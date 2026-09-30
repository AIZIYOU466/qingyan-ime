package com.yathoughts.ime.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.yathoughts.ime.R;

/**
 * 26 键 QWERTY 自绘键盘。
 *
 * 面板:
 *   PANEL_ALPHA  字母页（中文 26 键 / 英文 QWERTY，?123 切换符号页）
 *   PANEL_SYMBOL 符号页（数字 + 常用标点，ABC 返回字母页）
 *   PANEL_NUMBER 数字页（inputType 为数字/电话/日期时由 Service 强制）
 *
 * 交互: 点按、滑动换键、⌫ 长按连删、首行字母长按出数字。
 * 触摸只跟踪第一根手指：多指按下时忽略后续手指，避免双手打字误触发。
 */
public final class KeyboardView extends View {

    public interface Listener {
        void onKey(String text, int code);

        /** 面板切换（进入/退出符号页、数字页） */
        void onPanelChanged(int panel);

        /** 中/英语言切换 */
        void onLanguageSwitched(int mode);
    }

    // 键码（onKey 的 code 参数）
    public static final int CODE_CHAR = 0;
    public static final int CODE_SHIFT = 1;
    public static final int CODE_BACKSPACE = 2;
    public static final int CODE_SPACE = 3;
    public static final int CODE_ENTER = 4;
    public static final int CODE_MODE_CN = 5;      // 中/英切换
    public static final int CODE_MODE_SYMBOL = 6;  // ?123 符号面板 / ABC 返回字母页

    // 语言模式（仅 PANEL_ALPHA 有效）
    public static final int MODE_CN = 0;
    public static final int MODE_EN = 1;

    // 面板
    public static final int PANEL_ALPHA = 0;
    public static final int PANEL_SYMBOL = 1;
    public static final int PANEL_NUMBER = 2;

    private static final class Key {
        String label;        // 主显示
        String altLabel;     // 英文模式显示（逗号/句号），null 无
        int code;
        float weight;        // 行内宽度权重
        boolean special;     // 功能键配色
        boolean accent;      // 强调色（回车）
        float x, y, w, h;    // 计算后
    }

    private Key[][] rows;
    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint altTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specialPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rrect = new RectF();

    // 颜色（从资源读，随暗色模式）
    private int colorKeyBg, colorKeyText, colorPressed, colorSpecial, colorAccentBg, colorAccentText, colorBg;

    private Listener listener;
    private int mode = MODE_CN;
    private int panel = PANEL_ALPHA;
    private int shiftState = 0;   // 0=off 1=once 2=lock

