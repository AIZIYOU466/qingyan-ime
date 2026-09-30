package com.yathoughts.ime;

import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.yathoughts.ime.engine.PinyinEngine;
import com.yathoughts.ime.engine.UserDictHolder;
import com.yathoughts.ime.ui.CandidateView;
import com.yathoughts.ime.ui.KeyboardView;

import java.util.ArrayList;
import java.util.List;

/**
 * 输入法主服务：组合键盘 + 候选栏 + 拼音引擎。
 *
 * 组合逻辑：
 *  - 字母键: 累积 pendingPinyin，实时刷新候选；编辑框内组合文本 = 原始拼音字母
 *  - 候选选择: 上屏候选词（commitText），剩余拼音续打（级联）
 *  - 空格: 上屏首选
 *  - 回车: 组合中 = 上屏原始拼音字母；非组合 = 换行/输入法动作（搜索/发送/前往）
 *  - ⌫: 组合中删一个拼音字母；否则删除光标前字符
 *  - 中/英: 英文模式字母直通，组合拼音上屏为文本；?123 进符号页
 *
 * 按输入框类型（inputType）自适应：
 *  - 数字/电话/日期 → 数字键盘页
 *  - 密码/邮箱/URL  → 英文键盘（密码禁候选，避免泄露）
 *  - 普通文本       → 中文键盘 + 候选
 */
public final class ImeService extends InputMethodService implements KeyboardView.Listener, CandidateView.Listener {

    private KeyboardView keyboardView;
    private CandidateView candidateView;

