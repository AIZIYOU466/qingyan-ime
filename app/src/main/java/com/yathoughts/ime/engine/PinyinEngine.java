package com.yathoughts.ime.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拼音引擎：
 *  1. 输入字母串 → 音节切分（SyllableSplitter）
 *  2. 每种切分拼出 pinyin key（连写），精确查词库 + 前缀预测
 *  3. 模糊音展开（可选）
 *  4. 词级候选合并去重排序；不足时用 Viterbi 组单字成词/短句
 *  5. 用户词自学习（UserDict）
 */
public final class PinyinEngine {

    public interface ReadyCallback {
        void onReady();
    }

    private final Lexicon lexicon = new Lexicon();
    private final UserDict userDict = new UserDict();
    private volatile boolean ready = false;

    // 模糊音开关（默认关，设置页可开）
    private volatile boolean fuzzyEnabled = false;

    private static final int MAX_COMPOSE_SYLLABLES = 24;   // Viterbi 组词最多覆盖的音节数

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void initAsync(Context appContext, ReadyCallback cb) {
        Thread t = new Thread(() -> {
            try {
                lexicon.load(appContext);
                userDict.load(appContext);
                synchronized (this) { ready = true; }
                mainHandler.post(cb::onReady);
            } catch (Exception e) {
                mainHandler.post(cb::onReady);   // 失败也回调，避免输入法卡死
            }
        }, "ime-lexicon-load");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    public boolean isReady() {
        synchronized (this) { return ready; }
    }

    public void setFuzzyEnabled(boolean enabled) {
        this.fuzzyEnabled = enabled;
    }

    public boolean isFuzzyEnabled() {
        return fuzzyEnabled;
    }

    public void clearUserDict(Context context) {
        userDict.clear(context);
    }

    /** 持久化用户词（Service 结束输入时调用） */
    public void persistUserDict(Context context) {
        userDict.persistIfDirty(context);
    }

    /**
     * 主查询入口。
     *
     * @param raw 用户已输入的拼音字母串（小写 a-z + 可能的 '）
     * @return 排序后的候选（最多 limit 条）
     */
    public List<Candidate> query(String raw, int limit) {
        List<Candidate> out = new ArrayList<>();
        if (!ready || raw == null || raw.isEmpty()) return out;
        raw = normalize(raw);
        if (raw.isEmpty()) return out;

        Map<String, Candidate> byWord = new HashMap<>();

        // 1) 枚举音节切分
        List<String[]> splits = SyllableSplitter.split(raw);

        // 2) 精确 key 查询（每条切分 → 连写 key；模糊展开）
        for (String[] syls : splits) {
            String key = joinKey(syls);
            if (key == null) continue;
            for (String k : expandFuzzy(key)) {
                byte[] kb = ascii(k);
                if (kb == null) continue;
                // 精确 key
                List<Lexicon.Word> words = lexicon.lookupExact(kb);
                addWords(out, byWord, words, key, 1.0);
                // 用户词
                UserDict.Entry ue = userDict.get(key);
                if (ue != null) {
                    addToMap(out, byWord, new Candidate(ue.word, key, ue.score + 1_000_000_0, true));
                }
            }
        }

        // 3) 前缀预测：用户还没打完，按"已输入串为前缀"联想完整拼音词。
        //    权重接近精确命中（百度习惯）；候选不足时退避删尾字母补（nih→ni→你好）
        byte[] whole = ascii(raw);
        if (whole != null) {
            String prefix = raw;
            for (int d = 0; d < 2; d++) {
                if (prefix.isEmpty()) break;
                for (String k : expandFuzzy(prefix)) {
                    byte[] kb = ascii(k);
                    if (kb == null) continue;
                    addWords(out, byWord, lexicon.lookupPrefix(kb, 10), raw, d == 0 ? 0.9 : 0.7);
                }
                if (out.size() >= limit) break;
                if (prefix.length() <= 1) break;
                prefix = prefix.substring(0, prefix.length() - 1);
            }
        }

        // 3.5) 简拼联想：输入 2~5 位字母时按首字母简拼查词（zg→中国）；不因候选已满而跳过
        if (raw.length() >= 2 && raw.length() <= 5
                && raw.indexOf('\'') < 0 && lexicon.hasInitialsIndex()) {
            String ini = raw;
            for (int d = 0; d < 2; d++) {
                if (ini.isEmpty()) break;
                byte[] ib = ascii(ini);
                if (ib == null) break;
                addWords(out, byWord, lexicon.lookupInitials(ib, 6), raw, d == 0 ? 0.85 : 0.7);
                if (out.size() >= limit) break;
                if (ini.length() <= 1) break;
                ini = ini.substring(0, ini.length() - 1);
            }
        }

        // 4) 候选不足 → Viterbi 单字组词（对最优切分）
        if (out.size() < limit && !splits.isEmpty()) {
            List<Candidate> composed = viterbiChars(splits.get(0), limit - out.size() + 5);
            for (Candidate c : composed) addToMap(out, byWord, c);
        }

        // 5) 排序输出
        out.sort((a, b) -> Double.compare(b.score, a.score));
        if (out.size() > limit) out.subList(limit, out.size()).clear();
        return out;
    }

    /** 用户选定候选上屏 → 学习 */
    public void onCommit(String word, String pinyinKey) {
        if (word == null || word.isEmpty() || pinyinKey == null || pinyinKey.isEmpty()) return;
        userDict.bump(word, pinyinKey);
    }

    // ---------- 内部工具 ----------

    private static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || c == '\'') sb.append(c);
        }
        return sb.toString();
    }

    private static String joinKey(String[] syls) {
        if (syls == null || syls.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (String s : syls) {
            if (s == null || s.isEmpty()) return null;
            sb.append(s);
        }
        return sb.toString();
    }

    private static byte[] ascii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return null;
        }
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** 模糊音变体展开：原 key 恒在首位，其后追加模糊变体；变体组合上限 8 */
    private List<String> expandFuzzy(String key) {
        List<String> out = new ArrayList<>();
        out.add(key);
        if (!fuzzyEnabled) return out;
        // 按音节切分再逐音节生成变体
        List<String[]> splits = SyllableSplitter.split(key);
        if (splits.isEmpty()) return out;
        String[] syls = splits.get(0);
        List<Set<String>> per = new ArrayList<>();
        int combos = 1;
        for (String syl : syls) {
            Set<String> v = fuzzyVariants(syl);
            per.add(v);
            combos *= v.size();
        }
        if (combos <= 1) return out;   // 无变体，仅原 key
        List<String> vars = new ArrayList<>();
        product(per, 0, new StringBuilder(), vars);
        for (String v : vars) if (!v.equals(key)) out.add(v);
        return out;
    }

    /**
     * 单音节模糊变体集合（含自身）：
     *   声母 zh/z、ch/c、sh/s、n/l、f/h、r/l；
     *   韵尾 an/ang、en/eng、in/ing、ian/iang、uan/uang
     */
    private static Set<String> fuzzyVariants(String syl) {
        Set<String> v = new LinkedHashSet<>();
        v.add(syl);
        if (syl.startsWith("zh")) v.add("z" + syl.substring(2));
        else if (syl.startsWith("z")) v.add("zh" + syl.substring(1));
        if (syl.startsWith("ch")) v.add("c" + syl.substring(2));
        else if (syl.startsWith("c")) v.add("ch" + syl.substring(1));
        if (syl.startsWith("sh")) v.add("s" + syl.substring(2));
        else if (syl.startsWith("s")) v.add("sh" + syl.substring(1));
        swapInitial(v, syl, "n", "l");
        swapInitial(v, syl, "l", "n");
        swapInitial(v, syl, "f", "h");
        swapInitial(v, syl, "h", "f");
        swapInitial(v, syl, "r", "l");
        swapInitial(v, syl, "l", "r");
        swapFinal(v, syl, "iang", "ian");
        swapFinal(v, syl, "uang", "uan");
        swapFinal(v, syl, "ang", "an");
        swapFinal(v, syl, "an", "ang");
        swapFinal(v, syl, "eng", "en");
        swapFinal(v, syl, "en", "eng");
        swapFinal(v, syl, "ing", "in");
        swapFinal(v, syl, "in", "ing");
        return v;
    }

    private static void swapInitial(Set<String> v, String syl, String from, String to) {
        if (syl.length() > from.length() && syl.startsWith(from)) {
            v.add(to + syl.substring(from.length()));
        }
    }

    private static void swapFinal(Set<String> v, String syl, String from, String to) {
        if (syl.length() > from.length() && syl.endsWith(from)) {
            v.add(syl.substring(0, syl.length() - from.length()) + to);
        }
    }

    private static void product(List<Set<String>> per, int i, StringBuilder acc, List<String> out) {
        if (out.size() >= 8) return;
        if (i == per.size()) {
            out.add(acc.toString());
            return;
        }
        for (String s : per.get(i)) {
            acc.append(s);
            product(per, i + 1, acc, out);
            acc.setLength(acc.length() - s.length());
        }
    }

    private void addWords(List<Candidate> out, Map<String, Candidate> byWord,
                          List<Lexicon.Word> words, String key, double weight) {
        for (Lexicon.Word w : words) {
            double score = Math.log(1 + w.freq) * weight;
            Candidate c = new Candidate(w.word, key, score, false);
            Candidate old = byWord.get(w.word);
            if (old == null || c.score > old.score) {
                addToMap(out, byWord, c);
            }
        }
    }

    private static void addToMap(List<Candidate> out, Map<String, Candidate> byWord, Candidate c) {
        Candidate old = byWord.get(c.word);
        if (old == null) {
            byWord.put(c.word, c);
            out.add(c);
        } else if (c.score > old.score) {
            old.score = c.score;
            old.pinyinKey = c.pinyinKey;
        }
    }

    /** Viterbi 单字组词：对一种切分，逐音节取候选字，用词频近似组合（无 bigram 数据时的兜底） */
    private List<Candidate> viterbiChars(String[] syls, int limit) {
        List<Candidate> out = new ArrayList<>();
        if (limit <= 0 || syls.length < 2 || syls.length > MAX_COMPOSE_SYLLABLES) return out;

        // 每个音节取 top 字
        List<List<Lexicon.Word>> perSyl = new ArrayList<>();
        for (String syl : syls) {
            byte[] kb = ascii(syl);
            if (kb == null) return out;
            List<Lexicon.Word> chars = lexicon.lookupExact(kb);
            if (chars.size() > 6) chars.subList(6, chars.size()).clear();
            if (chars.isEmpty()) return out;
            perSyl.add(chars);
        }

        // 生成 top 组合（贪心 + 次优首字变化）
        String key = joinKey(syls);
        int produced = 0;
        for (int alt = 0; alt < 8 && produced < limit; alt++) {
            StringBuilder sb = new StringBuilder();
            double score = 0;
            for (int i = 0; i < perSyl.size(); i++) {
                List<Lexicon.Word> chars = perSyl.get(i);
                int altPos = alt == 0 ? -1 : (alt - 1) % perSyl.size();
                int pick = (i == altPos && chars.size() > 1) ? 1 : 0;
                sb.append(chars.get(pick).word);
                score += Math.log(1 + chars.get(pick).freq);
            }
            String word = sb.toString();
            // 组合词一律排在真实词库词之后：加负偏移，仅内部保留词频顺序
            Candidate c = new Candidate(word, key, score * 0.35 - 1_000_000, false);
            boolean dup = false;
            for (Candidate o : out) if (o.word.equals(word)) { dup = true; break; }
            if (!dup) {
                out.add(c);
                produced++;
            }
        }
        return out;
    }

    /** 候选 */
    public static final class Candidate {
        public final String word;
        public String pinyinKey;   // 用于用户词学习
        public double score;
        public final boolean fromUser;

        Candidate(String word, String pinyinKey, double score, boolean fromUser) {
            this.word = word;
            this.pinyinKey = pinyinKey;
            this.score = score;
            this.fromUser = fromUser;
        }
    }
}
