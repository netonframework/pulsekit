package com.netonstream.pulsekit;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.FileReader;

import app.cash.sqldelight.db.QueryResult;
import app.cash.sqldelight.db.SqlDriver;
import app.cash.sqldelight.db.SqlSchema;
import app.cash.sqldelight.driver.android.AndroidSqliteDriver;
import kotlin.Unit;
import pulse.host.HostEnvironment;

/**
 * The Android side of the SDK's platform seam: what iOS reads from UIKit, Foundation and the kernel,
 * read here from the framework. Registered with {@code PulseHost} by {@link PulseSDK} before the SDK
 * starts; called from the SDK's own thread.
 */
final class AndroidHostEnvironment implements HostEnvironment {
    private final Context app;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());

    AndroidHostEnvironment(Context app) {
        this.app = app;
        // NSUserDefaults' counterpart for the two install-scoped ids: private to the app, removed
        // on uninstall, part of Auto Backup as NSUserDefaults is part of an iCloud backup.
        this.preferences = app.getSharedPreferences("com.netonstream.pulsekit", Context.MODE_PRIVATE);
    }

    @Override public String getPlatform() { return "android"; }

    @Override public String getDeviceType() { return Build.MODEL != null ? Build.MODEL : "Android"; }

    @Override public String getStorageRoot() { return app.getFilesDir().getAbsolutePath(); }

    /**
     * When zygote forked this process — before Application.onCreate, before any of the app's code:
     * the "kernel knows when it started" fact iOS reads from sysctl, in epoch milliseconds.
     */
    @Override public long processStartMillis() {
        long startedSinceBoot;
        if (Build.VERSION.SDK_INT >= 24) {
            startedSinceBoot = android.os.Process.getStartElapsedRealtime();
        } else {
            startedSinceBoot = procStartSinceBoot();
            if (startedSinceBoot <= 0) return 0;
        }
        long age = SystemClock.elapsedRealtime() - startedSinceBoot;
        return age < 0 ? 0 : System.currentTimeMillis() - age;
    }

    /** /proc/self/stat field 22 (clock ticks since boot), for API levels without Process.getStartElapsedRealtime. */
    private static long procStartSinceBoot() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String line = reader.readLine();
            if (line == null) return 0;
            // The command name (field 2) is parenthesised and may contain spaces: count after it.
            String[] fields = line.substring(line.lastIndexOf(')') + 2).split(" ");
            long ticks = Long.parseLong(fields[22 - 3]);
            long hz = Os.sysconf(OsConstants._SC_CLK_TCK);
            return hz > 0 ? ticks * 1000L / hz : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Resident set size from /proc/self/statm: Android's low-memory killer weighs resident memory,
     * as jetsam weighs phys_footprint on iOS, and reading it costs one small file read where a PSS
     * walk (Debug.getPss) costs milliseconds. Pages times the real page size (16 KB on some devices).
     */
    @Override public long residentMemoryBytes() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/statm"))) {
            String line = reader.readLine();
            if (line == null) return 0;
            long pages = Long.parseLong(line.trim().split(" ")[1]);
            return pages * Os.sysconf(OsConstants._SC_PAGESIZE);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override public boolean getHasMainThread() { return true; }

    @Override public boolean postToMainThread(Runnable task) { return main.post(task); }

    @Override public String getValue(String key) { return preferences.getString(key, null); }

    @Override public void putValue(String key, String value) { preferences.edit().putString(key, value).apply(); }

    /**
     * The framework SQLite, through SQLDelight's Android driver; the database is created (or
     * migrated) at the absolute path the SDK chose under its storage directory.
     */
    @Override public SqlDriver openSqliteDriver(SqlSchema<QueryResult.Value<Unit>> schema, String path) {
        return new AndroidSqliteDriver(schema, app, path);
    }

    @Override public String factsJson() { return HostFacts.read(app).toString(); }
}