    private PinyinEngine engine;   // UserDictHolder 单例，见 onCreate

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sp, key) -> {
                if (key == null) return;
                if (key.equals(KeyboardView.PREF_SCALE)
                        || key.equals(KeyboardView.PREF_HEIGHT)
                        || key.equals(KeyboardView.PREF_KEY)) {
                    if (keyboardView != null) applyKeyboardAdjust(sp);
                }
            };

    private String pendingPinyin = "";      // 组合中的拼音字母串
    private List<PinyinEngine.Candidate> candidates = new ArrayList<>();
    private boolean candidatesAllowed = true;   // 由当前 inputType 决定

    // ---------- 生命周期 ----------

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
        engine = UserDictHolder.get();
        engine.initAsync(getApplicationContext(), this::onEngineReady);
        // 键盘调节参数（含设置页改动）实时同步到键盘
        getSharedPreferences("ime_prefs", MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefListener);
    }

    private void applyKeyboardAdjust(SharedPreferences sp) {
        if (keyboardView == null) return;
        keyboardView.setAdjust(sp.getInt(KeyboardView.PREF_SCALE, 100),
                sp.getInt(KeyboardView.PREF_HEIGHT, 100),
                sp.getInt(KeyboardView.PREF_KEY, 100));
    }

    private void onEngineReady() {
        applyPrefs();
        if (pendingPinyin.length() > 0) refreshCandidates();
    }

    private void applyPrefs() {
        SharedPreferences sp = getSharedPreferences("ime_prefs", MODE_PRIVATE);
        engine.setFuzzyEnabled(sp.getBoolean("fuzzy", true));
        boolean haptic = sp.getBoolean("vibrate", true);
        if (keyboardView != null) keyboardView.setHapticEnabled(haptic);
        boolean sound = sp.getBoolean("sound", true);
        if (keyboardView != null) keyboardView.setSoundEnabled(sound);
        applyKeyboardAdjust(sp);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            getSharedPreferences("ime_prefs", MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(prefListener);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public View onCreateInputView() {
        float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);

        // 候选栏高度随屏幕自适应（约占屏高 7%，clamp 到舒适区间）
        float hDp = getResources().getDisplayMetrics().heightPixels / dp;
        float candRatio = 0.07f;
        int candH = (int) (Math.max(32f, Math.min(46f, candRatio * hDp)) * dp);

        candidateView = new CandidateView(this);
        candidateView.setListener(this);
        root.addView(candidateView, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, candH));

        keyboardView = new KeyboardView(this);
        keyboardView.setListener(this);
        root.addView(keyboardView, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        applyPrefs();
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        applyPrefs();
        // inputType → 面板 / 候选可见性
        int inputType = info == null ? EditorInfo.TYPE_CLASS_TEXT : info.inputType;
        int panel = panelFor(inputType);
        boolean forceEn = forceEnglish(inputType);
        candidatesAllowed = candidatesAllowedFor(inputType);
        if (keyboardView != null) {
            keyboardView.setPanel(panel);
            if (forceEn && keyboardView.getMode() == KeyboardView.MODE_CN) {
                keyboardView.setMode(KeyboardView.MODE_EN);
            }
        }
        // restarting（光标移动/切换字段）时保留组合，避免丢拼音
        if (!restarting) {
            resetComposition();
            if (candidateView != null) candidateView.setCandidates("", null);
        }
        applyCandidateVisibility();
    }

    /** 横屏也保持底部紧凑键盘（百度习惯），不进入全屏提取模式 */
    @Override
    public boolean isFullscreenMode() {
        return false;
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        resetComposition();
        super.onFinishInputView(finishingInput);
        engine.persistUserDict(getApplicationContext());
    }

    // ---------- KeyboardView.Listener ----------

    @Override
    public void onKey(String text, int code) {
        switch (code) {
            case KeyboardView.CODE_CHAR:
                onCharKey(text);
                break;
            case KeyboardView.CODE_BACKSPACE:
                onBackspace();
                break;
            case KeyboardView.CODE_SPACE:
                onSpace();
                break;
            case KeyboardView.CODE_ENTER:
                onEnter();
                break;
            default:
                // 逗号/句号等（text 直接处理）
                if (text != null) onPunctuation(text);
                break;
        }
    }

    @Override
    public void onPanelChanged(int panel) {
        if (panel == KeyboardView.PANEL_SYMBOL || panel == KeyboardView.PANEL_NUMBER) {
            // 进入符号/数字页：组合中的首选先上屏，避免拼音残留；调节页不动组合
            flushTopCandidate();
        }
        applyCandidateVisibility();
    }

    @Override
    public void onLanguageSwitched(int mode) {
        if (mode == KeyboardView.MODE_EN && pendingPinyin.length() > 0) {
            // 切英文：组合拼音上屏为普通文本（百度习惯），并清掉编辑框组合区
            String raw = pendingPinyin;
            resetComposition();
            InputConnection conn = ic();
            if (conn != null) conn.commitText(raw, 1);
        }
        applyCandidateVisibility();
    }

    // ---------- CandidateView.Listener ----------

    @Override
    public void onCandidatePicked(int index) {
        if (index < 0 || index >= candidates.size()) return;
        commitCandidate(candidates.get(index));
    }

    // ---------- 按键处理 ----------

    private void onCharKey(String letter) {
        if (keyboardView != null && keyboardView.getMode() == KeyboardView.MODE_EN) {
            // 英文模式：shift 大写
            String out = letter;
            if (keyboardView.getShiftState() > 0) {
                out = letter.toUpperCase();
                if (keyboardView.getShiftState() == 1) keyboardView.setShiftState(0);
            }
            InputConnection conn = ic();
            if (conn != null) conn.commitText(out, 1);
            return;
        }
        // 中文模式：累积拼音（仅当键盘仍在字母页；数字/符号页的字符直通）
        boolean alphaChar = letter.length() == 1 && letter.charAt(0) >= 'a' && letter.charAt(0) <= 'z';
        if (keyboardView != null && keyboardView.getPanel() == KeyboardView.PANEL_ALPHA && alphaChar) {
            InputConnection conn = ic();
            if (conn == null) return;
            if (pendingPinyin.length() >= 30) return;   // 防御上限
            pendingPinyin += letter;
            conn.setComposingText(pendingPinyin, 1);
            refreshCandidates();
        } else {
            // 数字/符号等直通
            commitPendingOrSend(letter);
        }
    }

    private void onPunctuation(String p) {
        InputConnection conn = ic();
        if (conn == null) return;
        // 组合中：逗号/句号 = 上屏首选 + 标点（百度习惯）
        if (pendingPinyin.length() > 0 && !candidates.isEmpty()) {
            PinyinEngine.Candidate first = candidates.get(0);
            engine.onCommit(first.word, first.pinyinKey);
            resetComposition();
            conn.commitText(first.word + p, 1);
        } else {
            conn.commitText(p, 1);
        }
    }

    private void onBackspace() {
        InputConnection conn = ic();
        if (conn == null) return;
        if (pendingPinyin.length() > 0) {
            pendingPinyin = pendingPinyin.substring(0, pendingPinyin.length() - 1);
            if (pendingPinyin.isEmpty()) {
                conn.setComposingText("", 1);   // 清掉组合区
                resetComposition();
            } else {
                conn.setComposingText(pendingPinyin, 1);
            }
            refreshCandidates();
        } else {
            conn.deleteSurroundingText(1, 0);
        }
    }

    private void onSpace() {
        InputConnection conn = ic();
        if (conn == null) return;
        if (pendingPinyin.length() > 0 && !candidates.isEmpty()) {
            commitCandidate(candidates.get(0));
        } else {
            conn.commitText(" ", 1);
        }
    }

    private void onEnter() {
        if (pendingPinyin.length() > 0) {
            // 上屏原始拼音字母
            String raw = pendingPinyin;
            resetComposition();
            InputConnection conn = ic();
            if (conn != null) conn.commitText(raw, 1);
            refreshCandidates();
        } else {
            InputConnection conn = ic();
            if (conn == null) return;
            EditorInfo info = getCurrentInputEditorInfo();
            int action = info == null ? EditorInfo.IME_ACTION_NONE : (info.imeOptions & EditorInfo.IME_MASK_ACTION);
            if (action != EditorInfo.IME_ACTION_NONE) {
                conn.performEditorAction(action);
            } else {
                conn.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                conn.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            }
        }
    }

    // ---------- 候选 ----------

    private void refreshCandidates() {
        if (!engine.isReady()) {
            if (candidateView != null) candidateView.setCandidates(pendingPinyin, null);
            return;
        }
        if (pendingPinyin.isEmpty()) {
            candidates = new ArrayList<>();
            if (candidateView != null) candidateView.setCandidates("", null);
            return;
        }
        candidates = engine.query(pendingPinyin, 20);
        List<String> words = new ArrayList<>();
        for (PinyinEngine.Candidate c : candidates) words.add(c.word);
        if (candidateView != null) candidateView.setCandidates(pendingPinyin, words);
    }

    private void commitCandidate(PinyinEngine.Candidate c) {
        InputConnection conn = ic();
        if (conn == null) return;
        String key = c.pinyinKey == null ? pendingPinyin : c.pinyinKey;
        engine.onCommit(c.word, key);
        String rest = remainingPinyin(key);
        if (rest != null && rest.length() > 0) {
            // 级联续打：选中词后剩余拼音继续
            pendingPinyin = rest;
            conn.commitText(c.word, 1);
            conn.setComposingText(rest, 1);
            refreshCandidates();
        } else {
            resetComposition();
            conn.commitText(c.word, 1);
        }
    }

    /** 计算选中词消耗拼音后的剩余串；模糊音开启时直接放弃续打（key 变体前缀不可靠） */
    private String remainingPinyin(String key) {
        if (engine.isFuzzyEnabled()) return null;
        String raw = pendingPinyin;
        int i = 0;
        int n = Math.min(raw.length(), key.length());
        while (i < n && raw.charAt(i) == key.charAt(i)) i++;
        return raw.substring(i);
    }

    private void flushTopCandidate() {
        if (pendingPinyin.length() == 0) return;
        InputConnection conn = ic();
        if (!candidates.isEmpty()) {
            PinyinEngine.Candidate first = candidates.get(0);
            engine.onCommit(first.word, first.pinyinKey == null ? pendingPinyin : first.pinyinKey);
            resetComposition();
            if (conn != null) conn.commitText(first.word, 1);
        } else {
            resetComposition();
        }
    }

    private void commitPendingOrSend(String s) {
        InputConnection conn = ic();
        resetComposition();
        if (conn != null) conn.commitText(s, 1);
    }

    private void resetComposition() {
        pendingPinyin = "";
        candidates = new ArrayList<>();
        if (candidateView != null) candidateView.setCandidates("", null);
        InputConnection conn = ic();
        if (conn != null) conn.setComposingText("", 1);
    }

    // ---------- inputType → 面板 / 候选可见性 ----------

    private static int panelFor(int inputType) {
        int cls = inputType & EditorInfo.TYPE_MASK_CLASS;
        if (cls == EditorInfo.TYPE_CLASS_NUMBER
                || cls == EditorInfo.TYPE_CLASS_PHONE
                || cls == EditorInfo.TYPE_CLASS_DATETIME) {
            return KeyboardView.PANEL_NUMBER;
        }
        return KeyboardView.PANEL_ALPHA;
    }

    private static boolean forceEnglish(int inputType) {
        int cls = inputType & EditorInfo.TYPE_MASK_CLASS;
        if (cls != EditorInfo.TYPE_CLASS_TEXT) return false;
        int var = inputType & EditorInfo.TYPE_MASK_VARIATION;
        return var == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                || var == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || var == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || var == EditorInfo.TYPE_TEXT_VARIATION_URI;
    }

    private static boolean candidatesAllowedFor(int inputType) {
        int cls = inputType & EditorInfo.TYPE_MASK_CLASS;
        if (cls != EditorInfo.TYPE_CLASS_TEXT) return false;   // 数字等不联想
        int var = inputType & EditorInfo.TYPE_MASK_VARIATION;
        if (var == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                || var == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) return false;
        if ((inputType & EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false;
        return true;
    }

    private void applyCandidateVisibility() {
        if (candidateView == null) return;
        boolean show = candidatesAllowed
                && keyboardView != null
                && keyboardView.getPanel() == KeyboardView.PANEL_ALPHA
                && keyboardView.getMode() == KeyboardView.MODE_CN;
        candidateView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private InputConnection ic() {
        return getCurrentInputConnection();
    }
}