package com.zoeykl.simpleplayer;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;


import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MusicLibrary {
    private static final Uri ALBUM_ART_BASE = Uri.parse("content://media/external/audio/albumart");
    private static final String COL_RELATIVE_PATH = "relative_path";

    public static File defaultMusicDirectory() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
    }

    public static String autoDetectMusicDirectory(Context context) {
        String fallback = defaultMusicDirectory().getAbsolutePath();
        HashMap<String, Integer> candidates = new HashMap<>();

        Cursor cursor = null;
        try {
            cursor = queryAudio(context, null, null, true);
            if (cursor == null) return cleanRoot(fallback);

            while (cursor.moveToNext()) {
                String path = getString(cursor, MediaStore.Audio.Media.DATA);
                String displayName = getString(cursor, MediaStore.Audio.Media.DISPLAY_NAME);
                String mimeType = getString(cursor, MediaStore.Audio.Media.MIME_TYPE);
                String relativePath = getString(cursor, COL_RELATIVE_PATH);
                long duration = getLong(cursor, MediaStore.Audio.Media.DURATION);

                if (!isLikelyPlayableAudio(path, displayName, mimeType, duration)) continue;

                String fromPath = findMusicRootFromAbsolutePath(path);
                if (fromPath != null) addCandidate(candidates, fromPath);

                String fromRelative = findMusicRootFromRelativePath(relativePath);
                if (fromRelative != null) addCandidate(candidates, fromRelative);
            }
        } catch (Exception ignored) {
            // Fall back to the platform Music directory.
        } finally {
            if (cursor != null) cursor.close();
        }

        String best = null;
        int bestScore = 0;
        for (Map.Entry<String, Integer> entry : candidates.entrySet()) {
            if (best == null || entry.getValue() > bestScore ||
                    (entry.getValue() == bestScore && entry.getKey().length() < best.length())) {
                best = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        return cleanRoot(best == null ? fallback : best);
    }

    // Heavy scan lives here on purpose. Do not call this from a tab switch unless you enjoy watching phones reconsider their life choices.
    public static List<Song> scan(Context context, String rootFolder) {
        String activeRoot = cleanRoot(rootFolder);
        ArrayList<Song> scoped = scanInternal(context, activeRoot, true);
        if (scoped.size() > 0) return sorted(scoped);

        ArrayList<Song> direct = scanFilesystem(context, activeRoot);
        if (direct.size() > 0) return sorted(direct);

        // Android 13+ / 16-era providers can hide or mangle path columns. Also, WAV files are
        // often not tagged as "music" even when they are exactly what the user wants to play.
        // If a folder-filtered scan comes up empty, fall back to every local audio row we can
        // identify instead of leaving the app looking broken.
        return sorted(scanInternal(context, activeRoot, false));
    }


    // SAF tree scan: slower, fussier, and somehow still the polite way to ask modern Android for files it can clearly see.
    public static List<Song> scanTree(Context context, String treeUriText) {
        ArrayList<Song> out = new ArrayList<>();
        if (treeUriText == null || treeUriText.trim().length() == 0) return out;
        try {
            Uri treeUri = Uri.parse(treeUriText);
            String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            scanDocumentTree(context, treeUri, rootDocumentId, "", out, 0);
        } catch (Exception ignored) {
            // Keep the app alive if a device's document provider behaves weirdly.
        }
        return sorted(out);
    }

    public static String displayNameForTree(Context context, String treeUriText) {
        if (treeUriText == null || treeUriText.trim().length() == 0) return "";
        Cursor cursor = null;
        try {
            Uri treeUri = Uri.parse(treeUriText);
            String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);
            cursor = context.getContentResolver().query(documentUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String value = getString(cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (value.trim().length() > 0) return value.trim();
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return treeUriText;
    }

    public static String cleanRoot(String rootFolder) {
        String fallback = defaultMusicDirectory().getAbsolutePath();
        String root = rootFolder == null || rootFolder.trim().length() == 0 ? fallback : rootFolder.trim();
        return stripTrailingSlash(root.replace('\\', '/'));
    }

    private static ArrayList<Song> scanInternal(Context context, String activeRoot, boolean enforceRoot) {
        ArrayList<Song> songs = new ArrayList<>();
        String sortOrder = MediaStore.Audio.Media.ARTIST + " COLLATE NOCASE ASC, "
                + MediaStore.Audio.Media.ALBUM + " COLLATE NOCASE ASC, "
                + MediaStore.Audio.Media.TRACK + " ASC, "
                + MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

        Cursor cursor = null;
        try {
            cursor = queryAudio(context, null, sortOrder, true);
            if (cursor == null) return songs;

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String title = getString(cursor, MediaStore.Audio.Media.TITLE);
                String artist = getString(cursor, MediaStore.Audio.Media.ARTIST);
                String album = getString(cursor, MediaStore.Audio.Media.ALBUM);
                long duration = getLong(cursor, MediaStore.Audio.Media.DURATION);
                String path = getString(cursor, MediaStore.Audio.Media.DATA);
                String displayName = getString(cursor, MediaStore.Audio.Media.DISPLAY_NAME);
                String mimeType = getString(cursor, MediaStore.Audio.Media.MIME_TYPE);
                String relativePath = getString(cursor, COL_RELATIVE_PATH);
                long albumId = getLong(cursor, MediaStore.Audio.Media.ALBUM_ID);
                long mediaStoreTrack = getLong(cursor, MediaStore.Audio.Media.TRACK);
                long dateAddedSeconds = getLong(cursor, MediaStore.Audio.Media.DATE_ADDED);

                if (!isLikelyPlayableAudio(path, displayName, mimeType, duration)) continue;
                if (enforceRoot && !matchesRootWhenPossible(path, relativePath, activeRoot)) continue;

                Uri contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                String fileName = deriveFileName(path, displayName, title);
                String relative = deriveRelativePath(path, activeRoot, fileName, relativePath);
                String absolutePath = deriveAbsolutePath(path, activeRoot, relative);
                TrackMetadata metadata = TrackMetadata.read(context, contentUri, path, displayName);
                String finalTitle = prefer(metadata.title, title, stripExtension(fileName));
                String finalArtist = prefer(metadata.artist, metadata.albumArtist, artist, inferArtistFromPath(relative));
                String finalAlbum = prefer(metadata.album, album, inferAlbumFromPath(relative));
                long finalDuration = metadata.durationMs > 0 ? metadata.durationMs : duration;
                String mediaStoreArt = albumId > 0 ? ContentUris.withAppendedId(ALBUM_ART_BASE, albumId).toString() : "";
                String folderArt = findFilesystemSidecarArt(absolutePath);
                String finalArt = prefer(metadata.albumArtUri, folderArt, mediaStoreArt);

                int finalTrack = metadata.trackNumber > 0 ? metadata.trackNumber : normalizeTrackNumber(mediaStoreTrack);
                songs.add(new Song(id, finalTitle, finalArtist, finalAlbum, finalDuration,
                        contentUri.toString(), finalArt, displayName,
                        fileName, absolutePath, relative, finalTrack, metadata.genre, metadata.year, dateAddedSeconds));
            }
        } catch (Exception ignored) {
            // Keep the UI alive even if a specific device/provider behaves weirdly.
        } finally {
            if (cursor != null) cursor.close();
        }
        return songs;
    }


    private static void scanDocumentTree(Context context, Uri treeUri, String documentId,
                                         String relativePrefix, ArrayList<Song> out, int depth) {
        if (context == null || treeUri == null || documentId == null || depth > 24 || out.size() > 30000) return;
        Cursor cursor = null;
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
            cursor = context.getContentResolver().query(childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_SIZE,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    }, null, null, null);
            if (cursor == null) return;

            ArrayList<DocumentChild> children = new ArrayList<>();
            String folderArtUri = "";
            int bestArtRank = Integer.MAX_VALUE;
            while (cursor.moveToNext()) {
                String childId = getString(cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                String name = getString(cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                String mime = getString(cursor, DocumentsContract.Document.COLUMN_MIME_TYPE);
                long lastModifiedMs = getLong(cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (childId.length() == 0 || name.length() == 0) continue;

                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                DocumentChild child = new DocumentChild(childId, name, mime, childUri, lastModifiedMs);
                children.add(child);

                int artRank = sidecarArtRank(name, mime);
                if (artRank < bestArtRank) {
                    bestArtRank = artRank;
                    folderArtUri = childUri.toString();
                }
            }

            for (DocumentChild child : children) {
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(child.mime)) {
                    String nextPrefix = relativePrefix.length() == 0 ? child.name : relativePrefix + "/" + child.name;
                    scanDocumentTree(context, treeUri, child.id, nextPrefix, out, depth + 1);
                }
            }

            for (DocumentChild child : children) {
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(child.mime)) continue;
                if (!isLikelyPlayableAudio("", child.name, child.mime, 0L)) continue;

                String title = stripExtension(child.name);
                String relative = relativePrefix.length() == 0 ? child.name : relativePrefix + "/" + child.name;
                String[] guessed = inferArtistAlbumFromRelative(relativePrefix);
                TrackMetadata metadata = TrackMetadata.read(context, child.uri, "", child.name);
                String finalTitle = prefer(metadata.title, title);
                String finalArtist = prefer(metadata.artist, metadata.albumArtist, guessed[0]);
                String finalAlbum = prefer(metadata.album, guessed[1]);
                String finalArt = prefer(metadata.albumArtUri, folderArtUri);
                out.add(new Song(-1000000L - out.size(), finalTitle, finalArtist, finalAlbum, metadata.durationMs,
                        child.uri.toString(), finalArt, child.name, child.name, "", relative,
                        metadata.trackNumber, metadata.genre, metadata.year, child.lastModifiedMs > 0 ? child.lastModifiedMs / 1000L : 0L));
            }
        } catch (Exception ignored) {
            // Some providers restrict folders mid-tree. Skip the bad branch and keep scanning.
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static class DocumentChild {
        final String id;
        final String name;
        final String mime;
        final Uri uri;
        final long lastModifiedMs;

        DocumentChild(String id, String name, String mime, Uri uri, long lastModifiedMs) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.mime = mime == null ? "" : mime;
            this.uri = uri;
            this.lastModifiedMs = Math.max(0L, lastModifiedMs);
        }
    }

    private static String findFilesystemSidecarArt(String audioAbsolutePath) {
        try {
            if (audioAbsolutePath == null || audioAbsolutePath.trim().length() == 0) return "";
            File songFile = new File(audioAbsolutePath);
            File parent = songFile.getParentFile();
            if (parent == null || !parent.exists() || !parent.canRead()) return "";
            File[] files = parent.listFiles();
            if (files == null) return "";

            File best = null;
            int bestRank = Integer.MAX_VALUE;
            for (File candidate : files) {
                if (candidate == null || !candidate.isFile()) continue;
                int rank = sidecarArtRank(candidate.getName(), "");
                if (rank < bestRank) {
                    bestRank = rank;
                    best = candidate;
                }
            }
            return best == null ? "" : Uri.fromFile(best).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int sidecarArtRank(String name, String mime) {
        if (name == null) return Integer.MAX_VALUE;
        String lower = name.trim().toLowerCase(Locale.US);
        if (!hasImageExtension(lower) && (mime == null || !mime.toLowerCase(Locale.US).startsWith("image/"))) {
            return Integer.MAX_VALUE;
        }

        String base = lower;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);

        if ("albumartsmall".equals(base)) return 0;
        if (base.startsWith("albumart") && base.contains("small")) return 1;
        if ("folder".equals(base)) return 2;
        if ("cover".equals(base)) return 3;
        if ("front".equals(base)) return 4;
        if ("albumart".equals(base)) return 5;
        if (base.startsWith("albumart")) return 6;
        if ("album".equals(base)) return 7;
        return Integer.MAX_VALUE;
    }

    private static boolean hasImageExtension(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.US);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".webp");
    }

    private static String[] inferArtistAlbumFromRelative(String relativePrefix) {
        String artist = "Unknown Artist";
        String album = "Unknown Album";
        if (relativePrefix == null || relativePrefix.trim().length() == 0) return new String[]{artist, album};
        String[] parts = stripTrailingSlash(relativePrefix.replace('\\', '/')).split("/");
        ArrayList<String> clean = new ArrayList<>();
        for (String part : parts) {
            if (part != null && part.trim().length() > 0) clean.add(part.trim());
        }
        if (clean.size() >= 1) album = clean.get(clean.size() - 1);
        if (clean.size() >= 2) artist = clean.get(clean.size() - 2);
        if ("music".equalsIgnoreCase(album)) album = "Unknown Album";
        if ("music".equalsIgnoreCase(artist)) artist = "Unknown Artist";
        return new String[]{artist, album};
    }

    private static ArrayList<Song> scanFilesystem(Context context, String activeRoot) {
        ArrayList<Song> out = new ArrayList<>();
        File root = new File(activeRoot);
        scanFilesystemInto(context, root, cleanRoot(activeRoot), out, 0);
        return sorted(out);
    }

    private static void scanFilesystemInto(Context context, File file, String root, ArrayList<Song> out, int depth) {
        if (file == null || depth > 18 || out.size() > 20000) return;
        try {
            if (!file.exists() || !file.canRead()) return;
            if (file.isFile()) {
                String name = file.getName();
                if (!hasAudioExtension(name.toLowerCase(Locale.US))) return;

                String title = stripExtension(name);
                File albumDir = file.getParentFile();
                File artistDir = albumDir == null ? null : albumDir.getParentFile();
                String album = albumDir == null ? "Unknown Album" : albumDir.getName();
                String artist = artistDir == null ? "Unknown Artist" : artistDir.getName();
                if ("music".equalsIgnoreCase(album)) album = "Unknown Album";
                if ("music".equalsIgnoreCase(artist)) artist = "Unknown Artist";

                String absolute = file.getAbsolutePath();
                String relative = deriveRelativePath(absolute, root, name, "");
                Uri fileUri = Uri.fromFile(file);
                TrackMetadata metadata = TrackMetadata.read(context, fileUri, absolute, name);
                String finalTitle = prefer(metadata.title, title);
                String finalArtist = prefer(metadata.artist, metadata.albumArtist, artist);
                String finalAlbum = prefer(metadata.album, album);
                String folderArt = findFilesystemSidecarArt(absolute);
                String finalArt = prefer(metadata.albumArtUri, folderArt);
                out.add(new Song(-1L - out.size(), finalTitle, finalArtist, finalAlbum, metadata.durationMs,
                        fileUri.toString(), finalArt, name, name, absolute, relative,
                        metadata.trackNumber, metadata.genre, metadata.year, file.lastModified() > 0 ? file.lastModified() / 1000L : 0L));
                return;
            }

            File[] children = file.listFiles();
            if (children == null) return;
            for (File child : children) scanFilesystemInto(context, child, root, out, depth + 1);
        } catch (Exception ignored) {
            // Some Android builds throw when touching protected folders. Skip and keep scanning.
        }
    }

    private static ArrayList<Song> sorted(ArrayList<Song> songs) {
        Collections.sort(songs, new Comparator<Song>() {
            @Override public int compare(Song a, Song b) {
                int artist = safe(a.artist).compareToIgnoreCase(safe(b.artist));
                if (artist != 0) return artist;
                int album = safe(a.album).compareToIgnoreCase(safe(b.album));
                if (album != 0) return album;
                int trackA = a.trackNumber <= 0 ? Integer.MAX_VALUE : a.trackNumber;
                int trackB = b.trackNumber <= 0 ? Integer.MAX_VALUE : b.trackNumber;
                if (trackA != trackB) return trackA - trackB;
                int title = safe(a.title).compareToIgnoreCase(safe(b.title));
                if (title != 0) return title;
                return safe(a.fileName).compareToIgnoreCase(safe(b.fileName));
            }
        });
        return songs;
    }

    private static String prefer(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String cleaned = cleanMetadataText(value);
            if (cleaned.length() > 0) return cleaned;
        }
        return "";
    }

    private static String cleanMetadataText(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() == 0 || "<unknown>".equalsIgnoreCase(trimmed)) return "";
        return trimmed;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int normalizeTrackNumber(long raw) {
        if (raw <= 0) return 0;
        long track = raw;
        if (track > 1000) track = track % 1000;
        if (track <= 0 || track > Integer.MAX_VALUE) return 0;
        return (int) track;
    }

    private static String inferArtistFromPath(String relativePath) {
        String[] guessed = inferArtistAlbumFromRelative(parentPath(relativePath));
        return guessed[0];
    }

    private static String inferAlbumFromPath(String relativePath) {
        String[] guessed = inferArtistAlbumFromRelative(parentPath(relativePath));
        return guessed[1];
    }

    private static String parentPath(String relativePath) {
        if (relativePath == null) return "";
        String normalized = stripTrailingSlash(relativePath.replace('\\', '/'));
        int slash = normalized.lastIndexOf('/');
        if (slash <= 0) return "";
        return normalized.substring(0, slash);
    }

    private static String stripExtension(String name) {
        if (name == null) return "Unknown Title";
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name;
        return name.substring(0, dot);
    }

    private static Cursor queryAudio(Context context, String selection, String sortOrder, boolean richProjection) {
        try {
            return context.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    buildProjection(richProjection),
                    selection,
                    null,
                    sortOrder
            );
        } catch (Exception firstFailure) {
            if (!richProjection) return null;
            try {
                return context.getContentResolver().query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        buildProjection(false),
                        selection,
                        null,
                        sortOrder
                );
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static String[] buildProjection(boolean rich) {
        ArrayList<String> cols = new ArrayList<>();
        cols.add(MediaStore.Audio.Media._ID);
        cols.add(MediaStore.Audio.Media.TITLE);
        cols.add(MediaStore.Audio.Media.ARTIST);
        cols.add(MediaStore.Audio.Media.ALBUM);
        cols.add(MediaStore.Audio.Media.DURATION);
        cols.add(MediaStore.Audio.Media.DISPLAY_NAME);
        cols.add(MediaStore.Audio.Media.ALBUM_ID);
        cols.add(MediaStore.Audio.Media.TRACK);
        cols.add(MediaStore.Audio.Media.MIME_TYPE);
        cols.add(MediaStore.Audio.Media.DATE_ADDED);
        if (rich) {
            cols.add(MediaStore.Audio.Media.DATA);
            if (Build.VERSION.SDK_INT >= 29) cols.add(COL_RELATIVE_PATH);
        }
        return cols.toArray(new String[0]);
    }

    private static String getString(Cursor cursor, String columnName) {
        try {
            int index = cursor.getColumnIndex(columnName);
            if (index < 0 || cursor.isNull(index)) return "";
            String value = cursor.getString(index);
            return value == null ? "" : value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long getLong(Cursor cursor, String columnName) {
        try {
            int index = cursor.getColumnIndex(columnName);
            if (index < 0 || cursor.isNull(index)) return 0L;
            return cursor.getLong(index);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static boolean isLikelyPlayableAudio(String path, String displayName, String mimeType, long duration) {
        String name = firstNonEmpty(displayName, path).toLowerCase(Locale.US);
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.US);

        if (mime.startsWith("audio/")) return true;
        if (hasAudioExtension(name)) return true;

        // Some WAV/PCM files get odd provider metadata. Duration > 0 inside MediaStore.Audio is
        // a reasonable last-resort signal without depending on IS_MUSIC.
        return duration > 0;
    }

    private static boolean hasAudioExtension(String value) {
        return value.endsWith(".mp3")
                || value.endsWith(".wav")
                || value.endsWith(".wave")
                || value.endsWith(".flac")
                || value.endsWith(".m4a")
                || value.endsWith(".aac")
                || value.endsWith(".ogg")
                || value.endsWith(".opus")
                || value.endsWith(".wma")
                || value.endsWith(".aiff")
                || value.endsWith(".aif")
                || value.endsWith(".alac")
                || value.endsWith(".amr")
                || value.endsWith(".mid")
                || value.endsWith(".midi");
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && a.trim().length() > 0) return a.trim();
        if (b != null && b.trim().length() > 0) return b.trim();
        return "";
    }

    private static void addCandidate(HashMap<String, Integer> candidates, String value) {
        String key = cleanRoot(value);
        if (key.length() == 0) return;
        Integer count = candidates.get(key);
        candidates.put(key, count == null ? 1 : count + 1);
    }

    private static String findMusicRootFromAbsolutePath(String path) {
        if (path == null || path.trim().length() == 0) return null;
        String normalized = aliasStoragePath(path);
        String[] parts = normalized.split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part.length() == 0) continue;
            current.append('/').append(part);
            if ("music".equalsIgnoreCase(part)) return current.toString();
        }
        return null;
    }

    private static String findMusicRootFromRelativePath(String relativePath) {
        if (relativePath == null || relativePath.trim().length() == 0) return null;
        String normalized = stripTrailingSlash(relativePath.replace('\\', '/'));
        String[] parts = normalized.split("/");
        for (String part : parts) {
            if ("music".equalsIgnoreCase(part)) return defaultMusicDirectory().getAbsolutePath();
        }
        return null;
    }

    private static boolean matchesRootWhenPossible(String path, String relativePath, String rootFolder) {
        boolean checkedAbsolute = path != null && path.trim().length() > 0;
        if (checkedAbsolute && isInsideRoot(path, rootFolder)) return true;

        boolean checkedRelative = relativePath != null && relativePath.trim().length() > 0;
        if (checkedRelative && relativePathMatchesRoot(relativePath, rootFolder)) return true;

        // On newer Android builds some providers hide DATA and only partially expose path-ish metadata.
        // If we truly cannot evaluate the folder, keep the song instead of showing an empty library.
        if (!checkedAbsolute && !checkedRelative) return true;
        return false;
    }

    private static boolean isInsideRoot(String path, String rootFolder) {
        String pathNorm = aliasStoragePath(path);
        String rootNorm = aliasStoragePath(rootFolder);
        if (pathNorm.length() == 0 || rootNorm.length() == 0) return false;
        return pathNorm.equals(rootNorm) || pathNorm.startsWith(rootNorm + "/");
    }

    private static boolean relativePathMatchesRoot(String relativePath, String rootFolder) {
        String rel = stripTrailingSlash(relativePath.replace('\\', '/')).toLowerCase(Locale.US);
        String root = mediaRelativeRoot(rootFolder).toLowerCase(Locale.US);
        if (root.length() == 0) return false;
        return rel.equals(root) || rel.startsWith(root + "/");
    }

    private static String mediaRelativeRoot(String rootFolder) {
        String normalized = aliasStoragePath(rootFolder);
        String lower = normalized.toLowerCase(Locale.US);
        int storageIndex = lower.indexOf("/storage/emulated/0/");
        if (storageIndex >= 0) return normalized.substring(storageIndex + "/storage/emulated/0/".length());

        int sdcardIndex = lower.indexOf("/sdcard/");
        if (sdcardIndex >= 0) return normalized.substring(sdcardIndex + "/sdcard/".length());

        int musicIndex = lower.indexOf("/music");
        if (musicIndex >= 0) return normalized.substring(musicIndex + 1);
        return "";
    }

    private static String deriveFileName(String path, String displayName, String title) {
        if (displayName != null && displayName.trim().length() > 0) return displayName.trim();
        if (path != null && path.length() > 0) return new File(path).getName();
        if (title != null && title.trim().length() > 0) return title.trim();
        return "Unknown File";
    }

    private static String deriveRelativePath(String path, String rootFolder, String fileName, String mediaRelativePath) {
        if (path != null && path.length() > 0) {
            String normalized = path.replace('\\', '/');
            String pathNorm = aliasStoragePath(normalized);
            String rootNorm = aliasStoragePath(rootFolder);
            if (pathNorm.equals(rootNorm)) return fileName;
            if (pathNorm.startsWith(rootNorm + "/")) return pathNorm.substring(rootNorm.length() + 1);

            int musicIndex = normalized.toLowerCase(Locale.US).indexOf("/music/");
            if (musicIndex >= 0) return normalized.substring(musicIndex + 7);
            int slash = normalized.lastIndexOf('/');
            if (slash >= 0 && slash < normalized.length() - 1) return normalized.substring(slash + 1);
        }

        if (mediaRelativePath != null && mediaRelativePath.trim().length() > 0) {
            String rel = stripTrailingSlash(mediaRelativePath.replace('\\', '/'));
            String root = mediaRelativeRoot(rootFolder);
            if (root.length() > 0 && rel.toLowerCase(Locale.US).startsWith(root.toLowerCase(Locale.US))) {
                rel = rel.substring(root.length());
                while (rel.startsWith("/")) rel = rel.substring(1);
            }
            return rel.length() == 0 ? fileName : rel + "/" + fileName;
        }
        return fileName;
    }

    private static String deriveAbsolutePath(String path, String rootFolder, String relativePath) {
        if (path != null && path.trim().length() > 0) return path;
        if (relativePath == null || relativePath.length() == 0) return "";
        return cleanRoot(rootFolder) + "/" + relativePath;
    }

    private static String aliasStoragePath(String value) {
        String out = stripTrailingSlash((value == null ? "" : value.trim()).replace('\\', '/'));
        if (out.startsWith("/sdcard/")) out = "/storage/emulated/0/" + out.substring(8);
        else if (out.equals("/sdcard")) out = "/storage/emulated/0";
        else if (out.startsWith("/mnt/sdcard/")) out = "/storage/emulated/0/" + out.substring(12);
        else if (out.equals("/mnt/sdcard")) out = "/storage/emulated/0";
        return out;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) return "";
        String out = value.trim();
        while (out.length() > 1 && out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
