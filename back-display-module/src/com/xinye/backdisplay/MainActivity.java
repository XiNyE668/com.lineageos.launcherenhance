package com.xinye.backdisplay;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = SettingsProvider.prefs(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Back & Display Tweaks", 24, true);
        root.addView(title);
        root.addView(text("LineageOS 23.2 / Android 16\nLSPosed scopes: System Framework + System UI + Trebuchet", 14, false));

        section(root, "Back Animation");
        addSwitch(root, "Enable predictive Back tuning", "back_enabled", true);
        addSeek(root, "Cross-activity end scale", "activity_scale", 84, 96, 90, "%");
        addSeek(root, "Cross-task end scale", "task_scale", 74, 94, 84, "%");
        addSeek(root, "Return-to-home end scale", "home_scale", 70, 94, 82, "%");
        addSeek(root, "Commit animation duration", "back_duration", 250, 650, 420, " ms");
        addSeek(root, "SystemUI Back indicator scale", "edge_scale", 80, 120, 100, "%");

        section(root, "Display");
        addSwitch(root, "Minimum auto-brightness floor", "brightness_enabled", true);
        addSeek(root, "Minimum brightness", "brightness_floor", 1, 30, 10, "%");
        addSwitch(root, "Refresh ambient light on screen-on", "wake_refresh", true);
        root.addView(text(
                "The brightness floor is applied only while auto brightness is active and the display is ON. " +
                "Doze/AOD are excluded. On wake, the ambient-light sensor state is re-armed once; until valid lux arrives, the floor is used as the temporary fallback.",
                13, false));

        section(root, "Safety");
        root.addView(text(
                "All hooks are fail-soft: missing LineageOS methods are logged and skipped. The module does not kill SystemUI, Trebuchet, or system_server and does not require APatch files.",
                13, false));

        setContentView(scroll);
    }

    private void section(LinearLayout root, String s) {
        TextView v = text(s, 19, true);
        v.setPadding(0, dp(22), 0, dp(6));
        root.addView(v);
    }

    private void addSwitch(LinearLayout root, String label, String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(16);
        sw.setPadding(0, dp(6), 0, dp(6));
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                prefs.edit().putBoolean(key, checked).apply());
        root.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addSeek(LinearLayout root, String label, String key,
                         int min, int max, int def, String suffix) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(5), 0, dp(7));
        TextView value = text("", 15, false);
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        int current = prefs.getInt(key, def);
        if (current < min) current = min;
        if (current > max) current = max;
        seek.setProgress(current - min);
        value.setText(label + ": " + current + suffix);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int actual = min + progress;
                value.setText(label + ": " + actual + suffix);
                if (fromUser) prefs.edit().putInt(key, actual).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(value);
        box.addView(seek);
        root.addView(box);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
