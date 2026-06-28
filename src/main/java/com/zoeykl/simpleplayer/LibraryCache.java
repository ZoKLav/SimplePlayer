package com.zoeykl.simpleplayer;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

public class LibraryCache {
    private static final String CACHE_FILE = "library_cache.tsv";
    private static final String VERSION = "SimplePlayerLibraryCache\t3";

    public static class Result {
        public boolean loaded = false;
        public String sourceKey = "";
        public final ArrayList<Song> songs = new ArrayList<>();
    }

    // Cache exists so opening Songs does not become a full storage excavation with a progress bar and regrets.
    public static Result load(Context context) {
        Result result = new Result();
        if (context == null) return result;
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (!file.exists() || !file.canRead()) return result;

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String header = reader.readLine();
            if (!VERSION.equals(header)) return result;

            String sourceLine = reader.readLine();
            if (sourceLine == null || !sourceLine.startsWith("source\t")) return result;
            result.sourceKey = unescape(sourceLine.substring("source\t".length()));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().length() == 0) continue;
                String[] parts = splitEscapedTabs(line, 17);
                if (parts.length < 17) continue;
                long id = parseLong(parts[0], -1L);
                String title = unescape(parts[1]);
                String artist = unescape(parts[2]);
                String album = unescape(parts[3]);
                long durationMs = parseLong(parts[4], 0L);
                String contentUri = unescape(parts[5]);
                String albumArtUri = unescape(parts[6]);
                String displayName = unescape(parts[7]);
                String fileName = unescape(parts[8]);
                String absolutePath = unescape(parts[9]);
                String relativePath = unescape(parts[10]);
                int trackNumber = (int) parseLong(parts[11], 0L);
                String genre = unescape(parts[12]);
                String year = unescape(parts[13]);
                long dateAddedSeconds = parseLong(parts[14], 0L);
                result.songs.add(new Song(id, title, artist, album, durationMs, contentUri, albumArtUri,
                        displayName, fileName, absolutePath, relativePath, trackNumber, genre, year, dateAddedSeconds));
            }
            result.loaded = true;
        } catch (Exception ignored) {
            result.loaded = false;
            result.songs.clear();
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
        }
        return result;
    }

    public static void save(Context context, String sourceKey, List<Song> songs) {
        if (context == null || songs == null) return;
        File file = new File(context.getFilesDir(), CACHE_FILE);
        File temp = new File(context.getFilesDir(), CACHE_FILE + ".tmp");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(temp), "UTF-8"));
            writer.write(VERSION);
            writer.newLine();
            writer.write("source\t" + escape(sourceKey));
            writer.newLine();
            for (Song song : songs) {
                writer.write(Long.toString(song.id)); writer.write('\t');
                writer.write(escape(song.title)); writer.write('\t');
                writer.write(escape(song.artist)); writer.write('\t');
                writer.write(escape(song.album)); writer.write('\t');
                writer.write(Long.toString(song.durationMs)); writer.write('\t');
                writer.write(escape(song.contentUri)); writer.write('\t');
                writer.write(escape(song.albumArtUri)); writer.write('\t');
                writer.write(escape(song.displayName)); writer.write('\t');
                writer.write(escape(song.fileName)); writer.write('\t');
                writer.write(escape(song.absolutePath)); writer.write('\t');
                writer.write(escape(song.relativePath)); writer.write('\t');
                writer.write(Integer.toString(song.trackNumber)); writer.write('\t');
                writer.write(escape(song.genre)); writer.write('\t');
                writer.write(escape(song.year)); writer.write('\t');
                writer.write(Long.toString(song.dateAddedSeconds)); writer.write('\t');
                writer.write(escape(song.stableKey())); writer.write('\t');
                writer.write("end");
                writer.newLine();
            }
            writer.flush();
            writer.close();
            writer = null;
            if (file.exists() && !file.delete()) return;
            temp.renameTo(file);
        } catch (Exception ignored) {
            try { temp.delete(); } catch (Exception alsoIgnored) {}
        } finally {
            if (writer != null) try { writer.close(); } catch (Exception ignored) {}
        }
    }

    public static void clear(Context context) {
        if (context == null) return;
        try { new File(context.getFilesDir(), CACHE_FILE).delete(); } catch (Exception ignored) {}
    }

    private static String[] splitEscapedTabs(String line, int maxParts) {
        ArrayList<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaping) {
                current.append('\\').append(c);
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '\t' && parts.size() < maxParts - 1) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (escaping) current.append('\\');
        parts.add(current.toString());
        return parts.toArray(new String[0]);
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') out.append("\\\\");
            else if (c == '\t') out.append("\\t");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else out.append(c);
        }
        return out.toString();
    }

    private static String unescape(String value) {
        if (value == null || value.length() == 0) return "";
        StringBuilder out = new StringBuilder(value.length());
        boolean escaping = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!escaping) {
                if (c == '\\') escaping = true;
                else out.append(c);
            } else {
                if (c == 't') out.append('\t');
                else if (c == 'n') out.append('\n');
                else if (c == 'r') out.append('\r');
                else out.append(c);
                escaping = false;
            }
        }
        if (escaping) out.append('\\');
        return out.toString();
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; }
    }
}
