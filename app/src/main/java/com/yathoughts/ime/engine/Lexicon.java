package com.yathoughts.ime.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import android.content.Context;

/**
 * 词库加载与查询。
 *
 * 资产文件 assets/lexicon/lex.bin，布局见 tools/build_lexicon.py：
 *   [magic 'QJL1'|'QJL2'][keyCount][key 记录...(keyLen2+key+start4+count4)][entryCount][entry 记录...]
 *   entry 记录: [charLen2][utf16 码元][freq4]
 *   QJL2 附加: [initialCount][initial 记录...(initLen2+init+keyCount4+keyIdx...)] 简拼索引
 *
 * 加载时：
 *   - keys 按字典序存为 byte[][]（二分用）
 *   - keyStart/keyCount int 数组
 *   - 一次 O(n) 扫描建 entryOffsets（每条 entry 在字节流中的起始偏移）→ 随机访问 O(1)
 *   - 词文本不预解码，按需从 byte[] 解（省内存）
 *   - QJL2：简拼索引同解析到内存（简拼 -> keys 下标，用于首字母联想）
 */
public final class Lexicon {

    private byte[][] keys;            // 升序 ASCII
    private int[] keyEntryStart;      // key 对应 entry 区间起点
    private int[] keyEntryCount;      // 区间长度
    private byte[] entryData;         // entry 区原始字节（QJL2 时含尾部简拼索引）
    private int[] entryOffsets;       // 每条 entry 在 entryData 中的偏移，长度 = entryCount+1

    private byte[][] initials;        // 升序 ASCII 简拼（QJL2），null 表示索引不可用
    private int[][] initialsKeys;     // 每条简拼对应的 key 下标列表

    private static final int MAX_RESULTS_PER_KEY = 60;

    public void load(Context context) throws IOException {
        byte[] raw = readAsset(context, "lexicon/lex.bin");
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        byte[] magic = new byte[4];
        buf.get(magic);
        if (magic[0] != 'Q' || magic[1] != 'J' || magic[2] != 'L'
                || (magic[3] != '1' && magic[3] != '2')) {
            throw new IOException("lex.bin magic mismatch");
        }
        boolean hasInitials = magic[3] == '2';
        int keyCount = buf.getInt();
        keys = new byte[keyCount][];
        keyEntryStart = new int[keyCount];
        keyEntryCount = new int[keyCount];
        for (int i = 0; i < keyCount; i++) {
            int kl = buf.getShort() & 0xFFFF;
            byte[] kb = new byte[kl];
            buf.get(kb);
            keys[i] = kb;
            keyEntryStart[i] = buf.getInt();
            keyEntryCount[i] = buf.getInt();
        }
        int entryCount = buf.getInt();
        int entryLen = raw.length - buf.position();
        entryData = new byte[entryLen];
        buf.get(entryData);

        // O(n) 扫描建偏移表
        entryOffsets = new int[entryCount + 1];
        int p = 0;
        for (int i = 0; i < entryCount; i++) {
            entryOffsets[i] = p;
            int charLen = readShort(p);
            p += 2 + charLen * 2 + 4;
        }
        entryOffsets[entryCount] = p;

        if (hasInitials) loadInitials();
    }

    /** QJL2 尾部简拼索引区：在 entry 区之后解析简拼 -> key 下标列表 */
    private void loadInitials() {
        int p = entryOffsets[entryOffsets.length - 1];
        if (p + 4 > entryData.length) return;
        int n = readInt(p);
        p += 4;
        // 每条简拼记录最少 6 字节（len2+count4）；按剩余字节数做安全边界，不再按固定条数上限
        if (n <= 0 || p >= entryData.length || n > (entryData.length - p) / 6) return;
        initials = new byte[n][];
        initialsKeys = new int[n][];
        for (int i = 0; i < n; i++) {
            if (p + 2 > entryData.length) { initials = null; initialsKeys = null; return; }
            int len = readShort(p);
            p += 2;
            if (p + len > entryData.length) { initials = null; initialsKeys = null; return; }
            byte[] ib = new byte[len];
            System.arraycopy(entryData, p, ib, 0, len);
            p += len;
            int cnt = readInt(p);
            p += 4;
            if (p + cnt * 4 > entryData.length) { initials = null; initialsKeys = null; return; }
            int[] ks = new int[cnt];
            for (int j = 0; j < cnt; j++) {
                ks[j] = readInt(p);
                p += 4;
            }
            initials[i] = ib;
            initialsKeys[i] = ks;
        }
    }

    /** 精确 key 查询：返回该 key 下按词频降序的候选（含 word、freq）。 */
    public List<Word> lookupExact(byte[] key) {
        List<Word> out = new ArrayList<>();
        int idx = binarySearch(key);
        if (idx < 0) return out;
        collect(idx, out, MAX_RESULTS_PER_KEY);
        return out;
    }

