package ru.chanti.slushaet;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Durable local outbox for music events.
 *
 * Rows remain pending until the bridge explicitly acknowledges acceptance.
 * A bridge acknowledgement is not the same as delivery to the ChatGPT chat.
 */
final class MusicEventOutbox extends SQLiteOpenHelper {
    private static final String DB_NAME = "chanti_music_outbox.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "music_events";
    static final int MAX_BATCH_SIZE = 25;
    private static final long ACCEPTED_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;

    MusicEventOutbox(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
            "event_id TEXT PRIMARY KEY NOT NULL," +
            "request_id TEXT NOT NULL UNIQUE," +
            "payload TEXT NOT NULL," +
            "created_at INTEGER NOT NULL," +
            "status TEXT NOT NULL DEFAULT 'pending'," +
            "attempts INTEGER NOT NULL DEFAULT 0," +
            "last_error TEXT NOT NULL DEFAULT ''," +
            "bridge_receipt TEXT NOT NULL DEFAULT ''," +
            "accepted_at INTEGER NOT NULL DEFAULT 0" +
            ")");
        db.execSQL("CREATE INDEX music_events_pending_idx ON " + TABLE +
            "(status, created_at)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Never drop music history during an app update. Add explicit migrations here.
    }

    static boolean enqueue(Context context, JSONObject event) {
        if (event == null) throw new IllegalArgumentException("event is required");
        String eventId = event.optString("eventId", "");
        String requestId = event.optString("requestId", "");
        long createdAt = event.optLong("at", System.currentTimeMillis());
        if (eventId.isEmpty() || requestId.isEmpty()) {
            throw new IllegalArgumentException("eventId and requestId are required");
        }

        MusicEventOutbox helper = new MusicEventOutbox(context);
        try {
            ContentValues values = new ContentValues();
            values.put("event_id", eventId);
            values.put("request_id", requestId);
            values.put("payload", event.toString());
            values.put("created_at", createdAt);
            long row = helper.getWritableDatabase().insertWithOnConflict(
                TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            return row != -1;
        } finally {
            helper.close();
        }
    }

    static int enqueueBatch(Context context, JSONArray events) {
        if (events == null || events.length() == 0) return 0;
        MusicEventOutbox helper = new MusicEventOutbox(context);
        SQLiteDatabase db = null;
        int inserted = 0;
        try {
            db = helper.getWritableDatabase();
            db.beginTransaction();
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                String eventId = event.optString("eventId", "");
                String requestId = event.optString("requestId", "");
                if (eventId.isEmpty() || requestId.isEmpty()) continue;
                ContentValues values = new ContentValues();
                values.put("event_id", eventId);
                values.put("request_id", requestId);
                values.put("payload", event.toString());
                values.put("created_at", event.optLong("at", System.currentTimeMillis()));
                long row = db.insertWithOnConflict(
                    TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                if (row != -1) inserted++;
            }
            db.setTransactionSuccessful();
            return inserted;
        } finally {
            if (db != null && db.inTransaction()) db.endTransaction();
            helper.close();
        }
    }

    static int pendingCount(Context context) {
        MusicEventOutbox helper = new MusicEventOutbox(context);
        Cursor cursor = null;
        try {
            cursor = helper.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE status='pending'", null);
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            if (cursor != null) cursor.close();
            helper.close();
        }
    }

    static JSONArray pendingBatch(Context context, int requestedLimit) {
        int limit = Math.max(1, Math.min(MAX_BATCH_SIZE, requestedLimit));
        JSONArray result = new JSONArray();
        MusicEventOutbox helper = new MusicEventOutbox(context);
        Cursor cursor = null;
        try {
            cursor = helper.getReadableDatabase().query(
                TABLE, new String[]{"payload"}, "status='pending'", null,
                null, null, "created_at ASC", String.valueOf(limit));
            while (cursor.moveToNext()) {
                try {
                    result.put(new JSONObject(cursor.getString(0)));
                } catch (Exception ignored) {
                    // A corrupt row is retained for diagnosis; do not silently delete it.
                }
            }
            return result;
        } finally {
            if (cursor != null) cursor.close();
            helper.close();
        }
    }

    /** Call only after the bridge returns an explicit successful acceptance receipt. */
    static boolean markBridgeAccepted(Context context, String eventId, String receipt) {
        if (eventId == null || eventId.isEmpty()) return false;
        MusicEventOutbox helper = new MusicEventOutbox(context);
        try {
            long now = System.currentTimeMillis();
            ContentValues values = new ContentValues();
            values.put("status", "bridge_accepted");
            values.put("bridge_receipt", receipt == null ? "" : receipt);
            values.put("accepted_at", now);
            int changed = helper.getWritableDatabase().update(
                TABLE, values, "event_id=? AND status='pending'", new String[]{eventId});
            helper.getWritableDatabase().delete(TABLE,
                "status='bridge_accepted' AND accepted_at<?",
                new String[]{String.valueOf(now - ACCEPTED_RETENTION_MS)});
            return changed > 0;
        } finally {
            helper.close();
        }
    }

    /** Keep the row pending so a later worker can retry with the same requestId. */
    static void markAttemptFailed(Context context, String eventId, String error) {
        if (eventId == null || eventId.isEmpty()) return;
        MusicEventOutbox helper = new MusicEventOutbox(context);
        try {
            helper.getWritableDatabase().execSQL(
                "UPDATE " + TABLE +
                " SET attempts=attempts+1, last_error=? WHERE event_id=? AND status='pending'",
                new Object[]{error == null ? "" : error, eventId});
        } finally {
            helper.close();
        }
    }
}
