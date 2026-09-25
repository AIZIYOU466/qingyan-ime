package com.yathoughts.ime.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
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

        // 3) 前缀预测：用户还没打完，预测以"整个输入"为前缀的词
        byte[] whole = ascii(raw);
        if (whole != null) {
            for (String k : expandFuzzy(raw)) {
                byte[] kb = ascii(k);
                if (kb == null) continue;
                List<Lexicon.Word> pref = lexicon.lookupPrefix(kb, 8);
                // 预测候选降权
                addWords(out, byWord, pref, raw, 0.55);
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

    /** 模糊音变体展开（含自身）；组合上限 8 */
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
            Set<String> v = new HashSet<>();
            v.add(syl);
            String f = fuzzyVariant(syl);
            if (f != null) v.add(f);
            per.add(v);
            combos *= v.size();
            if (combos > 8) break;
        }
        out.clear();
        product(per, 0, new StringBuilder(), out);
        return out;
    }

    /** 单音节模糊变体：zh↔z ch↔c sh↔s（声母），an↔ang en↔eng in↔ing（韵尾） */
    private static String fuzzyVariant(String syl) {
        if (syl.startsWith("zh")) return "z" + syl.substring(2);
        if (syl.startsWith("z"))  return "zh" + syl.substring(1);
        if (syl.startsWith("ch")) return "c" + syl.substring(2);
        if (syl.startsWith("c") && !syl.equals("c")) return "ch" + syl.substring(1);
        if (syl.startsWith("sh")) return "s" + syl.substring(2);
        if (syl.startsWith("s") && !syl.equals("s")) return "sh" + syl.substring(1);
        if (syl.endsWith("ang")) return syl.substring(0, syl.length() - 3) + "an";
        if (syl.endsWith("an"))  return syl.substring(0, syl.length() - 2) + "ang";
        if (syl.endsWith("eng")) return syl.substring(0, syl.length() - 3) + "en";
        if (syl.endsWith("en") && !syl.equals("en")) return syl.substring(0, syl.length() - 2) + "eng";
        if (syl.endsWith("ing")) return syl.substring(0, syl.length() - 3) + "in";
        if (syl.endsWith("in") && !syl.equals("in")) return syl.substring(0, syl.length() - 2) + "ing";
        return null;
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
        if (limit <= 0 || syls.length < 2 || syls.length > 6) return out;

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
        for (int alt = 0; alt < 3 && produced < limit; alt++) {
            StringBuilder sb = new StringBuilder();
            double score = 0;
            boolean skip = false;
            for (int i = 0; i < perSyl.size(); i++) {
                List<Lexicon.Word> chars = perSyl.get(i);
                int pick = (alt == 1 && i == 0 && chars.size() > 1) ? 1 : (alt == 2 && i == perSyl.size() - 1 && chars.size() > 1 ? 1 : 0);
                sb.append(chars.get(pick).word);
                score += Math.log(1 + chars.get(pick).freq);
            }
            if (skip) continue;
            String word = sb.toString();
            Candidate c = new Candidate(word, key, score * 0.35, false);   // 组合词降权
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
