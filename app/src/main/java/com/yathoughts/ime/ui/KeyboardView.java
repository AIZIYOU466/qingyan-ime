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
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.yathoughts.ime.R;

/**
 * 26 键 QWERTY 自绘键盘。
 *
 * 面板:
 *   PANEL_ALPHA   字母页（中文 26 键 / 英文 QWERTY，?123 切换符号页）
 *   PANEL_SYMBOL  符号页（数字 + 常用标点，ABC 返回字母页）
 *   PANEL_NUMBER  数字页（inputType 为数字/电话/日期时由 Service 强制）
 *   PANEL_ADJUST  调节页（面板缩放 / 面板高度 / 按键大小，拖动滑杆实时生效并持久化）
 *
 * 自由调节: 三个参数（面板缩放 panelScale、面板高度 panelHeight、按键大小 keyScale）
 * 独立控制又相乘联动——最终键高 = 基础键高 × panelScale × panelHeight × keyScale。
 *
 * 交互: 点按、滑动换键、⌫ 长按连删、首行字母长按出数字。
 * 触摸只跟踪第一根手指：多指按下时忽略后续手指，避免双手打字误触发。
 */
public final class KeyboardView extends View {

    public interface Listener {
        void onKey(String text, int code);

        /** 面板切换（进入/退出符号页、数字页、调节页） */
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
    public static final int CODE_MODE_SETTINGS = 7; // ⚙ 键盘调节页

    // 语言模式（仅 PANEL_ALPHA 有效）
    public static final int MODE_CN = 0;
    public static final int MODE_EN = 1;

    // 面板
    public static final int PANEL_ALPHA = 0;
    public static final int PANEL_SYMBOL = 1;
    public static final int PANEL_NUMBER = 2;
    public static final int PANEL_ADJUST = 3;

    // 调节参数的 prefs 键
    public static final String PREF_SCALE = "kb_scale";     // 面板缩放 50~150
    public static final String PREF_HEIGHT = "kb_height";   // 面板高度 60~140
    public static final String PREF_KEY = "kb_key";         // 按键大小 60~150

    private static final String PREFS = "ime_prefs";

    /** 调节页滑杆的三个 min/max（面板缩放/高度/按键大小） */
    private static final int[][] SLIDER_RANGE = {{50, 150}, {60, 140}, {60, 150}};
    private static final String[] SLIDER_LABEL = {"面板缩放", "面板高度", "按键大小"};

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
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rrect = new RectF();

    // 颜色（从资源读，随暗色模式）
    private int colorKeyBg, colorKeyText, colorPressed, colorSpecial, colorAccentBg, colorAccentText, colorBg;

    // 自由调节参数（100=PREF 默认值对应 1.0f）
    private float panelScale = 1f;
    private float panelHeight = 1f;
    private float keyScale = 1f;

    private Listener listener;
    private int mode = MODE_CN;
    private int panel = PANEL_ALPHA;
    private int shiftState = 0;   // 0=off 1=once 2=lock

    // 调节页拖动状态
    private int draggingSlider = -1;
    private final RectF[] sliderTracks = {new RectF(), new RectF(), new RectF()};

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
        trackPaint.setStyle(Paint.Style.FILL);
        fillPaint.setStyle(Paint.Style.FILL);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT);
        altTextPaint.setTextAlign(Paint.Align.CENTER);
        altTextPaint.setTypeface(Typeface.DEFAULT);
        bubbleTextPaint.setTextAlign(Paint.Align.CENTER);
        bubbleTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        setLayerType(LAYER_TYPE_SOFTWARE, null);   // 阴影需要软件层
        refreshColors();
        buildPanel(PANEL_ALPHA);

        // 读持久化调节参数
        try {
            android.content.SharedPreferences sp =
                    getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            setAdjust(sp.getInt(PREF_SCALE, 100), sp.getInt(PREF_HEIGHT, 100), sp.getInt(PREF_KEY, 100));
        } catch (Throwable ignored) {
        }
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

    /** 从 prefs 应用三个调节参数（0.5~1.5 等） */
    public void setAdjust(int scalePct, int heightPct, int keyPct) {
        panelScale = clamp(scalePct / 100f, 0.5f, 1.5f);
        panelHeight = clamp(heightPct / 100f, 0.6f, 1.4f);
        keyScale = clamp(keyPct / 100f, 0.6f, 1.5f);
        requestLayout();
        invalidate();
    }

