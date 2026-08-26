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

        root.addView(text("Back Arrow & Brightness", 24, true));
        root.addView(text(
                "LineageOS 23.2 / Android 16\nLSPosed scopes: System UI + System Framework",
                14, false));

        section(root, "Back Arrow");
        addSwitch(root, "Show Back gesture arrow", "show_back_arrow", false);
        root.addView(text(
                "Default: OFF\n\nOFF → when BackPanelController enters ENTRY, the arrow/capsule stays hidden.\nON → no visibility override is applied; LineageOS uses its completely stock BackPanel behavior.\n\nThis does not alter the Back gesture, Predictive Back, Activity/Task transitions, or launcher animations.",
                13, false));

        section(root, "Display");
        addSwitch(root, "Minimum auto-brightness", "brightness_floor_enabled", true);
        addSeek(root, "Minimum brightness", "brightness_floor", 1, 30, 10, "%");
        addSwitch(root, "Refresh auto brightness on wake", "wake_refresh", true);
        root.addView(text(
                "The minimum is enforced only while automatic brightness is active and the display is ON. Doze/AOD are excluded. When the display turns on, the ambient-light sensor is re-armed once so automatic brightness can recover from a stale very-low value.",
                13, false));

        section(root, "Safety");
        root.addView(text(
                "No Cross-Activity/Cross-Task/Return-to-Home animation hooks. No Trebuchet scope. No process kill or APatch/system-file modification. Missing LineageOS methods are skipped.",
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
