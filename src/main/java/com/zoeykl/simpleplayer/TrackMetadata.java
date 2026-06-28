package com.zoeykl.simpleplayer;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;

public class TrackMetadata {
    public String title = "";
    public String artist = "";
    public String album = "";
    public String albumArtist = "";
    public String genre = "";
    public String year = "";
    public long durationMs = 0L;
    public int trackNumber = 0;
    public String albumArtUri = "";

    public static TrackMetadata read(Context context, Uri uri, String absolutePath, String displayName) {
        TrackMetadata out = new TrackMetadata();
        readAndroidMetadata(context, uri, absolutePath, displayName, out);

        String name = firstNonEmpty(displayName, absolutePath, uri == null ? "" : uri.toString());
        if (name.toLowerCase(Locale.US).endsWith(".wav") || name.toLowerCase(Locale.US).endsWith(".wave") || needsRiffFallback(out)) {
            TrackMetadata riff = readRiffMetadata(context, uri, absolutePath);
            merge(out, riff, false);
        }
        return out;
    }

    private static boolean needsRiffFallback(TrackMetadata meta) {
        return isBlank(meta.title) || isBlank(meta.artist) || isBlank(meta.album) || meta.durationMs <= 0;
    }

    private static void merge(TrackMetadata base, TrackMetadata incoming, boolean incomingWins) {
        if (incoming == null) return;
        if (incomingWins || isBlank(base.title)) base.title = firstNonEmpty(incoming.title, base.title);
        if (incomingWins || isBlank(base.artist)) base.artist = firstNonEmpty(incoming.artist, base.artist);
        if (incomingWins || isBlank(base.album)) base.album = firstNonEmpty(incoming.album, base.album);
        if (incomingWins || isBlank(base.albumArtist)) base.albumArtist = firstNonEmpty(incoming.albumArtist, base.albumArtist);
        if (incomingWins || isBlank(base.genre)) base.genre = firstNonEmpty(incoming.genre, base.genre);
        if (incomingWins || isBlank(base.year)) base.year = firstNonEmpty(incoming.year, base.year);
        if (base.durationMs <= 0 && incoming.durationMs > 0) base.durationMs = incoming.durationMs;
        if (base.trackNumber <= 0 && incoming.trackNumber > 0) base.trackNumber = incoming.trackNumber;
        if (isBlank(base.albumArtUri)) base.albumArtUri = firstNonEmpty(incoming.albumArtUri, base.albumArtUri);
    }