    public float getPanelScale() { return panelScale; }
    public float getPanelHeight() { return panelHeight; }
    public float getKeyScale() { return keyScale; }

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
        if (p < PANEL_ALPHA || p > PANEL_ADJUST) return;
        if (panel == p) return;
        panel = p;
        if (p != PANEL_ADJUST) buildPanel(p);
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
        // r4: 中/英 ⚙ ?123 ， 空格 。 ↵
        Key cn = special(mode == MODE_CN ? "中" : "英", CODE_MODE_CN, 1.1f);
        Key gear = special("⚙", CODE_MODE_SETTINGS, 1.0f);
        Key sym = special("?123", CODE_MODE_SYMBOL, 1.1f);
        Key comma = char1("，", ",", 1f);
        Key space = spaceKey(4.2f);
        Key period = char1("。", ".", 1f);
        Key enter = enterKey(1.6f);
        rows[3] = new Key[]{cn, gear, sym, comma, space, period, enter};
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
        for (Key[] row : rows) {
            for (Key k : row) {
                if (k.code == CODE_CHAR && k.label.length() == 1 && Character.isLetter(k.label.charAt(0))) {
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
        if (panel != PANEL_ADJUST) layoutKeys(width, height);
    }

    /** 键盘高度自适应：基础 = clamp(屏高比例)，再乘自由调节系数 */
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
        // 极端小屏兜底：不超过屏高一半（基础；自由调节可再放大）
        target = Math.min(target, Math.max(dpMin, (int) (hDp / 2)));
        target = (int) (target * panelScale * panelHeight);
        return (int) (target * dp);
    }

    private void layoutKeys(int width, int height) {
        float dp = getResources().getDisplayMetrics().density;
        float gap = 4 * dp * panelScale * (2.0f - keyScale);   // 按键大小越在越大，间隙越小
        float padX = 3 * dp * panelScale;
        float padTop = 4 * dp * panelScale;
        float keyH = (height - padTop - 4 * dp * panelScale - gap * (rows.length - 1)) / rows.length;

        for (int r = 0; r < rows.length; r++) {
            Key[] row = rows[r];
            float totalW = 0;
            for (Key k : row) totalW += k.weight;
            float avail = width - padX * 2 - gap * (row.length - 1);
            float x = padX;
            float y = padTop + r * (keyH + gap);
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
        if (panel == PANEL_ADJUST) {
            drawAdjustPanel(canvas);
            return;
        }
        float radius = 7 * dp * panelScale;

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
                    keyPaint.setShadowLayer(2 * dp * panelScale, 0, 1.5f * dp * panelScale, 0x22000000);
                    p = keyPaint;
                    textColor = colorKeyText;
                }
                canvas.drawRoundRect(rrect, radius, radius, p);

                float cx = k.x + k.w / 2;
                float cy = k.y + k.h / 2;
                textPaint.setColor(textColor);
                String label = k.label;
                if (k.code == CODE_CHAR && mode == MODE_EN && k.altLabel != null) {
                    label = k.altLabel;
                }
                if (k.code == CODE_CHAR && label.length() == 1 && Character.isLetter(label.charAt(0))) {
                    label = (mode == MODE_EN && shiftState > 0) ? label.toUpperCase() : label;
                    textPaint.setTextSize(22 * dp * panelScale * keyScale);
                } else if (k.code == CODE_SPACE) {
                    textPaint.setTextSize(14 * dp * panelScale * keyScale);
                } else {
                    textPaint.setTextSize(16 * dp * panelScale * keyScale);
                }
                float textY = cy - (textPaint.descent() + textPaint.ascent()) / 2;
                canvas.drawText(label, cx, textY, textPaint);

                if (k.code == CODE_CHAR && panel == PANEL_ALPHA
                        && "qwertyuiop".contains(k.label) && mode == MODE_CN) {
                    int idx = "qwertyuiop".indexOf(k.label);
                    String digit = idx == 9 ? "0" : String.valueOf(idx + 1);
                    altTextPaint.setColor(0x886B7280);
                    altTextPaint.setTextSize(9 * dp * panelScale * keyScale);
                    canvas.drawText(digit, k.x + k.w - 9 * dp * panelScale * keyScale, k.y + 13 * dp * panelScale, altTextPaint);
                }
            }
        }

