package com.lineageos.launcherenhance;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final String RENAME_SEPARATOR = "\t";

    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView statusView;
    private Button hiddenAppsButton;
    private Button renameAppsButton;
    private Button iconPackButton;

    private Set<String> hiddenPackages;
    private Map<String, String> renamedPackages;

    private final int[] dotColors = new int[]{
            0,
            Color.rgb(244, 67, 54),
            Color.rgb(255, 152, 0),
            Color.rgb(33, 150, 243),
            Color.rgb(76, 175, 80),
            Color.rgb(156, 39, 176)
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(ConfigKeys.PREFS, MODE_PRIVATE);
        hiddenPackages = new LinkedHashSet<>(prefs.getStringSet(
                ConfigKeys.HIDDEN_PACKAGES, Collections.emptySet()));
        renamedPackages = decodeRenames(prefs.getStringSet(
                ConfigKeys.RENAMED_PACKAGES, Collections.emptySet()));

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, dp(14), p, dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("Launcher Hub");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Android 16 / LineageOS 23.2 / Trebuchet\n"
                + "LSPosed module · 设置变更后自动重载 Trebuchet");
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(6), 0, dp(12));
        root.addView(subtitle);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(statusView, fullWidth());
        refreshStatus();

        addSection("图标与文字");
        addSwitch("隐藏桌面图标标题", ConfigKeys.HIDE_WORKSPACE_LABELS, false);
        addSwitch("隐藏应用抽屉图标标题", ConfigKeys.HIDE_DRAWER_LABELS, false);
        addSeek("桌面/文件夹图标大小", ConfigKeys.WORKSPACE_ICON_SCALE,
                70, 140, ConfigKeys.DEFAULT_SCALE, "%");
        addSeek("应用抽屉图标大小", ConfigKeys.DRAWER_ICON_SCALE,
                70, 140, ConfigKeys.DEFAULT_SCALE, "%");
        addSeek("桌面/文件夹标题大小", ConfigKeys.WORKSPACE_LABEL_SCALE,
                70, 140, ConfigKeys.DEFAULT_SCALE, "%");
        addSeek("应用抽屉标题大小", ConfigKeys.DRAWER_LABEL_SCALE,
                70, 140, ConfigKeys.DEFAULT_SCALE, "%");
        addOffsetSeek("图标与标题间距", ConfigKeys.LABEL_GAP_DELTA_DP, -8, 16, 0);

        addSection("图标包与应用");
        iconPackButton = addButton("");
        iconPackButton.setOnClickListener(v -> showIconPackDialog());
        updateIconPackButton();

        renameAppsButton = addButton("");
        renameAppsButton.setOnClickListener(v -> showRenamePackageDialog());
        updateRenameButton();

        hiddenAppsButton = addButton("");
        hiddenAppsButton.setOnClickListener(v -> showHiddenAppsDialog());
        updateHiddenAppsButton();

        addSection("桌面与文件夹");
        addSwitch("隐藏首屏搜索框 / At A Glance", ConfigKeys.HIDE_QSB, false);
        addSwitch("打开文件夹时强制屏幕居中", ConfigKeys.CENTER_FOLDER, false);

        addSection("通知角标");
        addSwitch("隐藏通知圆点", ConfigKeys.HIDE_DOTS, false);
        addSwitch("用数字角标替代通知圆点", ConfigKeys.SHOW_BADGE_COUNT, false);
        addDotColorSpinner();

        addSection("交互效果");
        addSwitch("启用自定义图标按压缩放", ConfigKeys.PRESS_SCALE_ENABLED, false);
        addSeek("按下时图标缩放", ConfigKeys.PRESS_SCALE_PERCENT,
                80, 100, ConfigKeys.DEFAULT_PRESS_SCALE, "%");

        addSection("最近任务");
        addSwitch("在最近任务底部显示内存信息", ConfigKeys.RECENTS_MEMINFO, false);
        addSwitch("内存信息同时显示 ZRAM", ConfigKeys.RECENTS_MEMINFO_ZRAM, false);
        addClearAllSideSpinner();

        addSection("应用配置");
        TextView liveApply = new TextView(this);
        liveApply.setText("设置会立即保存。Trebuchet 由 LSPosed 注入代码自行重载，不再请求 Root 权限。");
        liveApply.setTextSize(13);
        liveApply.setPadding(0, dp(4), 0, dp(8));
        root.addView(liveApply, fullWidth());

        Button refresh = addButton("刷新 LSPosed Hook 状态");
        refresh.setOnClickListener(v -> refreshStatus());

        Button reset = addButton("恢复全部默认设置");
        reset.setOnClickListener(v -> confirmReset());

        TextView footer = new TextView(this);
        footer.setText("原项目 Launcher3Customizer © 2023 gitofleonardo (MIT)\n"
                + "Android 16 rewrite: XiNyE & ChatGPT");
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, dp(24), 0, 0);
        root.addView(footer);
    }

    private void addSection(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(18);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(20), 0, dp(6));
        root.addView(v);
    }

    private Switch addSwitch(String title, String key, boolean defValue) {
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextSize(16);
        sw.setChecked(prefs.getBoolean(key, defValue));
        sw.setPadding(0, dp(6), 0, dp(6));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).apply();
            notifyConfigChanged();
        });
        root.addView(sw, fullWidth());
        return sw;
    }

    private void addSeek(String title, String key, int min, int max, int defValue, String suffix) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(7), 0, dp(4));

        TextView label = new TextView(this);
        label.setTextSize(15);
        int current = prefs.getInt(key, defValue);
        label.setText(title + "：" + current + suffix);
        box.addView(label);

        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, current - min)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int value = current;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value = min + progress;
                label.setText(title + "：" + value + suffix);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(key, value).apply();
                notifyConfigChanged();
            }
        });
        box.addView(seek, fullWidth());
        root.addView(box, fullWidth());
    }

    private void addOffsetSeek(String title, String key, int min, int max, int defValue) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(7), 0, dp(4));
        TextView label = new TextView(this);
        label.setTextSize(15);
        int current = prefs.getInt(key, defValue);
        label.setText(title + "：" + formatSignedDp(current));
        box.addView(label);

        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(current - min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int value = current;
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value = min + progress;
                label.setText(title + "：" + formatSignedDp(value));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(key, value).apply();
                notifyConfigChanged();
            }
        });
        box.addView(seek, fullWidth());
        root.addView(box, fullWidth());
    }

    private String formatSignedDp(int value) {
        if (value > 0) return "+" + value + " dp";
        return value + " dp";
    }

    private Button addButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = fullWidth();
        lp.topMargin = dp(5);
        root.addView(b, lp);
        return b;
    }

    private void addDotColorSpinner() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView title = new TextView(this);
        title.setText("通知圆点 / 数字角标颜色");
        title.setTextSize(15);
        row.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        String[] names = {"系统", "红", "橙", "蓝", "绿", "紫"};
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names);
        spinner.setAdapter(adapter);
        int saved = prefs.getInt(ConfigKeys.DOT_COLOR, 0);
        int selected = 0;
        for (int i = 0; i < dotColors.length; i++) {
            if (dotColors[i] == saved) selected = i;
        }
        spinner.setSelection(selected, false);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                prefs.edit().putInt(ConfigKeys.DOT_COLOR, dotColors[position]).apply();
                notifyConfigChanged();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        row.addView(spinner, new LinearLayout.LayoutParams(dp(105),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(row, fullWidth());
    }

    private void addClearAllSideSpinner() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView title = new TextView(this);
        title.setText("Clear all 位置");
        title.setTextSize(15);
        row.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        String[] names = {"Screenshot 左边", "Screenshot 右边"};
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names);
        spinner.setAdapter(adapter);
        int saved = prefs.getInt(ConfigKeys.RECENTS_CLEAR_ALL_SIDE,
                ConfigKeys.CLEAR_ALL_SIDE_RIGHT);
        spinner.setSelection(saved == ConfigKeys.CLEAR_ALL_SIDE_LEFT ? 0 : 1, false);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                int value = position == 0
                        ? ConfigKeys.CLEAR_ALL_SIDE_LEFT : ConfigKeys.CLEAR_ALL_SIDE_RIGHT;
                prefs.edit().putInt(ConfigKeys.RECENTS_CLEAR_ALL_SIDE, value).apply();
                notifyConfigChanged();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        row.addView(spinner, new LinearLayout.LayoutParams(dp(170),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(row, fullWidth());
    }

    private void showHiddenAppsDialog() {
        List<AppEntry> apps = loadLauncherApps();
        if (apps.isEmpty()) {
            Toast.makeText(this, "没有找到可隐藏的 Launcher 应用", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppEntry app = apps.get(i);
            names[i] = app.label + "\n" + app.packageName;
            checked[i] = hiddenPackages.contains(app.packageName);
        }
        new AlertDialog.Builder(this)
                .setTitle("隐藏应用（仅当前主用户的应用抽屉）")
                .setMultiChoiceItems(names, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("保存", (dialog, which) -> {
                    hiddenPackages.clear();
                    for (int i = 0; i < apps.size(); i++) {
                        if (checked[i]) hiddenPackages.add(apps.get(i).packageName);
                    }
                    prefs.edit().putStringSet(ConfigKeys.HIDDEN_PACKAGES,
                            new HashSet<>(hiddenPackages)).apply();
                    notifyConfigChanged();
                    updateHiddenAppsButton();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenamePackageDialog() {
        List<AppEntry> apps = loadLauncherApps();
        if (apps.isEmpty()) return;
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppEntry app = apps.get(i);
            String custom = renamedPackages.get(app.packageName);
            names[i] = app.label + (custom == null ? "" : "  →  " + custom)
                    + "\n" + app.packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要重命名的应用")
                .setItems(names, (dialog, which) -> showRenameEditor(apps.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenameEditor(AppEntry app) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(app.label);
        String current = renamedPackages.get(app.packageName);
        if (current != null) {
            input.setText(current);
            input.setSelection(current.length());
        }
        int pad = dp(18);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, 0, pad, 0);
        box.addView(input, fullWidth());

        new AlertDialog.Builder(this)
                .setTitle(app.label + "\n" + app.packageName)
                .setView(box)
                .setMessage("留空并保存即可恢复原始名称。")
                .setPositiveButton("保存", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) renamedPackages.remove(app.packageName);
                    else renamedPackages.put(app.packageName, value);
                    prefs.edit().putStringSet(ConfigKeys.RENAMED_PACKAGES,
                            encodeRenames(renamedPackages)).apply();
                    notifyConfigChanged();
                    updateRenameButton();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showIconPackDialog() {
        List<AppEntry> packs = loadIconPacks();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> packages = new ArrayList<>();
        labels.add("系统默认图标");
        packages.add("");
        for (AppEntry pack : packs) {
            labels.add(pack.label);
            packages.add(pack.packageName);
        }
        String current = prefs.getString(ConfigKeys.ICON_PACK, "");
        int checked = packages.indexOf(current);
        if (checked < 0) checked = 0;

        new AlertDialog.Builder(this)
                .setTitle("选择标准 Launcher 图标包")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    prefs.edit().putString(ConfigKeys.ICON_PACK, packages.get(which)).apply();
                    notifyConfigChanged();
                    updateIconPackButton();
                    dialog.dismiss();
                })
                .setNeutralButton("手动输入包名", (dialog, which) -> showManualIconPackDialog())
                .setNegativeButton("取消", null)
                .show();
    }

    private void showManualIconPackDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("例如 com.example.iconpack");
        input.setText(prefs.getString(ConfigKeys.ICON_PACK, ""));
        int pad = dp(18);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, 0, pad, 0);
        box.addView(input, fullWidth());
        new AlertDialog.Builder(this)
                .setTitle("图标包 package name")
                .setView(box)
                .setPositiveButton("保存", (dialog, which) -> {
                    prefs.edit().putString(ConfigKeys.ICON_PACK,
                            input.getText().toString().trim()).apply();
                    notifyConfigChanged();
                    updateIconPackButton();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private List<AppEntry> loadLauncherApps() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
        Map<String, AppEntry> byPackage = new LinkedHashMap<>();
        for (ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (getPackageName().equals(pkg)) continue;
            CharSequence cs = ri.loadLabel(pm);
            byPackage.putIfAbsent(pkg, new AppEntry(pkg, cs == null ? pkg : cs.toString()));
        }
        ArrayList<AppEntry> out = new ArrayList<>(byPackage.values());
        out.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.getDefault())));
        return out;
    }

    private List<AppEntry> loadIconPacks() {
        PackageManager pm = getPackageManager();
        String[] actions = {
                "org.adw.launcher.THEMES",
                "com.gau.go.launcherex.theme",
                "com.novalauncher.THEME",
                "com.teslacoilsw.launcher.THEME",
                "com.anddoes.launcher.THEME"
        };
        Map<String, AppEntry> found = new LinkedHashMap<>();
        for (String action : actions) {
            Intent i = new Intent(action);
            for (ResolveInfo ri : pm.queryIntentActivities(i, PackageManager.MATCH_ALL)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                CharSequence cs = ri.loadLabel(pm);
                found.putIfAbsent(pkg, new AppEntry(pkg, cs == null ? pkg : cs.toString()));
            }
        }
        String current = prefs.getString(ConfigKeys.ICON_PACK, "");
        if (!current.isEmpty() && !found.containsKey(current)) {
            try {
                CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(current, 0));
                found.put(current, new AppEntry(current, label.toString()));
            } catch (Throwable ignored) {}
        }
        ArrayList<AppEntry> out = new ArrayList<>(found.values());
        out.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.getDefault())));
        return out;
    }

    private void updateHiddenAppsButton() {
        hiddenAppsButton.setText("隐藏应用（" + hiddenPackages.size() + "）");
    }

    private void updateRenameButton() {
        renameAppsButton.setText("自定义应用名称（" + renamedPackages.size() + "）");
    }

    private void updateIconPackButton() {
        String pkg = prefs.getString(ConfigKeys.ICON_PACK, "");
        if (pkg.isEmpty()) {
            iconPackButton.setText("图标包：系统默认");
            return;
        }
        String label = selectedIconPackLabel(pkg);
        iconPackButton.setText(label == null ? "图标包：已选择" : "图标包：" + label);
    }

    private String selectedIconPackLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable ignored) {}
        for (AppEntry entry : loadIconPacks()) {
            if (pkg.equals(entry.packageName)) return entry.label;
        }
        return null;
    }

    private void refreshStatus() {
        String status = prefs.getString(ConfigKeys.STATUS, "尚未收到 Trebuchet Hook 状态");
        long time = prefs.getLong(ConfigKeys.STATUS_TIME, 0L);
        String when = time == 0L ? "-" : DateFormat.getDateTimeInstance().format(time);
        statusView.setText("LSPosed Hook 状态\n" + status + "\n最近报告：" + when);
    }

    private void notifyConfigChanged() {
        try {
            getContentResolver().notifyChange(ConfigKeys.URI, null);
        } catch (Throwable ignored) {}
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("恢复默认设置")
                .setMessage("这会清除图标包、隐藏应用、自定义名称及所有视觉参数。")
                .setPositiveButton("恢复", (dialog, which) -> {
                    prefs.edit().clear().apply();
                    notifyConfigChanged();
                    Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private Map<String, String> decodeRenames(Set<String> encoded) {
        Map<String, String> out = new HashMap<>();
        for (String item : encoded) {
            int split = item.indexOf(RENAME_SEPARATOR);
            if (split <= 0 || split >= item.length() - 1) continue;
            out.put(item.substring(0, split), item.substring(split + 1));
        }
        return out;
    }

    private Set<String> encodeRenames(Map<String, String> renames) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, String> e : renames.entrySet()) {
            out.add(e.getKey() + RENAME_SEPARATOR + e.getValue());
        }
        return out;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        AppEntry(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }
}
