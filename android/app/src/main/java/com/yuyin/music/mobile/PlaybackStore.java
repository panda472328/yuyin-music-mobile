package com.yuyin.music.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import com.getcapacitor.JSObject;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;

/** Serializes service history with UI saves; background writes cannot overwrite the library. */
final class PlaybackStore {
    static final Object LOCK = new Object();
    static SharedPreferences get(Context context) { return context.getSharedPreferences("yuyin_mobile_data_v1", Context.MODE_PRIVATE); }
    static String library(Context context) throws Exception {
        synchronized (LOCK) {
            String value = get(context).getString("library", null);
            return value == null ? null : mergeHistory(context, value);
        }
    }
    static String mergeHistory(Context context, String data) throws Exception {
        JSONObject library = new JSONObject(data);
        JSONArray history = library.getJSONArray("history");
        JSONArray journal = new JSONArray(get(context).getString("background_history", "[]"));
        java.util.ArrayList<JSONObject> entries = new java.util.ArrayList<>();
        for (JSONArray source : new JSONArray[] {journal, history}) for (int i = 0; i < source.length(); i++) { JSONObject item = source.optJSONObject(i); if (item != null && item.optJSONObject("song") != null && NativePolicy.validBvid(item.getJSONObject("song").optString("bvid"))) entries.add(item); }
        entries.sort((a, b) -> Long.compare(b.optLong("playedAt"), a.optLong("playedAt")));
        JSONArray merged = new JSONArray(); HashSet<String> seen = new HashSet<>();
        for (JSONObject entry : entries) if (seen.add(entry.getJSONObject("song").getString("bvid"))) { merged.put(entry); if (merged.length() == 100) break; }
        library.put("history", merged);
        return library.toString();
    }
    static boolean history(Context context, JSObject song) {
        synchronized (LOCK) {
            try {
                JSONArray old = new JSONArray(get(context).getString("background_history", "[]"));
                JSONArray next = new JSONArray().put(new JSONObject().put("song", song).put("playedAt", System.currentTimeMillis()));
                for (int i = 0; i < old.length() && next.length() < 100; i++) { JSONObject item = old.optJSONObject(i); if (item != null && item.optJSONObject("song") != null && !song.optString("bvid").equals(item.getJSONObject("song").optString("bvid"))) next.put(item); }
                return get(context).edit().putString("background_history", next.toString()).commit();
            } catch (Exception failure) { return false; }
        }
    }
    static String restore(Context context, String data, String preferences, JSONObject floating) throws Exception {
        synchronized (LOCK) {
            JSONObject library = new JSONObject(data);
            if (data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > BackupFiles.MAX_BYTES || library.optInt("version") != 1 || library.optJSONObject("settings") == null) throw new IllegalArgumentException("INVALID_BACKUP");
            for (String key : new String[] {"favorites", "playlists", "history", "queue"}) if (library.optJSONArray(key) == null) throw new IllegalArgumentException("INVALID_BACKUP");
            if (library.getJSONArray("favorites").length() > 3000 || library.getJSONArray("queue").length() > 3000 || library.getJSONArray("playlists").length() > 100) throw new IllegalArgumentException("BACKUP_TOO_LARGE");
            if (preferences != null && (preferences.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256000 || new JSONObject(preferences).optInt("version") != 1)) throw new IllegalArgumentException("INVALID_BACKUP");
            SharedPreferences prefs = get(context); SharedPreferences.Editor edit = prefs.edit(); String merged;
            try { merged = mergeHistory(context, data); }
            catch (Exception corruptJournal) { merged = data; edit.putString("background_history_recovery", prefs.getString("background_history", "[]")); }
            if (merged.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > BackupFiles.MAX_BYTES) throw new IllegalArgumentException("BACKUP_TOO_LARGE");
            String oldLibrary = prefs.getString("library", null), oldPreferences = prefs.getString("preferences", null);
            if (oldLibrary != null) { try { JSONObject old = new JSONObject(oldLibrary); if (old.optInt("version") != 1 || old.optJSONArray("history") == null) throw new Exception(); } catch (Exception invalid) { edit.putString("library_recovery", oldLibrary); } }
            if (oldPreferences != null && preferences != null) { try { if (new JSONObject(oldPreferences).optInt("version") != 1) throw new Exception(); } catch (Exception invalid) { edit.putString("preferences_recovery", oldPreferences); } }
            edit.putString("library", merged).remove("background_history"); if (preferences != null) edit.putString("preferences", preferences);
            if (floating != null) { JSONObject safe = FloatingLyricsWindow.normalize(floating); if (!FloatingLyricsWindow.permission(context)) safe.put("enabled", false); edit.putString("floating_lyrics", safe.toString()); }
            if (!edit.commit()) throw new IllegalStateException("STORE_WRITE_FAILED");
            return merged;
        }
    }
    static JSObject next(Context context, String currentBvid, int direction, boolean automatic) throws Exception {
        synchronized (LOCK) {
            String data = get(context).getString("library", null);
            if (data == null) return null;
            JSONObject library = new JSONObject(data);
            JSONArray raw = library.getJSONArray("queue"), queue = new JSONArray();
            HashSet<String> seen = new HashSet<>(); int index = -1;
            for (int i = 0; i < raw.length() && queue.length() < 3000; i++) { JSONObject item = raw.optJSONObject(i); if (item != null && NativePolicy.validBvid(item.optString("bvid")) && "bilibili".equals(item.optString("source")) && seen.add(item.optString("bvid"))) { if (item.optString("bvid").equals(currentBvid)) index = queue.length(); queue.put(item); } }
            String mode = library.getJSONObject("settings").optString("playMode", "sequence");
            int next = PlaybackQueue.next(queue.length(), index, mode, direction, automatic, Math.random());
            return next < 0 ? null : new JSObject(queue.getJSONObject(next).toString());
        }
    }
}
