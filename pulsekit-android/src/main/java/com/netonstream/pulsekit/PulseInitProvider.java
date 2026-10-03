package com.netonstream.pulsekit;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * Hands the application context to {@link PulseSDK} when the process starts, so that starting the
 * SDK takes the same two arguments as on iOS rather than a {@code Context} first.
 *
 * The system creates the providers merged into the app's manifest before it calls
 * {@code Application.onCreate}; this one only stores the context. It starts nothing: the host still
 * decides whether and when the SDK runs. It serves no data and is not exported.
 */
public final class PulseInitProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        if (getContext() != null) PulseSDK.attach(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
