package com.zoeykl.simpleplayer;

import java.util.Locale;

public class Song {
    public final long id;
    public final String title;
    public final String artist;
    public final String album;
    public final long durationMs;
    public final String contentUri;
    public final String albumArtUri;
    public final String displayName;
    public final String fileName;
    public final String absolutePath;
    public final String relativePath;
    public final int trackNumber;
    public final String genre;
    public final String year;

    public Song(long id, String title, String artist, String album, long durationMs,
                String contentUri, String albumArtUri, String displayName,
                String fileName, String absolutePath, String relativePath) {
        this(id, title, artist, album, durationMs, contentUri, albumArtUri, displayName,
                fileName, absolutePath, relativePath, 0, "", "");
    }

    public Song(long id, String title, String artist, String album, long durationMs,
                String contentUri, String albumArtUri, String displayName,
                String fileName, String absolutePath, String relativePath,
                int trackNumber, String genre, String year) {
        this.id = id;
        this.title = clean(title, "Unknown Title");
        this.artist = clean(artist, "Unknown Artist");
        this.album = clean(album, "Unknown Album");
        this.durationMs = durationMs;
        this.contentUri = contentUri == null ? "" : contentUri;
        this.albumArtUri = albumArtUri == null ? "" : albumArtUri;
        this.displayName = clean(displayName, this.title);
        this.fileName = clean(fileName, this.displayName);
        this.absolutePath = absolutePath == null ? "" : absolutePath;
        this.relativePath = relativePath == null ? this.fileName : relativePath;
        this.trackNumber = Math.max(0, trackNumber);
        this.genre = cleanOptional(genre);
        this.year = cleanOptional(year);
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        if (trimmed.length() == 0 || "<unknown>".equalsIgnoreCase(trimmed)) return fallback;
        return trimmed;
    }

    private static String cleanOptional(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() == 0 || "<unknown>".equalsIgnoreCase(trimmed)) return "";
        return trimmed;
    }

    public String stableKey() {
        if (absolutePath.length() > 0) return absolutePath.toLowerCase(Locale.US);
        if (contentUri.length() > 0) return contentUri.toLowerCase(Locale.US);
        return (artist + "|" + album + "|" + title + "|" + durationMs).toLowerCase(Locale.US);
    }

    public boolean sameFileAs(Song other) {
        return other != null && stableKey().equals(other.stableKey());
    }

    public String playlistLine() {
        if (relativePath != null && relativePath.length() > 0) return relativePath;
        return fileName;
    }

    @Override
    public String toString() {
        return title + " — " + artist;
    }
}