        if (pressedKey != null && pressedKey.code == CODE_CHAR && panel == PANEL_ALPHA && !longPressFired) {
            float dp2 = getResources().getDisplayMetrics().density;
            float bw = Math.max(pressedKey.w, 44 * dp2 * panelScale);
            float bh = pressedKey.h * 1.35f;
            float bx = pressedKey.x + pressedKey.w / 2 - bw / 2;
            float by = pressedKey.y - bh + 6 * dp2 * panelScale;
            rrect.set(bx, by, bx + bw, by + bh);
            bubblePaint.setColor(colorAccentBg);
            canvas.drawRoundRect(rrect, 9 * dp2 * panelScale, 9 * dp2 * panelScale, bubblePaint);
            bubbleTextPaint.setColor(colorAccentText);
            bubbleTextPaint.setTextSize(26 * dp2 * panelScale * keyScale);
            String show = pressedKey.label;
            if (mode == MODE_EN && shiftState > 0) show = show.toUpperCase();
            float ty = (by + pressedKey.y + 6 * dp2 * panelScale) / 2 - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2;
            canvas.drawText(show, bx + bw / 2, ty, bubbleTextPaint);
        }
    }

    // ---------- 调节页绘制 ----------

    private void drawAdjustPanel(Canvas canvas) {
        float dp = getResources().getDisplayMetrics().density;
        int w = getWidth();
        int h = getHeight();
        float top = 8 * dp * panelScale;

        // 标题
        textPaint.setColor(colorKeyText);
        textPaint.setTextSize(15 * dp * panelScale * keyScale);
        float titleY = top + (textPaint.descent() + textPaint.ascent());
        canvas.drawText("键盘调节", w * 0.5f, top + 8 * dp * panelScale, textPaint);

        // 三行滑杆
        float labelW = w * 0.28f;
        float trackL = w * 0.34f;
        float trackR = w * 0.86f;
        float trackW = trackR - trackL;
        float thick = 6 * dp * panelScale;
        float rowTop = titleY + 10 * dp * panelScale;
        float rowH = (h - rowTop - 8 * dp * panelScale) / 3f;
        for (int i = 0; i < 3; i++) {
            float yc = rowTop + (i + 0.5f) * rowH;
            sliderTracks[i].set(trackL, yc - rowH * 0.32f, trackR, yc + rowH * 0.32f);   // 触摸区
            // 标签
            textPaint.setColor(colorKeyText);
            textPaint.setTextSize(13 * dp * panelScale * keyScale);
            canvas.drawText(SLIDER_LABEL[i], labelW * 0.5f, yc - 6 * dp * panelScale, textPaint);
            // 轨道
            int min = SLIDER_RANGE[i][0], max = SLIDER_RANGE[i][1];
            int cur = sliderCur(i);
            float fillX = trackL + trackW * (cur - min) / (float) (max - min);
            rrect.set(trackL, yc - thick / 2, trackR, yc + thick / 2);
            trackPaint.setColor(colorSpecial);
            canvas.drawRoundRect(rrect, thick / 2, thick / 2, trackPaint);
            // 已填充段
            if (fillX > trackL) {
                rrect.set(trackL, yc - thick / 2, fillX, yc + thick / 2);
                fillPaint.setColor(colorAccentBg);
                canvas.drawRoundRect(rrect, thick / 2, thick / 2, fillPaint);
            }
            // 手柄
            fillPaint.setColor(colorAccentText);
            canvas.drawCircle(fillX, yc, 7 * dp * panelScale, fillPaint);
            // 当前值
            textPaint.setColor(colorKeyText);
            textPaint.setTextSize(13 * dp * panelScale * keyScale);
            canvas.drawText(cur + "%", w * 0.93f, yc - 6 * dp * panelScale, textPaint);
            yc += rowH;
        }

        // 底部按钮：重置默认 / 完成
        float btnY = h - 34 * dp * panelScale;
        rrect.set(w * 0.10f, btnY, w * 0.44f, btnY + 26 * dp * panelScale);
        specialPaint.setColor(colorSpecial);
        canvas.drawRoundRect(rrect, 6 * dp * panelScale, 6 * dp * panelScale, specialPaint);
        textPaint.setColor(colorKeyText);
        textPaint.setTextSize(14 * dp * panelScale * keyScale);
        float ty = btnY + (26 * dp * panelScale - (textPaint.descent() + textPaint.ascent())) / 2;
        canvas.drawText("重置默认", w * 0.27f, ty, textPaint);
        rrect.set(w * 0.56f, btnY, w * 0.90f, btnY + 26 * dp * panelScale);
        keyPaint.setColor(colorAccentBg);
        canvas.drawRoundRect(rrect, 6 * dp * panelScale, 6 * dp * panelScale, keyPaint);
        textPaint.setColor(colorAccentText);
        canvas.drawText("完成", w * 0.73f, ty, textPaint);
    }

    private int sliderCur(int idx) {
        switch (idx) {
            case 0: return Math.round(panelScale * 100f);
            case 1: return Math.round(panelHeight * 100f);
            default: return Math.round(keyScale * 100f);
        }
    }

    private boolean hitReset(float x, float y) {
        float w = getWidth(), h = getHeight();
        float dp = getResources().getDisplayMetrics().density;
        float btnY = h - 34 * dp * panelScale;
        return x >= w * 0.10f && x <= w * 0.44f && y >= btnY && y <= btnY + 26 * dp * panelScale;
    }

    private boolean hitDone(float x, float y) {
        float w = getWidth(), h = getHeight();
        float dp = getResources().getDisplayMetrics().density;
        float btnY = h - 34 * dp * panelScale;
        return x >= w * 0.56f && x <= w * 0.90f && y >= btnY && y <= btnY + 26 * dp * panelScale;
    }

    private void setSliderFromX(int idx, float x) {
        RectF t = sliderTracks[idx];
        if (t.width() <= 0) return;
        float t0 = clamp((x - t.left) / (t.right - t.left), 0f, 1f);
        int min = SLIDER_RANGE[idx][0], max = SLIDER_RANGE[idx][1];
        int v = Math.round(min + t0 * (max - min));
        int scalePct = idx == 0 ? v : Math.round(panelScale * 100f);
        int heightPct = idx == 1 ? v : Math.round(panelHeight * 100f);
        int keyPct = idx == 2 ? v : Math.round(keyScale * 100f);
        persistAndApply(scalePct, heightPct, keyPct);
    }

    private void resetAdjustToDefaults() {
        int s = 100, h2 = 100, k = 100;
        panelScale = keyScale = panelHeight = 1f;
        writePrefs(s, h2, k);
        setAdjust(s, h2, k);
        invalidate();
    }

    private void writePrefs(int scalePct, int heightPct, int keyPct) {
        try {
            getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_SCALE, scalePct)
                    .putInt(PREF_HEIGHT, heightPct)
                    .putInt(PREF_KEY, keyPct)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void persistAndApply(int scalePct, int heightPct, int keyPct) {
        int s = scalePct < 0 ? Math.round(panelScale * 100f) : scalePct;
        int h2 = heightPct < 0 ? Math.round(panelHeight * 100f) : heightPct;
        int k = keyPct < 0 ? Math.round(keyScale * 100f) : keyPct;
        writePrefs(s, h2, k);
        setAdjust(s, h2, k);
        invalidate();
    }

    // ---------- 触摸 ----------

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (panel == PANEL_ADJUST) return onAdjustTouch(ev);
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
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int idx = ev.findPointerIndex(activePointer);
                if (idx >= 0 && pressedKey != null) {
                    Key k = keyAt(ev.getX(idx), ev.getY(idx));
                    if (k != pressedKey) {
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

    /** 调节页触摸：拖动滑杆 / 点击按钮，不触发按键 */
    private boolean onAdjustTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = ev.getX(ev.getActionIndex());
                float y = ev.getY(ev.getActionIndex());
                if (hitDone(x, y)) {
                    panel = PANEL_ALPHA;
                    buildPanel(PANEL_ALPHA);
                    invalidate();
                    if (listener != null) listener.onPanelChanged(PANEL_ALPHA);
                } else if (hitReset(x, y)) {
                    resetAdjustToDefaults();
                } else {
                    draggingSlider = sliderAt(x, y);
                    if (draggingSlider >= 0) setSliderFromX(draggingSlider, x);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (draggingSlider >= 0) {
                    setSliderFromX(draggingSlider, ev.getX());
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP: {
                draggingSlider = -1;
                activePointer = -1;
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    /** 调节页：命中某个滑杆触摸区则返回索引，否则 -1 */
    private int sliderAt(float x, float y) {
        for (int i = 0; i < 3; i++) {
            if (x >= sliderTracks[i].left && x <= sliderTracks[i].right
                    && y >= sliderTracks[i].top && y <= sliderTracks[i].bottom) return i;
        }
        return -1;
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
            int idx = "qwertyuiop".indexOf(pressedKey.label);
            String digit = idx == 9 ? "0" : String.valueOf(idx + 1);
            longPressFired = true;
            if (listener != null) listener.onKey(digit, CODE_CHAR);
            haptic();
            pressedKey = null;
            invalidate();
        } else if (pressedKey.code == CODE_SHIFT) {
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
            case CODE_MODE_SETTINGS:
                panel = PANEL_ADJUST;
                invalidate();
                listener.onPanelChanged(PANEL_ADJUST);
                break;
            default:
                String out = k.label;
                if (mode == MODE_EN && k.altLabel != null) out = k.altLabel;
                listener.onKey(out, k.code);
        }
    }

    private void haptic() {
        if (!hapticEnabled) return;
        try {
            int feedback = android.os.Build.VERSION.SDK_INT >= 27
                    ? HapticFeedbackConstants.KEYBOARD_TAP : HapticFeedbackConstants.VIRTUAL_KEY;
            performHapticFeedback(feedback);
        } catch (Throwable ignored) {
            // 系统禁用触觉反馈或低端机型无马达时静默，不影响输入
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

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}