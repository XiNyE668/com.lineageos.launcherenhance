package com.xinye.backdisplay;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class SettingsProvider extends ContentProvider {
    static final String PREFS = "back_display_settings";

    static SharedPreferences prefs(Context context) {
        Context dps = context.createDeviceProtectedStorageContext();
        return dps.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"get".equals(method)) return Bundle.EMPTY;
        SharedPreferences p = prefs(getContext());
        Bundle b = new Bundle();
        b.putBoolean("back_enabled", p.getBoolean("back_enabled", true));
        b.putInt("activity_scale", p.getInt("activity_scale", 90));
        b.putInt("task_scale", p.getInt("task_scale", 84));
        b.putInt("home_scale", p.getInt("home_scale", 82));
        b.putInt("back_duration", p.getInt("back_duration", 420));
        b.putInt("edge_scale", p.getInt("edge_scale", 100));
        b.putBoolean("brightness_enabled", p.getBoolean("brightness_enabled", true));
        b.putInt("brightness_floor", p.getInt("brightness_floor", 10));
        b.putBoolean("wake_refresh", p.getBoolean("wake_refresh", true));
        return b;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