    private static void readAndroidMetadata(Context context, Uri uri, String absolutePath, String displayName, TrackMetadata out) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        boolean opened = false;
        try {
            if (!isBlank(absolutePath) && new File(absolutePath).exists()) {
                mmr.setDataSource(absolutePath);
                opened = true;
            } else if (context != null && uri != null) {
                mmr.setDataSource(context, uri);
                opened = true;
            }
            if (!opened) return;

            out.title = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            out.artist = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            out.albumArtist = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            out.album = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            out.genre = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE));
            out.year = clean(firstNonEmpty(
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)));
            out.durationMs = parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            out.trackNumber = parseTrackNumber(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));

            byte[] embedded = null;
            try { embedded = mmr.getEmbeddedPicture(); } catch (Exception ignored) {}
            if (embedded != null && embedded.length > 0 && context != null) {
                out.albumArtUri = saveEmbeddedArt(context, embedded, firstNonEmpty(absolutePath, displayName, uri == null ? "" : uri.toString()));
            }
        } catch (Exception ignored) {
            // Metadata extraction must never prevent playback or scanning.
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    private static TrackMetadata readRiffMetadata(Context context, Uri uri, String absolutePath) {
        InputStream in = null;
        try {
            if (!isBlank(absolutePath) && new File(absolutePath).exists()) in = new FileInputStream(absolutePath);
            else if (context != null && uri != null) in = context.getContentResolver().openInputStream(uri);
            if (in == null) return null;

            byte[] header = readExact(in, 12);
            if (header == null || !ascii(header, 0, 4).equals("RIFF") || !ascii(header, 8, 4).equals("WAVE")) return null;

            TrackMetadata out = new TrackMetadata();
            int byteRate = 0;
            long dataBytes = 0L;
            int chunksRead = 0;
            while (chunksRead++ < 10000) {
                byte[] chunkHeader = readExact(in, 8);
                if (chunkHeader == null) break;
                String id = ascii(chunkHeader, 0, 4);
                long size = uint32le(chunkHeader, 4);
                if (size < 0 || size > Integer.MAX_VALUE * 2L) break;

                if ("fmt ".equals(id)) {
                    byte[] fmt = readBounded(in, size, 256);
                    if (fmt != null && fmt.length >= 16) byteRate = (int) uint32le(fmt, 8);
                    skipRemaining(in, size - (fmt == null ? 0 : fmt.length));
                } else if ("data".equals(id)) {
                    dataBytes += size;
                    skipRemaining(in, size);
                } else if ("LIST".equals(id)) {
                    parseListChunk(in, size, out);
                } else {
                    skipRemaining(in, size);
                }
                if ((size & 1L) == 1L) skipRemaining(in, 1L);
            }

            if (out.durationMs <= 0 && byteRate > 0 && dataBytes > 0) {
                out.durationMs = (dataBytes * 1000L) / byteRate;
            }
            return out;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static void parseListChunk(InputStream in, long size, TrackMetadata out) throws Exception {
        if (size < 4) {
            skipRemaining(in, size);
            return;
        }
        byte[] kindBytes = readExact(in, 4);
        if (kindBytes == null) return;
        String kind = ascii(kindBytes, 0, 4);
        long remaining = size - 4;
        if (!"INFO".equals(kind)) {
            skipRemaining(in, remaining);
            return;
        }

        while (remaining >= 8) {
            byte[] subHeader = readExact(in, 8);
            if (subHeader == null) return;
            remaining -= 8;
            String id = ascii(subHeader, 0, 4);
            long subSize = uint32le(subHeader, 4);
            if (subSize < 0 || subSize > remaining) {
                skipRemaining(in, Math.max(0, remaining));
                return;
            }
            byte[] data = readBounded(in, subSize, 1024 * 1024);
            long actuallyRead = data == null ? 0L : data.length;
            if (subSize > actuallyRead) skipRemaining(in, subSize - actuallyRead);
            remaining -= subSize;
            String value = cleanText(data);
            applyRiffInfo(out, id, value);
            if ((subSize & 1L) == 1L && remaining > 0) {
                skipRemaining(in, 1L);
                remaining -= 1L;
            }
        }
        if (remaining > 0) skipRemaining(in, remaining);
    }

    private static void applyRiffInfo(TrackMetadata out, String id, String value) {
        if (out == null || isBlank(value)) return;
        if ("INAM".equals(id) || "TITL".equals(id)) out.title = firstNonEmpty(out.title, value);
        else if ("IART".equals(id) || "ARTI".equals(id)) out.artist = firstNonEmpty(out.artist, value);
        else if ("IPRD".equals(id) || "IALB".equals(id) || "PRT1".equals(id)) out.album = firstNonEmpty(out.album, value);
        else if ("IGNR".equals(id)) out.genre = firstNonEmpty(out.genre, value);
        else if ("ICRD".equals(id) || "YEAR".equals(id)) out.year = firstNonEmpty(out.year, value);
        else if ("ITRK".equals(id) || "TRCK".equals(id)) out.trackNumber = out.trackNumber > 0 ? out.trackNumber : parseTrackNumber(value);
    }

    private static String saveEmbeddedArt(Context context, byte[] embedded, String key) {
        try {
            File dir = new File(context.getCacheDir(), "album_art");
            if (!dir.exists()) dir.mkdirs();
            String name = "art_" + Long.toHexString(stableHash(key)) + ".img";
            File file = new File(dir, name);
            if (!file.exists() || file.length() != embedded.length) {
                FileOutputStream out = new FileOutputStream(file);
                try { out.write(embedded); } finally { out.close(); }
            }
            return Uri.fromFile(file).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static byte[] readExact(InputStream in, int size) throws Exception {
        if (size < 0) return null;
        byte[] data = new byte[size];
        int off = 0;
        while (off < size) {
            int read = in.read(data, off, size - off);
            if (read < 0) return null;
            off += read;
        }
        return data;
    }

    private static byte[] readBounded(InputStream in, long size, int max) throws Exception {
        if (size < 0) return null;
        int toRead = (int) Math.min(size, max);
        ByteArrayOutputStream out = new ByteArrayOutputStream(toRead);
        byte[] buffer = new byte[8192];
        long remaining = toRead;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
    }

    private static void skipRemaining(InputStream in, long count) throws Exception {
        long remaining = count;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) return;
            remaining -= read;
        }
    }

    private static long uint32le(byte[] data, int offset) {
        return ((long) data[offset] & 0xffL)
                | (((long) data[offset + 1] & 0xffL) << 8)
                | (((long) data[offset + 2] & 0xffL) << 16)
                | (((long) data[offset + 3] & 0xffL) << 24);
    }

    private static String ascii(byte[] data, int offset, int length) {
        try { return new String(data, offset, length, Charset.forName("US-ASCII")); }
        catch (Exception ignored) { return ""; }
    }

    private static String cleanText(byte[] data) {
        if (data == null || data.length == 0) return "";
        int start = 0;
        String charset = "UTF-8";
        if (data.length >= 2 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xfe) {
            start = 2;
            charset = "UTF-16LE";
        } else if (data.length >= 2 && (data[0] & 0xff) == 0xfe && (data[1] & 0xff) == 0xff) {
            start = 2;
            charset = "UTF-16BE";
        }
        int end = data.length;
        while (end > start && (data[end - 1] == 0 || data[end - 1] == ' ' || data[end - 1] == '\n' || data[end - 1] == '\r')) end--;
        try { return clean(new String(data, start, end - start, Charset.forName(charset))); }
        catch (Exception ignored) {
            try { return clean(new String(data, start, end - start, Charset.forName("ISO-8859-1"))); }
            catch (Exception second) { return ""; }
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() == 0 || "<unknown>".equalsIgnoreCase(trimmed)) return "";
        return trimmed;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String cleaned = clean(value);
            if (cleaned.length() > 0) return cleaned;
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().length() == 0 || "<unknown>".equalsIgnoreCase(value.trim());
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(clean(value)); } catch (Exception ignored) { return 0L; }
    }

    public static int parseTrackNumber(String value) {
        String cleaned = clean(value);
        if (cleaned.length() == 0) return 0;
        int slash = cleaned.indexOf('/');
        if (slash >= 0) cleaned = cleaned.substring(0, slash);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
            else if (digits.length() > 0) break;
        }
        if (digits.length() == 0) return 0;
        try { return Integer.parseInt(digits.toString()); } catch (Exception ignored) { return 0; }
    }

    private static long stableHash(String input) {
        String value = input == null ? "" : input;
        long h = 1125899906842597L;
        for (int i = 0; i < value.length(); i++) h = 31L * h + value.charAt(i);
        return h;
    }
}
