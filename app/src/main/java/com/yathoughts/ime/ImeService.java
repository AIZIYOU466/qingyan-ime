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
 *  - 候选选择: 上屏候选词（commitText），剩余拼音续打
 *  - 空格: 上屏首选
 *  - 回车: 组合中 = 上屏原始拼音字母；非组合 = 换行/动作
 *  - ⌫: 组合中删一个拼音字母；否则删除光标前字符
 *  - 中/英: 英文模式字母直通
 */
public final class ImeService extends InputMethodService implements KeyboardView.Listener, CandidateView.Listener {

    private KeyboardView keyboardView;
    private CandidateView candidateView;

    private PinyinEngine engine;   // UserDictHolder 单例，见 onCreate

    private String pendingPinyin = "";      // 组合中的拼音字母串
    private List<PinyinEngine.Candidate> candidates = new ArrayList<>();
    private boolean engineReadyNotified;

    // ---------- 生命周期 ----------

    @Override
    public void onCreate() {
        super.onCreate();
        engine = UserDictHolder.get();
        engine.initAsync(getApplicationContext(), this::onEngineReady);
    }

    private void onEngineReady() {
        engineReadyNotified = true;
        applyPrefs();
        if (pendingPinyin.length() > 0) refreshCandidates();
    }

    private void applyPrefs() {
        SharedPreferences sp = getSharedPreferences("ime_prefs", MODE_PRIVATE);
        engine.setFuzzyEnabled(sp.getBoolean("fuzzy", false));
        boolean haptic = sp.getBoolean("vibrate", true);
        if (keyboardView != null) keyboardView.setHapticEnabled(haptic);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);

        candidateView = new CandidateView(this);
        candidateView.setListener(this);
        root.addView(candidateView, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int) (42 * dp)));

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
        resetComposition();
        if (candidateView != null) candidateView.setCandidates("", null);
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
    public void onModeKey(int mode) {
        if (mode == KeyboardView.MODE_EN) {
            resetComposition();
        }
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
            ic().commitText(out, 1);
            return;
        }
        // 中文模式：累积拼音
        if (letter.length() == 1 && letter.charAt(0) >= 'a' && letter.charAt(0) <= 'z') {
            InputConnection conn = ic();
            if (conn == null) return;
            if (pendingPinyin.length() >= 30) return;   // 防御上限
            pendingPinyin += letter;
            conn.setComposingText(pendingPinyin, 1);
            refreshCandidates();
        } else {
            // 数字等直通（长按首行时也会走这里）
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
                conn.commitText("", 1);   // 清掉组合区
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
        resetComposition();
        conn.commitText(c.word, 1);
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
    }

    private InputConnection ic() {
        return getCurrentInputConnection();
    }
}
