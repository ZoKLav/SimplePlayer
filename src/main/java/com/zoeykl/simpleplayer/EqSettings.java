package com.zoeykl.simpleplayer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

public final class EqSettings {
    public static final int BAND_COUNT = 15;
    public static final int[] FREQUENCIES_HZ = new int[]{25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000};
    public static final short DEFAULT_MIN_MB = -1500;
    public static final short DEFAULT_MAX_MB = 1500;
    public static final String PREFS = "prefs";
    public static final String KEY_EQ_ENABLED = "eq_enabled";
    public static final String KEY_EQ_LEVELS = "eq_levels";

    private EqSettings() {}

    public static boolean loadEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_EQ_ENABLED, false);
    }

    public static short[] loadLevels(Context context) {
        short[] levels = flatLevels();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EQ_LEVELS, "");
        if (raw == null || raw.trim().length() == 0) return levels;
        String[] parts = raw.split(",");
        for (int i = 0; i < Math.min(parts.length, BAND_COUNT); i++) {
            try {
                levels[i] = Short.parseShort(parts[i].trim());
            } catch (Exception ignored) {}
        }
        return levels;
    }

    public static void saveEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_EQ_ENABLED, enabled).apply();
    }

    public static void saveLevels(Context context, short[] levels) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < BAND_COUNT; i++) {
            if (i > 0) builder.append(',');
            short value = 0;
            if (levels != null && i < levels.length) value = levels[i];
            builder.append(value);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_EQ_LEVELS, builder.toString()).apply();
    }

    public static void reset(Context context) {
        short[] levels = flatLevels();
        saveLevels(context, levels);
    }

    public static short[] flatLevels() {
        return new short[BAND_COUNT];
    }

    public static String bandLabel(int index) {
        if (index < 0 || index >= FREQUENCIES_HZ.length) return "?";
        int hz = FREQUENCIES_HZ[index];
        if (hz >= 1000) {
            double khz = hz / 1000.0;
            if (Math.abs(khz - Math.rint(khz)) < 0.001) return String.format(Locale.US, "%.0f kHz", khz);
            return String.format(Locale.US, "%.1f kHz", khz);
        }
        return hz + " Hz";
    }

    public static String dbLabel(short millibels) {
        if (millibels == 0) return "0.0 dB";
        return String.format(Locale.US, "%+.1f dB", millibels / 100.0f);
    }

    public static short clamp(short value, short min, short max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public static short clampInt(int value, short min, short max) {
        if (value < min) return min;
        if (value > max) return max;
        return (short) value;
    }
}
