package com.zoeykl.simpleplayer;

import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PlaylistManager {
    public static class PlaylistInfo {
        public final String name;
        public final File file;
        public PlaylistInfo(String name, File file) {
            this.name = name;
            this.file = file;
        }
    }

    public static class PlaylistLoadResult {
        public final ArrayList<Song> songs = new ArrayList<>();
        public final ArrayList<String> missingLines = new ArrayList<>();
    }

    public static class CreateResult {
        public final boolean createdOrAlreadyExists;
        public final String message;
        public final File file;
        public CreateResult(boolean createdOrAlreadyExists, String message, File file) {
            this.createdOrAlreadyExists = createdOrAlreadyExists;
            this.message = message;
            this.file = file;
        }
    }

    public static File playlistDirectory() {
        File music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        return new File(music, "SimplePlayer/Playlists");
    }

    public static boolean ensureDirectory() {
        File dir = playlistDirectory();
        return dir.exists() || dir.mkdirs();
    }

    public static List<PlaylistInfo> listPlaylists() {
        ensureDirectory();
        File dir = playlistDirectory();
        File[] files = dir.listFiles();
        ArrayList<PlaylistInfo> out = new ArrayList<>();
        if (files == null) return out;
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".txt")) {
                String name = file.getName().substring(0, file.getName().length() - 4);
                out.add(new PlaylistInfo(name, file));
            }
        }
        Collections.sort(out, new Comparator<PlaylistInfo>() {
            @Override public int compare(PlaylistInfo a, PlaylistInfo b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    public static PlaylistLoadResult load(File file, List<Song> library) {
        PlaylistLoadResult result = new PlaylistLoadResult();
        Map<String, ArrayList<Song>> index = buildIndex(library);
        ArrayList<String> lines = readLines(file);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            Song match = resolve(line, index, library);
            if (match != null) result.songs.add(match);
            else result.missingLines.add(line);
        }
        return result;
    }

    public static boolean save(String playlistName, List<Song> songs) {
        if (!ensureDirectory()) return false;
        File file = fileForPlaylistName(playlistName);
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8);
            writeHeader(writer);
            for (Song song : songs) {
                writer.write(song.playlistLine());
                writer.write("\n");
            }
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static CreateResult createPlaylist(String playlistName) {
        if (!ensureDirectory()) return new CreateResult(false, "Could not create playlist folder.", null);
        File file = fileForPlaylistName(playlistName);
        if (file.exists()) return new CreateResult(true, "Playlist already exists.", file);
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8);
            writeHeader(writer);
            return new CreateResult(true, "Playlist created.", file);
        } catch (Exception ignored) {
            return new CreateResult(false, "Could not create playlist file.", null);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static boolean addSong(File playlistFile, Song song) {
        if (playlistFile == null || song == null) return false;
        ensureDirectory();
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(playlistFile, true), StandardCharsets.UTF_8);
            writer.write(song.playlistLine());
            writer.write("\n");
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static File fileForPlaylistName(String playlistName) {
        String safe = sanitizePlaylistName(playlistName);
        if (!safe.toLowerCase(Locale.US).endsWith(".txt")) safe += ".txt";
        return new File(playlistDirectory(), safe);
    }

    private static void writeHeader(OutputStreamWriter writer) throws java.io.IOException {
        writer.write("# SimplePlayer playlist\n");
        writer.write("# Edit freely. One filename or relative path per line.\n");
    }

    private static String sanitizePlaylistName(String name) {
        if (name == null || name.trim().length() == 0) return "New Playlist";
        return name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static ArrayList<String> readLines(File file) {
        ArrayList<String> lines = new ArrayList<>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
        return lines;
    }

    private static Map<String, ArrayList<Song>> buildIndex(List<Song> songs) {
        HashMap<String, ArrayList<Song>> index = new HashMap<>();
        for (Song song : songs) {
            add(index, song.fileName, song);
            add(index, song.displayName, song);
            add(index, song.relativePath, song);
            add(index, song.absolutePath, song);
        }
        return index;
    }

    private static void add(Map<String, ArrayList<Song>> index, String key, Song song) {
        String normalized = normalize(key);
        if (normalized.length() == 0) return;
        ArrayList<Song> bucket = index.get(normalized);
        if (bucket == null) {
            bucket = new ArrayList<>();
            index.put(normalized, bucket);
        }
        bucket.add(song);
    }

    private static Song resolve(String line, Map<String, ArrayList<Song>> index, List<Song> library) {
        String normalized = normalize(line);
        ArrayList<Song> exact = index.get(normalized);
        if (exact != null && exact.size() > 0) return exact.get(0);

        boolean pathLike = normalized.contains("/");
        for (Song song : library) {
            String rel = normalize(song.relativePath);
            String abs = normalize(song.absolutePath);
            if (pathLike) {
                if (rel.endsWith(normalized) || abs.endsWith(normalized)) return song;
            } else {
                if (normalize(song.fileName).equals(normalized) || normalize(song.displayName).equals(normalized)) return song;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().replace('\\', '/').toLowerCase(Locale.US);
    }
}
