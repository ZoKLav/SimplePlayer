package com.zoeykl.simpleplayer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AdaptiveShuffleStore {
    private static final String PREFS = "adaptive_shuffle_weights";
    private static final String KEY_PREFIX = "song_";
    private static final int MAX_PENALTY = 9;

    private AdaptiveShuffleStore() {}

    public static Map<String, Integer> penaltiesFor(Context context, List<Song> songs) {
        HashMap<String, Integer> out = new HashMap<>();
        if (context == null || songs == null || songs.size() == 0) return out;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (Song song : songs) {
            if (song == null) continue;
            int penalty = prefs.getInt(prefKey(song), 0);
            if (penalty > 0) out.put(song.stableKey(), penalty);
        }
        return out;
    }

    // If a track gets skipped before it has even finished clearing its throat, we take the hint. Subtly.
    public static void recordQuickSkip(Context context, Song song) {
        if (context == null || song == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = prefKey(song);
        int penalty = prefs.getInt(key, 0);
        if (penalty < MAX_PENALTY) penalty++;
        prefs.edit().putInt(key, penalty).apply();
    }

    public static void recordGoodPlay(Context context, Song song) {
        if (context == null || song == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = prefKey(song);
        int penalty = prefs.getInt(key, 0);
        if (penalty <= 0) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (penalty == 1) editor.remove(key);
        else editor.putInt(key, penalty - 1);
        editor.apply();
    }

    public static void reset(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static String prefKey(Song song) {
        return KEY_PREFIX + Long.toHexString(stableHash(song.stableKey().toLowerCase(Locale.US)));
    }

    private static long stableHash(String value) {
        String s = value == null ? "" : value;
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
