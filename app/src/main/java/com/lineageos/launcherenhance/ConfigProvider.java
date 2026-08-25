package com.lineageos.launcherenhance;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private SharedPreferences prefs() { return getContext().getSharedPreferences(ConfigKeys.PREFS, 0); }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (ConfigKeys.METHOD_GET_CONFIG.equals(method)) {
            SharedPreferences p = prefs();
            Bundle b = new Bundle();
            b.putBoolean(ConfigKeys.HIDE_WORKSPACE_LABELS, p.getBoolean(ConfigKeys.HIDE_WORKSPACE_LABELS, false));
            b.putBoolean(ConfigKeys.HIDE_DRAWER_LABELS, p.getBoolean(ConfigKeys.HIDE_DRAWER_LABELS, false));
            b.putInt(ConfigKeys.WORKSPACE_ICON_SCALE, p.getInt(ConfigKeys.WORKSPACE_ICON_SCALE, 100));
            b.putInt(ConfigKeys.DRAWER_ICON_SCALE, p.getInt(ConfigKeys.DRAWER_ICON_SCALE, 100));
            b.putInt(ConfigKeys.WORKSPACE_LABEL_SCALE, p.getInt(ConfigKeys.WORKSPACE_LABEL_SCALE, 100));
            b.putInt(ConfigKeys.DRAWER_LABEL_SCALE, p.getInt(ConfigKeys.DRAWER_LABEL_SCALE, 100));
            b.putInt(ConfigKeys.LABEL_GAP_DELTA_DP, p.getInt(ConfigKeys.LABEL_GAP_DELTA_DP, 0));
            b.putBoolean(ConfigKeys.HIDE_QSB, p.getBoolean(ConfigKeys.HIDE_QSB, false));
            b.putBoolean(ConfigKeys.CENTER_FOLDER, p.getBoolean(ConfigKeys.CENTER_FOLDER, false));
            b.putBoolean(ConfigKeys.HIDE_DOTS, p.getBoolean(ConfigKeys.HIDE_DOTS, false));
            b.putBoolean(ConfigKeys.SHOW_BADGE_COUNT, p.getBoolean(ConfigKeys.SHOW_BADGE_COUNT, false));
            b.putInt(ConfigKeys.DOT_COLOR, p.getInt(ConfigKeys.DOT_COLOR, 0));
            b.putBoolean(ConfigKeys.PRESS_SCALE_ENABLED, p.getBoolean(ConfigKeys.PRESS_SCALE_ENABLED, false));
            b.putInt(ConfigKeys.PRESS_SCALE_PERCENT, p.getInt(ConfigKeys.PRESS_SCALE_PERCENT, 92));
            b.putBoolean(ConfigKeys.RECENTS_MEMINFO, p.getBoolean(ConfigKeys.RECENTS_MEMINFO, false));
            b.putBoolean(ConfigKeys.RECENTS_MEMINFO_ZRAM, p.getBoolean(ConfigKeys.RECENTS_MEMINFO_ZRAM, false));
            b.putBoolean(ConfigKeys.RECENTS_CLEAR_ALL_INLINE,
                    p.getBoolean(ConfigKeys.RECENTS_CLEAR_ALL_INLINE, false));
            b.putInt(ConfigKeys.RECENTS_CLEAR_ALL_SIDE,
                    p.getInt(ConfigKeys.RECENTS_CLEAR_ALL_SIDE, ConfigKeys.CLEAR_ALL_SIDE_RIGHT));
            b.putString(ConfigKeys.ICON_PACK, p.getString(ConfigKeys.ICON_PACK, ""));
            Set<String> hidden = p.getStringSet(ConfigKeys.HIDDEN_PACKAGES, Collections.emptySet());
            Set<String> renamed = p.getStringSet(ConfigKeys.RENAMED_PACKAGES, Collections.emptySet());
            b.putStringArrayList(ConfigKeys.HIDDEN_PACKAGES, new ArrayList<>(new HashSet<>(hidden)));
            b.putStringArrayList(ConfigKeys.RENAMED_PACKAGES, new ArrayList<>(new HashSet<>(renamed)));
            return b;
        }
        if (ConfigKeys.METHOD_REPORT_STATUS.equals(method)) {
            String status = extras == null ? null : extras.getString(ConfigKeys.STATUS);
            if (status != null) prefs().edit().putString(ConfigKeys.STATUS, status).putLong(ConfigKeys.STATUS_TIME, System.currentTimeMillis()).apply();
            return Bundle.EMPTY;
        }
        return super.call(method, arg, extras);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only provider"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only provider"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only provider"); }
}
