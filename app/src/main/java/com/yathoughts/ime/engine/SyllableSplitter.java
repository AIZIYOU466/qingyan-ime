package com.yathoughts.ime.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 拼音音节切分：把用户输入的字母串枚举出所有合法音节切分（含分隔符 '）。
 * 例: "xian" → [xian] / [xi an]（封顶 12 种，按"音节数少优先"排序）
 * 例: "xi'an" → 强制切分为 [xi an]
 */
public final class SyllableSplitter {

    /** 合法韵母表（无声调，ü 用 v 表示） */
    private static final Set<String> FINALS = new HashSet<>();
    /** 声母 → 允许的韵母（"" 表示无声母）；宽松超集，容忍常见手误 */
    private static final String[][] INITIAL_TABLE;

    static {
        // 全部韵母
        String[] allFinals = {
                "a", "o", "e", "i", "u", "v",
                "ai", "ei", "ui", "ao", "ou", "iu", "ie", "ve", "er",
                "an", "en", "in", "un", "vn",
                "ang", "eng", "ing", "ong",
                "ia", "iao", "ian", "iang", "ua", "uo", "uai", "uan", "uang", "ue", "iong", "ueng"
        };
        for (String f : allFinals) FINALS.add(f);

        INITIAL_TABLE = new String[][] {
                // 声母(0=无声母) + 允许韵母列表
                {"",   "a", "o", "e", "ai", "ei", "ao", "ou", "er", "an", "en", "ang", "eng"},
                {"b",  "a", "o", "e", "i", "u", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng"},
                {"p",  "a", "o", "e", "i", "u", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng"},
                {"m",  "a", "o", "e", "i", "u", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng"},
                {"f",  "a", "o", "e", "u", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng"},
                {"d",  "a", "o", "e", "i", "u", "ai", "ei", "ui", "ao", "ou", "iu", "ie", "an", "en", "ang", "eng", "ong", "iao", "ian", "uan", "un", "uo"},
                {"t",  "a", "o", "e", "i", "u", "ai", "ei", "ui", "ao", "ou", "ie", "an", "en", "ang", "eng", "ong", "iao", "ian", "uan", "un", "uo"},
                {"n",  "a", "o", "e", "i", "u", "v", "ai", "ei", "ui", "ao", "ou", "iu", "ie", "ve", "an", "en", "ang", "eng", "ong", "iao", "ian", "iang", "uan", "un", "uo"},
                {"l",  "a", "o", "e", "i", "u", "v", "ai", "ei", "ui", "ao", "ou", "iu", "ie", "ve", "an", "en", "ang", "eng", "ong", "iao", "ian", "iang", "uan", "un", "uo"},
                {"g",  "a", "o", "e", "u", "ai", "ei", "ui", "ao", "ou", "an", "en", "ang", "eng", "ong", "uai", "uan", "un", "uang", "ua", "uo"},
                {"k",  "a", "o", "e", "u", "ai", "ei", "ui", "ao", "ou", "an", "en", "ang", "eng", "ong", "uai", "uan", "un", "uang", "ua", "uo"},
                {"h",  "a", "o", "e", "u", "ai", "ei", "ui", "ao", "ou", "an", "en", "ang", "eng", "ong", "uai", "uan", "un", "uang", "ua", "uo"},
                {"j",  "i", "u", "ia", "ie", "iao", "iu", "ian", "in", "iang", "ing", "iong", "uan", "un", "ue"},
                {"q",  "i", "u", "ia", "ie", "iao", "iu", "ian", "in", "iang", "ing", "iong", "uan", "un", "ue"},
                {"x",  "i", "u", "ia", "ie", "iao", "iu", "ian", "in", "iang", "ing", "iong", "uan", "un", "ue"},
                {"zh", "a", "o", "e", "i", "u", "ai", "ei", "ui", "ao", "ou", "an", "en", "ang", "eng", "ong", "uai", "uan", "un", "uang", "ua", "uo"},
                {"ch", "a", "o", "e", "i", "u", "ai", "ei", "ui", "ao", "ou", "an", "en", "ang", "eng", "ong", "uai", "uan", "un", "uang", "ua", "uo"},
                {"sh", "a", "o", "e", "i", "u", "ai", "ei", "ui", "ao", "ou", "an", "en", "ang", "eng", "ong", "uai", "uan", "un", "uang", "ua", "uo"},
                {"r",  "a", "o", "e", "i", "u", "ai", "ei", "ui", "ao", "ou", "an", "en", "ang", "eng", "ong", "uan", "un", "ua", "uo"},
                {"z",  "a", "o", "e", "i", "u", "ai", "ei", "ui", "ao", "ou", "an", "en", "ang", "eng", "ong", "uai", "uan", "un", "ua", "uo"},
                {"c",  "a", "o", "e", "i", "u", "ai", "ei", "ui", "ao", "ou", "an", "en", "ang", "eng", "ong", "uan", "un", "ua", "uo"},
                {"s",  "a", "o", "e", "i", "u", "ai", "ei", "ui", "ao", "ou", "an", "en", "ang", "eng", "ong", "uan", "un", "ua", "uo"},
                {"y",  "a", "ao", "an", "ang", "e", "i", "in", "ing", "ong", "ou", "u", "ue", "uan", "un", "ia", "iao", "ian", "iang", "ie"},
                {"w",  "a", "ai", "an", "ang", "ei", "en", "eng", "o", "u", "ia", "ua", "uo"}
        };
    }

    private static final int MAX_PATHS = 12;
    private static final int MAX_SYLLABLE_LEN = 6;   // zhuang/xiang 等

    /**
     * 枚举切分。raw: 已小写、仅含 a-z 和 ' 的输入。
     * 返回切分方案列表（每项为音节数组），按音节数升序（更少音节 = 更可能是用户意图）。
     */
    public static List<String[]> split(String raw) {
        List<String[]> paths = new ArrayList<>();
        // 按 ' 强制分段
        String[] segs = raw.split("'+");
        if (segs.length == 0 || (segs.length == 1 && segs[0].isEmpty())) {
            if (raw.contains("'")) return paths;   // 纯分隔符
            segs = new String[]{raw};
        }
        enumerate(segs, 0, new ArrayList<>(), paths);
        paths.sort((a, b) -> Integer.compare(a.length, b.length));
        if (paths.size() > MAX_PATHS) {
            paths.subList(MAX_PATHS, paths.size()).clear();
        }
        return paths;
    }

    private static void enumerate(String[] segs, int segIdx, List<String> acc, List<String[]> out) {
        if (out.size() >= MAX_PATHS * 2) return;   // 搜索上限
        if (segIdx == segs.length) {
            out.add(acc.toArray(new String[0]));
            return;
        }
        String seg = segs[segIdx];
        if (seg.isEmpty()) {
            enumerate(segs, segIdx + 1, acc, out);
            return;
        }
        recSplit(seg, 0, acc, segs, segIdx, out);
    }

    private static void recSplit(String s, int pos, List<String> acc, String[] segs, int segIdx, List<String[]> out) {
        if (out.size() >= MAX_PATHS * 2) return;
        if (pos == s.length()) {
            enumerate(segs, segIdx + 1, acc, out);
            return;
        }
        int maxLen = Math.min(MAX_SYLLABLE_LEN, s.length() - pos);
        for (int len = maxLen; len >= 1; len--) {
            String part = s.substring(pos, pos + len);
            if (isValidSyllable(part)) {
                acc.add(part);
                recSplit(s, pos + len, acc, segs, segIdx, out);
                acc.remove(acc.size() - 1);
            }
        }
    }

    /** 音节合法性：声母 + 韵母组合是否在表内（宽松匹配） */
    public static boolean isValidSyllable(String s) {
        if (s.isEmpty()) return false;
        for (String[] row : INITIAL_TABLE) {
            String initial = row[0];
            if (!s.startsWith(initial)) continue;
            String rest = s.substring(initial.length());
            if (rest.isEmpty() || FINALS.contains(rest)) {
                // 声母行里声母+该韵母组合允许（row 里包含 rest 即可；首行已含空韵母情形）
                for (int i = 1; i < row.length; i++) {
                    if (row[i].equals(rest)) return true;
                }
            }
        }
        return false;
    }
}