    /**
     * 前缀查询：所有以 prefix 开头的 key，收集组内 top 词（各组最多 perKey 条）。
     * 用于"用户还在打字"的预测候选（prefix 为已输入的完整字母串）。
     */
    public List<Word> lookupPrefix(byte[] prefix, int perKey) {
        List<Word> out = new ArrayList<>();
        int lo = lowerBound(prefix);
        if (lo < 0) return out;
        for (int i = lo; i < keys.length; i++) {
            if (!startsWith(keys[i], prefix)) break;
            collect(i, out, perKey);
            if (out.size() >= 200) break;   // 防御：超长范围截断
        }
        return out;
    }

    /**
     * 简拼查询：命中简拼（如 "zg"）→ 收集对应的 key 组的 top 词。
     * 词库为 QJL2 时可用；否则返回空。
     */
    public List<Word> lookupInitials(byte[] init, int perKey) {
        List<Word> out = new ArrayList<>();
        if (initials == null || init == null || init.length == 0) return out;
        int idx = binarySearchInitials(init);
        if (idx < 0) return out;
        int[] ks = initialsKeys[idx];
        for (int i = 0; i < ks.length; i++) {
            if (ks[i] < 0 || ks[i] >= keys.length) continue;
            collect(ks[i], out, perKey);
            if (out.size() >= 200) break;
        }
        return out;
    }

    public boolean hasInitialsIndex() {
        return initials != null;
    }

    /** key 是否存在。 */
    public boolean hasKey(byte[] key) {
        return binarySearch(key) >= 0;
    }

    private int binarySearchInitials(byte[] target) {
        int lo = 0, hi = initials.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = compareBytes(initials[mid], target);
            if (cmp < 0) lo = mid + 1;
            else if (cmp > 0) hi = mid - 1;
            else return mid;
        }
        return -1;
    }

    public int keyCount() {
        return keys == null ? 0 : keys.length;
    }

    // ---------- 内部 ----------

    private void collect(int keyIdx, List<Word> out, int limit) {
        int s = keyEntryStart[keyIdx];
        int c = keyEntryCount[keyIdx];
        int n = Math.min(c, limit);
        for (int i = 0; i < n; i++) {
            int e = entryOffsets[s + i];
            int charLen = readShort(e);
            StringBuilder sb = new StringBuilder(charLen);
            for (int j = 0; j < charLen; j++) {
                sb.append(readCharAt(e + 2 + j * 2));
            }
            int freq = readInt(e + 2 + charLen * 2);
            out.add(new Word(sb.toString(), freq));
        }
    }

    private int binarySearch(byte[] key) {
        int lo = 0, hi = keys.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = compareBytes(keys[mid], key);
            if (cmp < 0) lo = mid + 1;
            else if (cmp > 0) hi = mid - 1;
            else return mid;
        }
        return -1;
    }

    private int lowerBound(byte[] prefix) {
        int lo = 0, hi = keys.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (comparePrefix(keys[mid], prefix) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo < keys.length ? lo : -1;
    }

    private static int compareBytes(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) return d;
        }
        return a.length - b.length;
    }

    /** a 是否按"前缀顺序"与 p 比较：a 以 p 开头 → 0；否则按字节序比较。 */
    private static int comparePrefix(byte[] a, byte[] p) {
        int n = Math.min(a.length, p.length);
        for (int i = 0; i < n; i++) {
            int d = (a[i] & 0xFF) - (p[i] & 0xFF);
            if (d != 0) return d;
        }
        if (a.length == p.length) return 0;
        return a.length > p.length ? 0 : 1;   // a 更长且前缀相同 → a 在范围内 → 视作相等
    }

    private static boolean startsWith(byte[] a, byte[] p) {
        if (a.length < p.length) return false;
        for (int i = 0; i < p.length; i++) {
            if (a[i] != p[i]) return false;
        }
        return true;
    }

    private int readShort(int off) {
        return (entryData[off] & 0xFF) | ((entryData[off + 1] & 0xFF) << 8);
    }

    private int readInt(int off) {
        return (entryData[off] & 0xFF)
                | ((entryData[off + 1] & 0xFF) << 8)
                | ((entryData[off + 2] & 0xFF) << 16)
                | ((entryData[off + 3] & 0xFF) << 24);
    }

    private char readCharAt(int off) {
        return (char) ((entryData[off] & 0xFF) | ((entryData[off + 1] & 0xFF) << 8));
    }

    private static byte[] readAsset(Context context, String path) throws IOException {
        InputStream in = context.getAssets().open(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 23);
            byte[] tmp = new byte[1 << 16];
            int n;
            while ((n = in.read(tmp)) > 0) out.write(tmp, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** 候选词 */
    public static final class Word {
        public final String word;
        public final int freq;

        public Word(String word, int freq) {
            this.word = word;
            this.freq = freq;
        }
    }
}