    // 触摸状态：只跟踪第一根手指
    private int activePointer = -1;
    private Key pressedKey;
    private boolean longPressFired;
    private final Runnable longPressRunnable = this::fireLongPress;
    private final Runnable repeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (pressedKey != null && pressedKey.code == CODE_BACKSPACE) {
                emitKey(pressedKey);
                postDelayed(this, 50);
            }
        }
    };

    private Vibrator vibrator;
    private boolean hapticEnabled = true;

    private ToneGenerator toneGen;
    private boolean soundEnabled = true;

    private static final long LONG_PRESS_MS = 350;

    public KeyboardView(Context context) {
        super(context);
        init();
    }

    public KeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bgPaint.setStyle(Paint.Style.FILL);
        keyPaint.setStyle(Paint.Style.FILL);
        specialPaint.setStyle(Paint.Style.FILL);
        bubblePaint.setStyle(Paint.Style.FILL);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT);
        altTextPaint.setTextAlign(Paint.Align.CENTER);
        altTextPaint.setTypeface(Typeface.DEFAULT);
        bubbleTextPaint.setTextAlign(Paint.Align.CENTER);
        bubbleTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        setLayerType(LAYER_TYPE_SOFTWARE, null);   // 阴影需要软件层
        refreshColors();
        buildPanel(PANEL_ALPHA);

        vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setHapticEnabled(boolean enabled) {
        hapticEnabled = enabled;
    }

    public void setSoundEnabled(boolean enabled) {
        soundEnabled = enabled;
    }

    /** 暗色模式变化后由 Service 调用刷新颜色 */
    public void refreshColors() {
        Resources r = getResources();
        colorKeyBg = r.getColor(R.color.kb_key_bg);
        colorKeyText = r.getColor(R.color.kb_key_text);
        colorPressed = r.getColor(R.color.kb_key_pressed);
        colorSpecial = r.getColor(R.color.kb_special_bg);
        colorAccentBg = r.getColor(R.color.kb_accent_key_bg);
        colorAccentText = r.getColor(R.color.kb_accent_key_text);
        colorBg = r.getColor(R.color.kb_bg);
        invalidate();
    }

    public void setMode(int m) {
        mode = m;
        if (panel == PANEL_ALPHA) buildPanel(PANEL_ALPHA);
        invalidate();
    }

    public int getMode() {
        return mode;
    }

    public int getPanel() {
        return panel;
    }

    public void setPanel(int p) {
        if (p != PANEL_ALPHA && p != PANEL_SYMBOL && p != PANEL_NUMBER) return;
        if (panel == p) return;
        panel = p;
        buildPanel(p);
        invalidate();
    }

    public int getShiftState() {
        return shiftState;
    }

    public void setShiftState(int s) {
        shiftState = s;
        updateCaseLabels();
        invalidate();
    }

    // ---------- 面板构建 ----------

    private void buildPanel(int p) {
        rows = new Key[4][];
        if (p == PANEL_SYMBOL) {
            buildSymbolPanel();
        } else if (p == PANEL_NUMBER) {
            buildNumberPanel();
        } else {
            buildAlphaPanel();
        }
    }

    private void buildAlphaPanel() {
        rows[0] = row("qwertyuiop", 1f);
        rows[1] = row("asdfghjkl", 1.11f);
        Key shift = special("⇧", CODE_SHIFT, 1.5f);
        Key back = special("⌫", CODE_BACKSPACE, 1.5f);
        Key[] mid = row("zxcvbnm", 1f);
        rows[2] = new Key[9];
        rows[2][0] = shift;
        System.arraycopy(mid, 0, rows[2], 1, mid.length);
        rows[2][8] = back;
        // r4: 中/英 ?123 ， 空格 。 ↵
        Key cn = special(mode == MODE_CN ? "中" : "英", CODE_MODE_CN, 1.2f);
        Key sym = special("?123", CODE_MODE_SYMBOL, 1.2f);
        Key comma = char1("，", ",", 1f);
        Key period = char1("。", ".", 1f);
        Key space = spaceKey(4.2f);
        Key enter = enterKey(1.6f);
        rows[3] = new Key[]{cn, sym, comma, space, period, enter};
    }

    private void buildSymbolPanel() {
        rows[0] = row("1234567890", 1f);
        rows[1] = row("！？，。；：“”（）", 1f);
        rows[2] = row("~@#%&-+*/=", 1f);
        Key abc = special("ABC", CODE_MODE_SYMBOL, 1.5f);
        Key space = spaceKey(3.2f);
        Key back = special("⌫", CODE_BACKSPACE, 1.5f);
        Key enter = enterKey(1.6f);
        rows[3] = new Key[]{abc, space, back, enter};
    }

    private void buildNumberPanel() {
        rows[0] = row("1234567890", 1f);
        rows[1] = row("!?,.;:()@#", 1f);
        rows[2] = row("~%&-+*/=<>", 1f);
        Key dot = char1(".", ".", 1f);
        Key space = spaceKey(3.2f);
        Key back = special("⌫", CODE_BACKSPACE, 1.5f);
        Key enter = enterKey(1.6f);
        rows[3] = new Key[]{dot, space, back, enter};
    }

    private Key[] row(String letters, float weight) {
        Key[] ks = new Key[letters.length()];
        for (int i = 0; i < letters.length(); i++) {
            Key k = new Key();
            k.label = String.valueOf(letters.charAt(i));
            k.code = CODE_CHAR;
            k.weight = weight;
            ks[i] = k;
        }
        return ks;
    }

    private Key special(String label, int code, float weight) {
        Key k = new Key();
        k.label = label;
        k.code = code;
        k.weight = weight;
        k.special = true;
        return k;
    }

    private Key char1(String cnLabel, String enLabel, float weight) {
        Key k = new Key();
        k.label = cnLabel;
        k.altLabel = enLabel;
        k.code = CODE_CHAR;
        k.weight = weight;
        return k;
    }

    private Key spaceKey(float weight) {
        Key k = new Key();
        k.label = "空格";
        k.code = CODE_SPACE;
        k.weight = weight;
        return k;
    }

    private Key enterKey(float weight) {
        Key k = new Key();
        k.label = "↵";
        k.code = CODE_ENTER;
        k.weight = weight;
        k.special = true;
        k.accent = true;
        return k;
    }

    private void updateCaseLabels() {
        boolean upper = (mode == MODE_EN && shiftState > 0);
        for (Key[] row : rows) {
            for (Key k : row) {
                if (k.code == CODE_CHAR && k.label.length() == 1 && Character.isLetter(k.label.charAt(0))) {
                    // 保持小写字母标签; 英文模式 shift 时上屏大写
                    k.label = k.label.toLowerCase();
                }
            }
        }
    }

    // ---------- 尺寸 ----------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = computeKeyboardHeight();
        setMeasuredDimension(width, height);
        layoutKeys(width, height);
    }

    /** 键盘高度随屏幕自适应：竖屏约为屏高 42%（168~224dp），横屏紧凑（92~128dp） */
    private int computeKeyboardHeight() {
        float dp = getResources().getDisplayMetrics().density;
        float wDp = getResources().getDisplayMetrics().widthPixels / dp;
        float hDp = getResources().getDisplayMetrics().heightPixels / dp;
        boolean landscape = hDp < wDp;
        float ratio;
        int dpMin, dpMax;
        if (landscape) {
            ratio = 0.52f;
            dpMin = 92;
            dpMax = 128;
        } else {
            ratio = 0.42f;
            dpMin = 168;
            dpMax = 224;
        }
        int target = (int) (hDp * ratio);
        target = Math.max(dpMin, Math.min(dpMax, target));
        // 极端小屏兜底：不超过屏高一半
        target = Math.min(target, Math.max(dpMin, (int) (hDp / 2)));
        return (int) (target * dp);
    }

    private void layoutKeys(int width, int height) {
        float dp = getResources().getDisplayMetrics().density;
        float gap = 4 * dp;
        float padX = 3 * dp;
        float padTop = 4 * dp;
        float keyH = (height - padTop - 4 * dp) / rows.length;

        for (int r = 0; r < rows.length; r++) {
            Key[] row = rows[r];
            float totalW = 0;
            for (Key k : row) totalW += k.weight;
            float avail = width - padX * 2 - gap * (row.length - 1);
            float x = padX;
            float y = padTop + r * (keyH + gap / 2);
            for (Key k : row) {
                k.w = avail * (k.weight / totalW);
                k.x = x;
                k.y = y;
                k.h = keyH;
                x += k.w + gap;
            }
        }
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas canvas) {
        float dp = getResources().getDisplayMetrics().density;
        canvas.drawColor(colorBg);
        float radius = 7 * dp;

        for (Key[] row : rows) {
            for (Key k : row) {
                rrect.set(k.x, k.y, k.x + k.w, k.y + k.h);
                Paint p;
                int textColor;
                if (k == pressedKey) {
                    keyPaint.setColor(colorPressed);
                    p = keyPaint;
                    textColor = colorKeyText;
                } else if (k.accent) {
                    keyPaint.setColor(colorAccentBg);
                    p = keyPaint;
                    textColor = colorAccentText;
                } else if (k.special) {
                    specialPaint.setColor(colorSpecial);
                    p = specialPaint;
                    textColor = colorKeyText;
                } else {
                    keyPaint.setColor(colorKeyBg);
                    keyPaint.setShadowLayer(2 * dp, 0, 1.5f * dp, 0x22000000);
                    p = keyPaint;
                    textColor = colorKeyText;
                }
                canvas.drawRoundRect(rrect, radius, radius, p);

                float cx = k.x + k.w / 2;
                float cy = k.y + k.h / 2;
                textPaint.setColor(textColor);
                String label = k.label;
                if (k.code == CODE_CHAR && mode == MODE_EN && k.altLabel != null) {
                    // 英文模式显示半角标点
                    label = k.altLabel;
                }
                if (k.code == CODE_CHAR && label.length() == 1 && Character.isLetter(label.charAt(0))) {
                    // 英文模式 shift 大写显示
                    label = (mode == MODE_EN && shiftState > 0) ? label.toUpperCase() : label;
                    textPaint.setTextSize(22 * dp);
                } else if (k.code == CODE_SPACE) {
                    textPaint.setTextSize(14 * dp);
                } else if (panel == PANEL_ALPHA && k.code == CODE_CHAR) {
                    textPaint.setTextSize(16 * dp);
                } else {
                    textPaint.setTextSize(16 * dp);
                }
                float textY = cy - (textPaint.descent() + textPaint.ascent()) / 2;
                canvas.drawText(label, cx, textY, textPaint);

                // 首行字母右上角数字提示
                if (k.code == CODE_CHAR && panel == PANEL_ALPHA
                        && "qwertyuiop".contains(k.label) && mode == MODE_CN) {
                    int idx = "qwertyuiop".indexOf(k.label);
                    String digit = idx == 9 ? "0" : String.valueOf(idx + 1);
                    altTextPaint.setColor(0x886B7280);
                    altTextPaint.setTextSize(9 * dp);
                    canvas.drawText(digit, k.x + k.w - 9 * dp, k.y + 13 * dp, altTextPaint);
                }
            }
        }

        // 按键气泡（仅字母页的字母键）
        if (pressedKey != null && pressedKey.code == CODE_CHAR && panel == PANEL_ALPHA && !longPressFired) {
            float dp2 = getResources().getDisplayMetrics().density;
            float bw = Math.max(pressedKey.w, 44 * dp2);
            float bh = pressedKey.h * 1.35f;
            float bx = pressedKey.x + pressedKey.w / 2 - bw / 2;
            float by = pressedKey.y - bh + 6 * dp2;
            rrect.set(bx, by, bx + bw, by + bh);
            bubblePaint.setColor(colorAccentBg);
            canvas.drawRoundRect(rrect, 9 * dp2, 9 * dp2, bubblePaint);
            bubbleTextPaint.setColor(colorAccentText);
            bubbleTextPaint.setTextSize(26 * dp2);
            String show = pressedKey.label;
            if (mode == MODE_EN && shiftState > 0) show = show.toUpperCase();
            float ty = (by + pressedKey.y + 6 * dp2) / 2 - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2;
            canvas.drawText(show, bx + bw / 2, ty, bubbleTextPaint);
        }
    }

    // ---------- 触摸 ----------

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (activePointer != -1) return true;   // 异常状态防御
                int idx = ev.getActionIndex();
                activePointer = ev.getPointerId(idx);
                pressedKey = keyAt(ev.getX(idx), ev.getY(idx));
                longPressFired = false;
                if (pressedKey != null) {
                    haptic();
                    postDelayed(longPressRunnable, LONG_PRESS_MS);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // 第二根及后续手指：忽略，保持第一个活动指，避免双手打字误触发
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int idx = ev.findPointerIndex(activePointer);
                if (idx >= 0 && pressedKey != null) {
                    Key k = keyAt(ev.getX(idx), ev.getY(idx));
                    if (k != pressedKey) {
                        // 滑动换键：取消长按，更新
                        pressedKey = k;
                        longPressFired = false;
                        removeCallbacks(longPressRunnable);
                        removeCallbacks(repeatRunnable);
                        if (pressedKey != null) postDelayed(longPressRunnable, LONG_PRESS_MS);
                        invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                // 只有活动指抬起才算一次点击；其余手指抬起忽略
                int idx = ev.getActionIndex();
                if (ev.getPointerId(idx) == activePointer) {
                    removeCallbacks(longPressRunnable);
                    removeCallbacks(repeatRunnable);
                    if (pressedKey != null && !longPressFired) {
                        emitKey(pressedKey);
                    }
                    pressedKey = null;
                    activePointer = -1;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPressRunnable);
                removeCallbacks(repeatRunnable);
                if (ev.getActionMasked() == MotionEvent.ACTION_UP && pressedKey != null && !longPressFired) {
                    emitKey(pressedKey);
                }
                pressedKey = null;
                activePointer = -1;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void fireLongPress() {
        if (pressedKey == null) return;
        if (pressedKey.code == CODE_BACKSPACE) {
            longPressFired = true;
            emitKey(pressedKey);
            postDelayed(repeatRunnable, 50);
        } else if (pressedKey.code == CODE_CHAR && panel == PANEL_ALPHA
                && "qwertyuiop".contains(pressedKey.label) && mode == MODE_CN) {
            // 长按首行 → 上屏数字
            int idx = "qwertyuiop".indexOf(pressedKey.label);
            String digit = idx == 9 ? "0" : String.valueOf(idx + 1);
            longPressFired = true;
            if (listener != null) listener.onKey(digit, CODE_CHAR);
            haptic();
            pressedKey = null;
            invalidate();
        } else if (pressedKey.code == CODE_SHIFT) {
            // 长按 shift = 锁定
            longPressFired = true;
            shiftState = shiftState == 2 ? 0 : 2;
            invalidate();
        }
    }

    private void emitKey(Key k) {
        haptic();
        playKeySound();
        if (listener == null) return;
        switch (k.code) {
            case CODE_SHIFT:
                shiftState = shiftState == 0 ? 1 : (shiftState == 1 ? 2 : 0);
                invalidate();
                break;
            case CODE_MODE_CN:
                mode = mode == MODE_CN ? MODE_EN : MODE_CN;
                buildPanel(PANEL_ALPHA);
                invalidate();
                listener.onLanguageSwitched(mode);
                break;
            case CODE_MODE_SYMBOL:
                panel = panel == PANEL_ALPHA ? PANEL_SYMBOL : PANEL_ALPHA;
                buildPanel(panel);
                invalidate();
                listener.onPanelChanged(panel);
                break;
            default:
                String out = k.label;
                // 英文模式：逗号/句号等输出半角
                if (mode == MODE_EN && k.altLabel != null) out = k.altLabel;
                listener.onKey(out, k.code);
        }
    }

    private void haptic() {
        if (!hapticEnabled || vibrator == null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(12);
            }
        } catch (Throwable ignored) {
            // 部分机型/ROM 无振动器或权限被禁，震动失败不应影响输入
        }
    }

    private void playKeySound() {
        if (!soundEnabled) return;
        try {
            if (toneGen == null) {
                toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            }
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 30);
        } catch (Throwable ignored) {
            // 无音频设备/资源受限时静音，不影响输入
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (toneGen != null) {
            try {
                toneGen.release();
            } catch (Throwable ignored) {
            }
            toneGen = null;
        }
    }

    private Key keyAt(float x, float y) {
        for (Key[] row : rows) {
            for (Key k : row) {
                if (x >= k.x && x <= k.x + k.w && y >= k.y && y <= k.y + k.h) return k;
            }
        }
        return null;
    }
}
