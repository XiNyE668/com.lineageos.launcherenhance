package com.lineageos.launcherenhance;

import android.net.Uri;

final class ConfigKeys {
    static final String PREFS = "config";
    static final String AUTHORITY = "com.lineageos.launcherenhance.config";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    static final String METHOD_GET_CONFIG = "getConfig";
    static final String METHOD_REPORT_STATUS = "reportStatus";
    static final String HIDE_WORKSPACE_LABELS = "hide_workspace_labels";
    static final String HIDE_DRAWER_LABELS = "hide_drawer_labels";
    static final String WORKSPACE_ICON_SCALE = "workspace_icon_scale";
    static final String DRAWER_ICON_SCALE = "drawer_icon_scale";
    static final String WORKSPACE_LABEL_SCALE = "workspace_label_scale";
    static final String DRAWER_LABEL_SCALE = "drawer_label_scale";
    static final String LABEL_GAP_DELTA_DP = "label_gap_delta_dp";
    static final String HIDE_QSB = "hide_qsb";
    static final String CENTER_FOLDER = "center_folder";
    static final String ALLOW_WALLPAPER_SCROLLING = "allow_wallpaper_scrolling";
    static final String THREE_FINGER_SCREENSHOT = "three_finger_screenshot";
    static final String HIDE_DOTS = "hide_dots";
    static final String SHOW_BADGE_COUNT = "show_badge_count";
    static final String DOT_COLOR = "dot_color";
    static final String PRESS_SCALE_ENABLED = "press_scale_enabled";
    static final String PRESS_SCALE_PERCENT = "press_scale_percent";
    static final String RECENTS_MEMINFO = "recents_meminfo";
    static final String RECENTS_MEMINFO_ZRAM = "recents_meminfo_zram";
    static final String RECENTS_CLEAR_ALL_SIDE = "recents_clear_all_side";
    static final int CLEAR_ALL_SIDE_LEFT = 0;
    static final int CLEAR_ALL_SIDE_RIGHT = 1;
    static final String ICON_PACK = "icon_pack";
    static final String HIDDEN_PACKAGES = "hidden_packages";
    static final String RENAMED_PACKAGES = "renamed_packages";
    static final String STATUS = "hook_status";
    static final String STATUS_TIME = "hook_status_time";
    static final int DEFAULT_SCALE = 100;
    static final int DEFAULT_PRESS_SCALE = 92;
    private ConfigKeys() {}
}
