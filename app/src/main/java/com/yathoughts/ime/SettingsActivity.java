package com.yathoughts.ime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.yathoughts.ime.engine.UserDictHolder;

/**
 * 设置页（纯框架 Activity，无 AndroidX）：
 *  - 启用状态检测 + 跳转系统设置
 *  - 偏好开关（模糊音/震动/按键音）
 *  - 清空用户词
 *  - 关于（无广告声明）
 */
public final class SettingsActivity extends Activity {

    private static final String PREFS = "ime_prefs";

    private TextView enableStatus, defaultStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // 标题
        root.addView(title("启用清简输入法", 22, true), matchWrap());

        enableStatus = statusLine();
        root.addView(enableStatus, matchWrap());
        defaultStatus = statusLine();
        root.addView(defaultStatus, matchWrap());

        Button enableBtn = new Button(this);
        enableBtn.setText(R.string.btn_enable_ime);
        enableBtn.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(enableBtn, matchWrap());

        Button switchBtn = new Button(this);
        switchBtn.setText(R.string.btn_switch_ime);
        switchBtn.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        root.addView(switchBtn, matchWrap());

        root.addView(space((int) (24 * dp)));

        // 偏好
        root.addView(title(getString(R.string.pref_title), 18, true), matchWrap());

        Switch fuzzy = switchRow(getString(R.string.pref_fuzzy), "fuzzy");
        root.addView(fuzzy, matchWrap());

        Switch vibrate = switchRow(getString(R.string.pref_vibrate), "vibrate");
        root.addView(vibrate, matchWrap());

        Switch sound = switchRow("按键音", "sound");
        root.addView(sound, matchWrap());

        Button clearBtn = new Button(this);
        clearBtn.setText(R.string.pref_clear_user);
        clearBtn.setOnClickListener(v -> {
            UserDictHolder.get().clearUserDict(getApplicationContext());
            Toast.makeText(this, R.string.pref_clear_user_done, Toast.LENGTH_SHORT).show();
        });
        root.addView(clearBtn, matchWrap());

        root.addView(space((int) (24 * dp)));

        // 关于
        root.addView(title(getString(R.string.about_title), 18, true), matchWrap());
        TextView about = new TextView(this);
        about.setText(R.string.about_text);
        about.setTextSize(14);
        about.setLineSpacing(4 * dp, 1f);
        root.addView(about, matchWrap());

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        String imeId = getPackageName() + "/.ImeService";
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS);
        String def = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean isEnabled = enabled != null && enabled.contains(imeId);
        boolean isDefault = imeId.equals(def);
        enableStatus.setText(getString(isEnabled ? R.string.status_enabled : R.string.status_not_enabled));
        enableStatus.setTextColor(isEnabled ? 0xFF16A34A : 0xFFDC2626);
        defaultStatus.setText(getString(isDefault ? R.string.status_default : R.string.status_not_default));
        defaultStatus.setTextColor(isDefault ? 0xFF16A34A : 0xFFDC2626);
    }

    // ---------- UI 辅助 ----------

    private TextView title(String text, float sizeSp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        return tv;
    }

    private TextView statusLine() {
        TextView tv = new TextView(this);
        tv.setTextSize(15);
        int pad = (int) (4 * getResources().getDisplayMetrics().density);
        tv.setPadding(0, pad, 0, pad);
        return tv;
    }

    private Switch switchRow(String label, final String key) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(15);
        sw.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, key.equals("vibrate")));
        sw.setOnCheckedChangeListener((btn, checked) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, checked).apply());
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        sw.setPadding(0, pad, 0, pad);
        return sw;
    }

    private View space(int h) {
        View v = new View(this);
        v.setMinimumHeight(h);
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
