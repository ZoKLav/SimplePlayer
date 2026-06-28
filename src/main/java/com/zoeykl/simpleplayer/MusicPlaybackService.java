package com.zoeykl.simpleplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Build;
import android.os.IBinder;
import android.view.KeyEvent;
import android.service.media.MediaBrowserService;

import java.io.InputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MusicPlaybackService extends MediaBrowserService {
    private static final int NOTIFICATION_ID = 2701;
    private static final String CHANNEL_ID = "playback";
    private static final String ACTION_TOGGLE = "com.zoeykl.simpleplayer.TOGGLE";
    private static final String ACTION_PLAY = "com.zoeykl.simpleplayer.PLAY";
    private static final String ACTION_PAUSE = "com.zoeykl.simpleplayer.PAUSE";
    private static final String ACTION_NEXT = "com.zoeykl.simpleplayer.NEXT";
    private static final String ACTION_PREVIOUS = "com.zoeykl.simpleplayer.PREVIOUS";

    private static final String PREFS = "prefs";
    private static final String KEY_MUSIC_FOLDER = "music_folder";
    private static final String KEY_MUSIC_TREE_URI = "music_tree_uri";
    private static final String KEY_SHUFFLE_SEED = "shuffle_seed";
    private static final String KEY_ADAPTIVE_SHUFFLE = "adaptive_shuffle";

    private static final String AUTO_ROOT = "auto:root";
    private static final String AUTO_SONGS = "auto:songs";
    private static final String AUTO_ALBUMS = "auto:albums";
    private static final String AUTO_ARTISTS = "auto:artists";
    private static final String AUTO_PLAYLISTS = "auto:playlists";
    private static final String AUTO_ALBUM_PREFIX = "auto:album:";
    private static final String AUTO_ARTIST_PREFIX = "auto:artist:";
    private static final String AUTO_PLAYLIST_PREFIX = "auto:playlist:";
    private static final String AUTO_SONG_PREFIX = "auto:song:";

    public class LocalBinder extends Binder {
        public MusicPlaybackService getService() {
            return MusicPlaybackService.this;
        }
    }

    public static class SimplePlaybackState {
        public Song song;
        public boolean playing;
        public boolean shuffle;
        public boolean adaptiveShuffle;
        public int positionMs;
        public int durationMs;
        public int queueIndex;
        public int queueSize;
        public long shuffleSeed;
    }

    private final IBinder binder = new LocalBinder();
    private final ArrayList<Song> library = new ArrayList<>();
    private final ArrayList<Song> queue = new ArrayList<>();
    private final ArrayList<Song> queueBase = new ArrayList<>();
    private MediaPlayer player;
    private MediaSession mediaSession;
    private Song currentSong;
    private int queueIndex = -1;
    private boolean shuffleEnabled = false;
    private boolean adaptiveShuffleEnabled = false;
    private long shuffleSeed = 0x5eed51a7L;
    private boolean preparing = false;
    private boolean sessionTokenSet = false;
    private int accent = 0xFFE53935;

    @Override public void onCreate() {
        super.onCreate();
        createChannelIfNeeded();
        setupMediaSession();
    }

    @Override public IBinder onBind(Intent intent) {
        if (intent != null && MediaBrowserService.SERVICE_INTERFACE.equals(intent.getAction())) {
            return super.onBind(intent);
        }
        return binder;
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        ensureAutoLibraryLoaded();
        return new BrowserRoot(AUTO_ROOT, null);
    }

    @Override public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        ensureAutoLibraryLoaded();
        result.sendResult(autoChildrenFor(parentId));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            handleAction(intent.getAction());
        }
        return START_STICKY;
    }

    private void handleAction(String action) {
        if (ACTION_TOGGLE.equals(action)) togglePlayPause();
        else if (ACTION_PLAY.equals(action)) play();
        else if (ACTION_PAUSE.equals(action)) pause();
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREVIOUS.equals(action)) previous();
    }

    private void setupMediaSession() {
        if (Build.VERSION.SDK_INT < 21 || mediaSession != null) return;
        mediaSession = new MediaSession(this, "SimplePlayer");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seekTo((int) Math.max(0, Math.min(Integer.MAX_VALUE, pos))); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) { playFromAutoMediaId(mediaId); }
            @Override public void onPlayFromSearch(String query, Bundle extras) { playFirstSearchMatch(query); }
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent event = mediaButtonIntent == null ? null : (KeyEvent) mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return super.onMediaButtonEvent(mediaButtonIntent);
                int keyCode = event.getKeyCode();
                if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    togglePlayPause();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                    play();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                    pause();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                    next();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                    previous();
                    return true;
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });
        mediaSession.setActive(true);
        if (!sessionTokenSet) {
            try {
                setSessionToken(mediaSession.getSessionToken());
                sessionTokenSet = true;
            } catch (Exception ignored) {}
        }
        updateMediaSessionState();
    }

    public void setAccent(int accentColor) {
        accent = 0xFF000000 | (accentColor & 0x00FFFFFF);
        updateNotificationOnly();
    }

    public void setLibrary(List<Song> songs) {
        library.clear();
        if (songs != null) library.addAll(songs);
    }

    public void playSong(Song song, List<Song> contextQueue) {
        if (song == null) return;
        queue.clear();
        List<Song> base = contextQueue == null || contextQueue.size() == 0 ? library : contextQueue;
        queueBase.clear();
        queueBase.addAll(base);
        if (shuffleEnabled) {
            queue.addAll(makeShuffleQueue(base, song));
            queueIndex = 0;
        } else {
            queue.addAll(base);
            queueIndex = indexOf(queue, song);
            if (queueIndex < 0) {
                queue.add(0, song);
                queueIndex = 0;
            }
        }
        startCurrent();
    }

    public void setQueueAndPlay(List<Song> songs, int startIndex) {
        if (songs == null || songs.size() == 0) return;
        Song first = songs.get(Math.max(0, Math.min(startIndex, songs.size() - 1)));
        playSong(first, songs);
    }

    public void togglePlayPause() {
        if (isPlaying()) pause();
        else play();
    }

    public void play() {
        if (player == null) {
            if (currentSong != null) startCurrent();
            else if (queue.size() > 0) {
                if (queueIndex < 0) queueIndex = 0;
                startCurrent();
            }
            return;
        }
        try {
            player.start();
            updateMediaSessionState();
            showNotification();
        } catch (Exception ignored) {}
    }

    public void pause() {
        if (player != null) {
            try { player.pause(); } catch (Exception ignored) {}
        }
        updateMediaSessionState();
        updateNotificationOnly();
    }

    public void next() {
        advance(true);
    }

    private void advance(boolean userInitiated) {
        if (queue.size() == 0) return;
        if (userInitiated) maybeRecordQuickSkip();
        else maybeRecordGoodPlay();
        if (queueIndex < queue.size() - 1) {
            queueIndex++;
        } else if (shuffleEnabled) {
            Song old = currentSong;
            ArrayList<Song> base = queueBase.size() > 0 ? new ArrayList<>(queueBase) : (library.size() > 0 ? new ArrayList<>(library) : new ArrayList<>(queue));
            queue.clear();
            queue.addAll(makeShuffleQueue(base, old));
            queueIndex = queue.size() > 1 ? 1 : 0;
        } else {
            queueIndex = 0;
        }
        startCurrent();
    }

    public void previous() {
        if (queue.size() == 0) return;
        if (getPosition() > 3000) {
            seekTo(0);
            return;
        }
        maybeRecordQuickSkip();
        if (queueIndex > 0) queueIndex--;
        else queueIndex = queue.size() - 1;
        startCurrent();
    }

    public void seekTo(int ms) {
        if (player != null) {
            try { player.seekTo(ms); } catch (Exception ignored) {}
        }
        updateMediaSessionState();
    }

    public void setShuffleEnabled(boolean enabled) {
        if (shuffleEnabled == enabled) return;
        shuffleEnabled = enabled;
        if (currentSong != null) {
            ArrayList<Song> base = queueBase.size() > 0 ? new ArrayList<>(queueBase) : (queue.size() > 0 ? new ArrayList<>(queue) : new ArrayList<>(library));
            queue.clear();
            if (enabled) {
                queue.addAll(makeShuffleQueue(base, currentSong));
                queueIndex = 0;
            } else {
                queue.addAll(base);
                queueIndex = indexOf(queue, currentSong);
                if (queueIndex < 0) {
                    queue.add(0, currentSong);
                    queueIndex = 0;
                }
            }
        }
        updateMediaSessionState();
        updateNotificationOnly();
    }

    public void setShuffleSeed(long seed) {
        if (seed == 0L) seed = 0x5eed51a7L;
        shuffleSeed = seed;
        rebuildShuffleQueueAroundCurrentSong();
    }

    public long getShuffleSeed() {
        return shuffleSeed;
    }

    public void setAdaptiveShuffleEnabled(boolean enabled) {
        if (adaptiveShuffleEnabled == enabled) return;
        adaptiveShuffleEnabled = enabled;
        rebuildShuffleQueueAroundCurrentSong();
    }

    public boolean isAdaptiveShuffleEnabled() {
        return adaptiveShuffleEnabled;
    }

    public void resetAdaptiveShuffleWeights() {
        AdaptiveShuffleStore.reset(this);
        rebuildShuffleQueueAroundCurrentSong();
    }

    public void rebuildShuffleQueueAroundCurrentSong() {
        if (!shuffleEnabled || currentSong == null) return;
        ArrayList<Song> base = queueBase.size() > 0 ? new ArrayList<>(queueBase) : (library.size() > 0 ? new ArrayList<>(library) : new ArrayList<>(queue));
        queue.clear();
        queue.addAll(makeShuffleQueue(base, currentSong));
        queueIndex = queue.size() == 0 ? -1 : 0;
        updateMediaSessionState();
        updateNotificationOnly();
    }

    public boolean isShuffleEnabled() {
        return shuffleEnabled;
    }

    public ArrayList<Song> getQueueSnapshot() {
        return new ArrayList<>(queue);
    }

    public SimplePlaybackState getPlaybackState() {
        SimplePlaybackState state = new SimplePlaybackState();
        state.song = currentSong;
        state.playing = isPlaying();
        state.shuffle = shuffleEnabled;
        state.adaptiveShuffle = adaptiveShuffleEnabled;
        state.positionMs = getPosition();
        state.durationMs = getDuration();
        state.queueIndex = queueIndex;
        state.queueSize = queue.size();
        state.shuffleSeed = shuffleSeed;
        return state;
    }

    private ArrayList<Song> makeShuffleQueue(List<Song> base, Song first) {
        return ShuffleBag.makeQueue(base, first, shuffleSeed, AdaptiveShuffleStore.penaltiesFor(this, base), adaptiveShuffleEnabled);
    }

    private void maybeRecordQuickSkip() {
        if (!adaptiveShuffleEnabled || currentSong == null) return;
        int position = getPosition();
        if (position >= 0 && position < 15000) {
            AdaptiveShuffleStore.recordQuickSkip(this, currentSong);
        }
    }

    private void maybeRecordGoodPlay() {
        if (!adaptiveShuffleEnabled || currentSong == null) return;
        int position = getPosition();
        int duration = getDuration();
        if (position >= 60000 || (duration > 0 && position >= Math.max(30000, duration / 2))) {
            AdaptiveShuffleStore.recordGoodPlay(this, currentSong);
        }
    }

    private void startCurrent() {
        if (queueIndex < 0 || queueIndex >= queue.size()) return;
        currentSong = queue.get(queueIndex);
        releasePlayer();
        preparing = true;
        updateMediaSessionMetadata();
        updateMediaSessionState();
        showNotification();
        try {
            player = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= 21) {
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build());
            } else {
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            if (currentSong.contentUri.startsWith("file:") || currentSong.contentUri.length() == 0) {
                player.setDataSource(currentSong.absolutePath);
            } else {
                player.setDataSource(getApplicationContext(), Uri.parse(currentSong.contentUri));
            }
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    preparing = false;
                    mp.start();
                    updateMediaSessionMetadata();
                    updateMediaSessionState();
                    showNotification();
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    advance(false);
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    preparing = false;
                    advance(false);
                    return true;
                }
            });
            player.prepareAsync();
        } catch (Exception e) {
            preparing = false;
            releasePlayer();
            updateMediaSessionState();
            updateNotificationOnly();
        }
    }

    private boolean isPlaying() {
        return player != null && !preparing && player.isPlaying();
    }

    private int getPosition() {
        if (player == null || preparing) return 0;
        try { return player.getCurrentPosition(); } catch (Exception ignored) { return 0; }
    }

    private int getDuration() {
        if (player == null || preparing) return currentSong == null ? 0 : (int) currentSong.durationMs;
        try { return player.getDuration(); } catch (Exception ignored) { return currentSong == null ? 0 : (int) currentSong.durationMs; }
    }

    private int indexOf(List<Song> songs, Song target) {
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).sameFileAs(target)) return i;
        }
        return -1;
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private void showNotification() {
        if (currentSong == null) return;
        createChannelIfNeeded();
        Notification notification = buildPlaybackNotification();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void updateNotificationOnly() {
        if (currentSong == null) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildPlaybackNotification());
    }

    private Notification buildPlaybackNotification() {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, activityIntent, pendingFlags());

        boolean playing = isPlaying();
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(currentSong == null ? "SimplePlayer" : currentSong.title)
                .setContentText(currentSong == null ? "Ready" : currentSong.artist + " • " + currentSong.album)
                .setSubText(shuffleEnabled ? (adaptiveShuffleEnabled ? "Adaptive shuffle" : "Seeded shuffle") : "Queue")
                .setContentIntent(contentIntent)
                .setShowWhen(false)
                .setOngoing(playing)
                .setColor(accent);

        Bitmap art = loadAlbumArtBitmap(currentSong);
        if (art != null) builder.setLargeIcon(art);
        if (Build.VERSION.SDK_INT >= 21) {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
            builder.addAction(android.R.drawable.ic_media_previous, "Previous", serviceAction(ACTION_PREVIOUS));
            builder.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    playing ? "Pause" : "Play", serviceAction(ACTION_TOGGLE));
            builder.addAction(android.R.drawable.ic_media_next, "Next", serviceAction(ACTION_NEXT));
            Notification.MediaStyle style = new Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2);
            if (mediaSession != null) style.setMediaSession(mediaSession.getSessionToken());
            builder.setStyle(style);
            builder.setCategory(Notification.CATEGORY_TRANSPORT);
        }
        return builder.build();
    }

    private PendingIntent serviceAction(String action) {
        Intent intent = new Intent(this, MusicPlaybackService.class);
        intent.setAction(action);
        int requestCode = Math.abs(action.hashCode());
        return PendingIntent.getService(this, requestCode, intent, pendingFlags());
    }

    private int pendingFlags() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private Bitmap loadAlbumArtBitmap(Song song) {
        if (song == null || song.albumArtUri == null || song.albumArtUri.length() == 0) return null;
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(Uri.parse(song.albumArtUri));
            if (in == null) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    private void updateMediaSessionMetadata() {
        if (Build.VERSION.SDK_INT < 21 || mediaSession == null || currentSong == null) return;
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentSong.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentSong.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, currentSong.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, getDuration()));
        Bitmap art = loadAlbumArtBitmap(currentSong);
        if (art != null) builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        mediaSession.setMetadata(builder.build());
    }

    private void updateMediaSessionState() {
        if (Build.VERSION.SDK_INT < 21 || mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackState.ACTION_PLAY_FROM_SEARCH;
        int state = isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        if (preparing) state = PlaybackState.STATE_BUFFERING;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, getPosition(), isPlaying() ? 1.0f : 0.0f)
                .build());
    }


    private void ensureAutoLibraryLoaded() {
        if (library.size() > 0) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        shuffleSeed = prefs.getLong(KEY_SHUFFLE_SEED, shuffleSeed);
        adaptiveShuffleEnabled = prefs.getBoolean(KEY_ADAPTIVE_SHUFFLE, adaptiveShuffleEnabled);

        String treeUri = prefs.getString(KEY_MUSIC_TREE_URI, "");
        String folder = prefs.getString(KEY_MUSIC_FOLDER, MusicLibrary.defaultMusicDirectory().getAbsolutePath());
        String sourceKey = (treeUri != null && treeUri.trim().length() > 0)
                ? "tree:" + treeUri.trim()
                : "folder:" + MusicLibrary.cleanRoot(folder);

        LibraryCache.Result cached = LibraryCache.load(this);
        if (cached.loaded && sourceKey.equals(cached.sourceKey)) {
            library.clear();
            library.addAll(cached.songs);
            return;
        }

        ArrayList<Song> loaded = new ArrayList<>();
        if (treeUri != null && treeUri.trim().length() > 0) {
            loaded.addAll(MusicLibrary.scanTree(this, treeUri));
        }
        if (loaded.size() == 0) {
            loaded.addAll(MusicLibrary.scan(this, folder));
        }
        library.clear();
        library.addAll(loaded);
        LibraryCache.save(this, sourceKey, library);
    }

    private List<MediaBrowser.MediaItem> autoChildrenFor(String parentId) {
        ArrayList<MediaBrowser.MediaItem> items = new ArrayList<>();
        if (parentId == null || parentId.length() == 0 || AUTO_ROOT.equals(parentId)) {
            items.add(browsableItem(AUTO_SONGS, "Songs", library.size() + " songs"));
            items.add(browsableItem(AUTO_ALBUMS, "Albums", albumBuckets().size() + " albums"));
            items.add(browsableItem(AUTO_ARTISTS, "Artists", artistBuckets().size() + " artists"));
            items.add(browsableItem(AUTO_PLAYLISTS, "Playlists", PlaylistManager.listPlaylists().size() + " playlists"));
            return items;
        }

        if (AUTO_SONGS.equals(parentId)) {
            addPlayableSongs(items, AUTO_SONGS, library);
            return items;
        }

        if (AUTO_ALBUMS.equals(parentId)) {
            for (Map.Entry<String, ArrayList<Song>> entry : albumBuckets().entrySet()) {
                String[] parts = splitPair(entry.getKey());
                items.add(browsableItem(AUTO_ALBUM_PREFIX + Uri.encode(entry.getKey()),
                        parts[0], parts[1] + " • " + entry.getValue().size() + " songs"));
            }
            return items;
        }

        if (parentId.startsWith(AUTO_ALBUM_PREFIX)) {
            String key = Uri.decode(parentId.substring(AUTO_ALBUM_PREFIX.length()));
            ArrayList<Song> songs = albumBuckets().get(key);
            addPlayableSongs(items, parentId, songs == null ? Collections.<Song>emptyList() : songs);
            return items;
        }

        if (AUTO_ARTISTS.equals(parentId)) {
            ArrayList<String> names = new ArrayList<>(artistBuckets().keySet());
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            Map<String, ArrayList<Song>> artists = artistBuckets();
            for (String name : names) {
                ArrayList<Song> artistSongs = artists.get(name);
                items.add(browsableItem(AUTO_ARTIST_PREFIX + Uri.encode(name),
                        name, (artistSongs == null ? 0 : artistSongs.size()) + " songs"));
            }
            return items;
        }

        if (parentId.startsWith(AUTO_ARTIST_PREFIX)) {
            String artist = Uri.decode(parentId.substring(AUTO_ARTIST_PREFIX.length()));
            ArrayList<Song> songs = artistBuckets().get(artist);
            addPlayableSongs(items, parentId, songs == null ? Collections.<Song>emptyList() : songs);
            return items;
        }

        if (AUTO_PLAYLISTS.equals(parentId)) {
            for (PlaylistManager.PlaylistInfo playlist : PlaylistManager.listPlaylists()) {
                PlaylistManager.PlaylistLoadResult loaded = PlaylistManager.load(playlist.file, library);
                items.add(browsableItem(AUTO_PLAYLIST_PREFIX + Uri.encode(playlist.name),
                        playlist.name, loaded.songs.size() + " songs"));
            }
            return items;
        }

        if (parentId.startsWith(AUTO_PLAYLIST_PREFIX)) {
            String name = Uri.decode(parentId.substring(AUTO_PLAYLIST_PREFIX.length()));
            addPlayableSongs(items, parentId, playlistSongs(name));
            return items;
        }

        return items;
    }

    private void addPlayableSongs(ArrayList<MediaBrowser.MediaItem> items, String containerId, List<Song> songs) {
        if (songs == null) return;
        for (Song song : songs) {
            items.add(playableSongItem(containerId, song));
        }
    }

    private MediaBrowser.MediaItem browsableItem(String mediaId, String title, String subtitle) {
        MediaDescription description = new MediaDescription.Builder()
                .setMediaId(mediaId)
                .setTitle(title == null ? "" : title)
                .setSubtitle(subtitle == null ? "" : subtitle)
                .build();
        return new MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowser.MediaItem playableSongItem(String containerId, Song song) {
        MediaDescription.Builder builder = new MediaDescription.Builder()
                .setMediaId(songMediaId(containerId, song))
                .setTitle(song.title)
                .setSubtitle(song.artist + " • " + song.album);
        if (song.albumArtUri != null && song.albumArtUri.length() > 0) {
            try { builder.setIconUri(Uri.parse(song.albumArtUri)); } catch (Exception ignored) {}
        }
        return new MediaBrowser.MediaItem(builder.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE);
    }

    private void playFromAutoMediaId(String mediaId) {
        ensureAutoLibraryLoaded();
        AutoSelection selection = autoSelectionForSongMediaId(mediaId);
        if (selection == null || selection.song == null) return;
        playSong(selection.song, selection.queue);
    }

    private void playFirstSearchMatch(String query) {
        ensureAutoLibraryLoaded();
        if (library.size() == 0) return;
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.length() == 0) {
            setQueueAndPlay(library, 0);
            return;
        }
        for (int i = 0; i < library.size(); i++) {
            Song song = library.get(i);
            String haystack = (song.title + " " + song.artist + " " + song.album + " " + song.fileName).toLowerCase();
            if (haystack.contains(q)) {
                setQueueAndPlay(library, i);
                return;
            }
        }
        setQueueAndPlay(library, 0);
    }

    private AutoSelection autoSelectionForSongMediaId(String mediaId) {
        if (mediaId == null || !mediaId.startsWith(AUTO_SONG_PREFIX)) return null;
        String rest = mediaId.substring(AUTO_SONG_PREFIX.length());
        int split = rest.indexOf(':');
        if (split < 0) return null;
        String containerId = Uri.decode(rest.substring(0, split));
        String key = Uri.decode(rest.substring(split + 1));
        ArrayList<Song> context = songsForAutoContainer(containerId);
        Song match = findSongByStableKey(context, key);
        if (match == null) match = findSongByStableKey(library, key);
        if (match == null) return null;
        return new AutoSelection(match, context.size() > 0 ? context : new ArrayList<>(library));
    }

    private ArrayList<Song> songsForAutoContainer(String containerId) {
        ensureAutoLibraryLoaded();
        if (containerId == null || containerId.length() == 0 || AUTO_SONGS.equals(containerId)) return new ArrayList<>(library);
        if (containerId.startsWith(AUTO_ALBUM_PREFIX)) {
            String key = Uri.decode(containerId.substring(AUTO_ALBUM_PREFIX.length()));
            ArrayList<Song> found = albumBuckets().get(key);
            return found == null ? new ArrayList<Song>() : new ArrayList<>(found);
        }
        if (containerId.startsWith(AUTO_ARTIST_PREFIX)) {
            String artist = Uri.decode(containerId.substring(AUTO_ARTIST_PREFIX.length()));
            ArrayList<Song> found = artistBuckets().get(artist);
            return found == null ? new ArrayList<Song>() : new ArrayList<>(found);
        }
        if (containerId.startsWith(AUTO_PLAYLIST_PREFIX)) {
            String playlist = Uri.decode(containerId.substring(AUTO_PLAYLIST_PREFIX.length()));
            return playlistSongs(playlist);
        }
        return new ArrayList<>(library);
    }

    private Song findSongByStableKey(List<Song> songs, String stableKey) {
        if (songs == null || stableKey == null) return null;
        for (Song song : songs) {
            if (song.stableKey().equals(stableKey)) return song;
        }
        return null;
    }

    private String songMediaId(String containerId, Song song) {
        return AUTO_SONG_PREFIX + Uri.encode(containerId == null ? AUTO_SONGS : containerId) + ":" + Uri.encode(song.stableKey());
    }

    private Map<String, ArrayList<Song>> albumBuckets() {
        LinkedHashMap<String, ArrayList<Song>> albums = new LinkedHashMap<>();
        for (Song song : library) {
            String key = song.album + "\u001f" + song.artist;
            ArrayList<Song> bucket = albums.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                albums.put(key, bucket);
            }
            bucket.add(song);
        }
        return albums;
    }

    private Map<String, ArrayList<Song>> artistBuckets() {
        LinkedHashMap<String, ArrayList<Song>> artists = new LinkedHashMap<>();
        for (Song song : library) {
            ArrayList<Song> bucket = artists.get(song.artist);
            if (bucket == null) {
                bucket = new ArrayList<>();
                artists.put(song.artist, bucket);
            }
            bucket.add(song);
        }
        return artists;
    }

    private String[] splitPair(String key) {
        String[] parts = key == null ? new String[0] : key.split("\u001f", 2);
        return new String[]{parts.length > 0 ? parts[0] : "Unknown Album", parts.length > 1 ? parts[1] : "Unknown Artist"};
    }

    private ArrayList<Song> playlistSongs(String playlistName) {
        for (PlaylistManager.PlaylistInfo playlist : PlaylistManager.listPlaylists()) {
            if (playlist.name.equals(playlistName)) {
                return PlaylistManager.load(playlist.file, library).songs;
            }
        }
        return new ArrayList<>();
    }

    private static class AutoSelection {
        final Song song;
        final ArrayList<Song> queue;
        AutoSelection(Song song, ArrayList<Song> queue) {
            this.song = song;
            this.queue = queue;
        }
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Music playback notification");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        releasePlayer();
        if (Build.VERSION.SDK_INT >= 21 && mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
