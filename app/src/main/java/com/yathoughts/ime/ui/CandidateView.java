package com.yathoughts.ime.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.yathoughts.ime.R;

/**
 * 候选栏：左侧拼音区 + 可横向滚动候选列表。
 */
public final class CandidateView extends LinearLayout {

    public interface Listener {
        void onCandidatePicked(int index);
    }

    private TextView pinyinView;
    private HorizontalScrollView scroll;
    private LinearLayout row;
    private Listener listener;

    private int colorText, colorPinyin, colorBg, colorDivider;

    public CandidateView(Context context) {
        super(context);
        init();
    }

    public CandidateView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        refreshColors();

        float dp = getResources().getDisplayMetrics().density;

        pinyinView = new TextView(getContext());
        pinyinView.setTextSize(13);
        pinyinView.setSingleLine(true);
        pinyinView.setTextColor(colorPinyin);
        LayoutParams plp = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        plp.leftMargin = (int) (12 * dp);
        pinyinView.setGravity(Gravity.CENTER_VERTICAL);
        addView(pinyinView, plp);

        scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LayoutParams slp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        addView(scroll, slp);

        row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(row);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void refreshColors() {
        Resources r = getResources();
        colorText = r.getColor(R.color.kb_candidate_text);
        colorPinyin = r.getColor(R.color.kb_pinyin_text);
        colorBg = r.getColor(R.color.kb_candidate_bg);
        colorDivider = r.getColor(R.color.kb_divider);
        setBackgroundColor(colorBg);
        if (pinyinView != null) pinyinView.setTextColor(colorPinyin);
        invalidate();
    }

    /** 显示拼音 + 候选 */
    public void setCandidates(String pinyin, java.util.List<String> candidates) {
        float dp = getResources().getDisplayMetrics().density;
        pinyinView.setText(pinyin == null ? "" : pinyin);
        row.removeAllViews();
        if (candidates == null) return;
        int n = candidates.size();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            TextView tv = new TextView(getContext());
            String text = candidates.get(i);
            tv.setText(text);
            tv.setTextSize(text.length() <= 2 ? 19 : text.length() <= 4 ? 17 : 15);
            tv.setTextColor(colorText);
            tv.setSingleLine(true);
            tv.setPadding((int) (11 * dp), 0, (int) (11 * dp), 0);
            tv.setGravity(Gravity.CENTER);
            tv.setOnClickListener(v -> {
                if (listener != null) listener.onCandidatePicked(idx);
            });
            row.addView(tv, new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

            if (i < n - 1) {
                View div = new View(getContext());
                div.setBackgroundColor(colorDivider);
                row.addView(div, new LinearLayout.LayoutParams((int) (1 * dp), (int) (18 * dp)));
            }
        }
        scroll.setScrollX(0);
    }
}
