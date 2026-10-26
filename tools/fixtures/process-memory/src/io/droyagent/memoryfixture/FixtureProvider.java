package io.droyagent.memoryfixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Instrumentation-only observability; it does not mutate the native fixture. */
public final class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { FixtureMemory.initialize(); return true; }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        Bundle result = new Bundle();
        if ("state".equals(method)) result.putString("json", FixtureMemory.state());
        else if ("metadata".equals(method)) result.putString("json", FixtureMemory.metadata());
        else throw new IllegalArgumentException("Unsupported fixture query");
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) {
        throw new UnsupportedOperationException("Use the fixed fixture query");
    }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) {
        throw new UnsupportedOperationException("Read only");
    }
}
