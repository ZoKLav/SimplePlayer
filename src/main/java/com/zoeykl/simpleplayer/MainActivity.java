// UI code built by hand because this app has to run back to Nougat, where elegance goes to negotiate.
package com.zoeykl.simpleplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 4101;
    private static final int REQUEST_TREE = 4102;
    private static final String PREFS = "prefs";
    private static final String KEY_ACCENT = "accent";
    private static final String KEY_MUSIC_FOLDER = "music_folder";
    private static final String KEY_MUSIC_FOLDER_MANUAL = "music_folder_manual";
    private static final String KEY_MUSIC_TREE_URI = "music_tree_uri";
    private static final String KEY_SHUFFLE_SEED = "shuffle_seed";
    private static final String KEY_ADAPTIVE_SHUFFLE = "adaptive_shuffle";
    private static final String PERMISSION_READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE";
    private static final String PERMISSION_WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE";
    // String literal so older compile SDKs do not burst into flames over a permission they have never met.
    private static final String PERMISSION_READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO";

    private static final int BG = Color.rgb(18, 19, 23);
    private static final int BG_BOTTOM = Color.rgb(10, 11, 14);
    private static final int SURFACE = Color.rgb(31, 33, 39);
    private static final int SURFACE_DARK = Color.rgb(23, 24, 29);
    private static final int SURFACE_RAISED = Color.rgb(45, 48, 56);
    private static final int SURFACE_GLOW = Color.rgb(58, 61, 70);
    private static final int STROKE = Color.rgb(68, 72, 82);
    private static final int STROKE_SOFT = Color.rgb(43, 46, 54);
    private static final int TEXT_MUTED = Color.rgb(176, 181, 193);
    private static final int TEXT_DIM = Color.rgb(132, 138, 152);

    private final Handler handler = new Handler();
    private final ArrayList<Song> songs = new ArrayList<>();
    private MusicPlaybackService service;
    private boolean bound = false;
    private int accent = Color.rgb(229, 57, 53);
    private String musicFolder = "";
    private String musicTreeUri = "";
    private long shuffleSeed = 0x5eed51a7L;
    private boolean adaptiveShuffle = false;
    private String currentTab = "Songs";
    private LinearLayout root;
    private FrameLayout content;
    private SeekBar progressBar;
    private TextView elapsedLabel;
    private TextView durationLabel;
    private TextView queueInfoLabel;
    private TextView seedInfoLabel;
    private Button playerPlayButton;
    private Button playerShuffleButton;
    private Button nowPlayingPlayButton;
    private boolean userSeeking = false;
    private boolean libraryScanRunning = false;
    private String lastRenderedSongKey = "";
    private int lastRenderedQueueIndex = Integer.MIN_VALUE;
    private int lastRenderedQueueSize = Integer.MIN_VALUE;
    private boolean lastRenderedShuffle = false;
    private boolean lastRenderedAdaptiveShuffle = false;
    private long lastRenderedShuffleSeed = Long.MIN_VALUE;

    // Half-second UI tick: not fancy, just enough to stop the seek bar from cosplaying a screenshot.
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updatePlaybackUi();
            handler.postDelayed(this, 500);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            MusicPlaybackService.LocalBinder local = (MusicPlaybackService.LocalBinder) binder;
            service = local.getService();
            service.setLibrary(songs);
            service.setShuffleSeed(shuffleSeed);
            service.setAdaptiveShuffleEnabled(adaptiveShuffle);
            service.setAccent(accent);
            bound = true;
            render();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        accent = prefs.getInt(KEY_ACCENT, accent);
        musicFolder = MusicLibrary.cleanRoot(prefs.getString(KEY_MUSIC_FOLDER, MusicLibrary.defaultMusicDirectory().getAbsolutePath()));
        musicTreeUri = prefs.getString(KEY_MUSIC_TREE_URI, "");
        shuffleSeed = prefs.getLong(KEY_SHUFFLE_SEED, shuffleSeed);
        adaptiveShuffle = prefs.getBoolean(KEY_ADAPTIVE_SHUFFLE, false);
        boolean cacheLoaded = loadLibraryCache();
        startService(new Intent(this, MusicPlaybackService.class));
        bindService(new Intent(this, MusicPlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        if (hasPickedFolder()) {
            if (!cacheLoaded) loadLibrary(false);
        } else if (hasAudioPermission()) {
            if (!cacheLoaded) {
                maybeAutoDetectMusicFolder();
                loadLibrary(false);
            }
        } else {
            requestAudioPermission();
        }
        render();
        handler.post(ticker);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        if (bound) unbindService(connection);
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        render();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO && hasAudioPermission()) {
            maybeAutoDetectMusicFolder();
            loadLibrary(true);
            render();
        } else if (requestCode == REQUEST_AUDIO) {
            toast("Storage/audio permission is needed to scan local music.");
        }
    }


    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception alsoIgnored) {}
            }
            musicTreeUri = uri.toString();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_MUSIC_TREE_URI, musicTreeUri)
                    .putBoolean(KEY_MUSIC_FOLDER_MANUAL, true)
                    .apply();
            currentTab = "Songs";
            render();
            loadLibrary(true);
        }
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(PERMISSION_READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(PERMISSION_READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(PERMISSION_READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }


    private boolean hasPickedFolder() {
        return musicTreeUri != null && musicTreeUri.trim().length() > 0;
    }

    private String pickedFolderLabel() {
        if (!hasPickedFolder()) return "";
        return MusicLibrary.displayNameForTree(this, musicTreeUri);
    }

    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{PERMISSION_READ_MEDIA_AUDIO}, REQUEST_AUDIO);
        } else {
            requestPermissions(new String[]{PERMISSION_READ_EXTERNAL_STORAGE, PERMISSION_WRITE_EXTERNAL_STORAGE}, REQUEST_AUDIO);
        }
    }

    // Auto-detect only until the user chooses a folder; after that, stop "helping" like a haunted assistant.
    private void maybeAutoDetectMusicFolder() {
        if (hasPickedFolder()) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_MUSIC_FOLDER_MANUAL, false)) return;
        String detected = MusicLibrary.autoDetectMusicDirectory(this);
        if (detected != null && detected.trim().length() > 0) {
            musicFolder = MusicLibrary.cleanRoot(detected);
            prefs.edit().putString(KEY_MUSIC_FOLDER, musicFolder).apply();
        }
    }

    // Cache first, scan only when asked. The Songs tab is not a background job application.
    private boolean loadLibraryCache() {
        LibraryCache.Result cached = LibraryCache.load(this);
        if (!cached.loaded) return false;
        if (!librarySourceKey().equals(cached.sourceKey)) return false;
        songs.clear();
        songs.addAll(cached.songs);
        publishLibraryToService();
        return true;
    }

    private String librarySourceKey() {
        if (hasPickedFolder()) return "tree:" + musicTreeUri.trim();
        return "folder:" + MusicLibrary.cleanRoot(musicFolder);
    }

    private void publishLibraryToService() {
        if (service != null) {
            service.setLibrary(songs);
            service.setShuffleSeed(shuffleSeed);
            service.setAdaptiveShuffleEnabled(adaptiveShuffle);
            service.setAccent(accent);
        }
    }

    private void loadLibrary() {
        loadLibrary(true);
    }

    // Heavy scan runs off the UI thread because frozen scrolling is not a design language.
    private void loadLibrary(final boolean showToast) {
        if (libraryScanRunning) {
            if (showToast) toast("Library scan already running.");
            return;
        }
        final String sourceKey = librarySourceKey();
        libraryScanRunning = true;
        if (showToast) toast("Scanning library…");
        render();
        new Thread(new Runnable() {
            @Override public void run() {
                final ArrayList<Song> loaded = new ArrayList<>();
                if (hasPickedFolder()) {
                    loaded.addAll(MusicLibrary.scanTree(MainActivity.this, musicTreeUri));
                }
                if (loaded.size() == 0) {
                    loaded.addAll(MusicLibrary.scan(MainActivity.this, musicFolder));
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        libraryScanRunning = false;
                        if (!sourceKey.equals(librarySourceKey())) return;
                        songs.clear();
                        songs.addAll(loaded);
                        LibraryCache.save(MainActivity.this, sourceKey, songs);
                        publishLibraryToService();
                        if (showToast) toast("Library refreshed: " + songs.size() + " songs");
                        render();
                    }
                });
            }
        }, "SimplePlayer library scan").start();
    }

    // Brute-force rebuilds are acceptable here because views are simple and state lives elsewhere. Mostly.
    private void render() {
        clearPlaybackViewRefs();
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(makeAppBackground());
        setContentView(root);
        buildHeader();
        buildTabs();
        content = new FrameLayout(this);
        content.setBackground(makeAppBackground());
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        showCurrentTab();
        buildNowPlayingBar();
        rememberPlaybackSnapshot(service == null ? null : service.getPlaybackState());
    }

    private void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(14), dp(16), dp(10));
        header.setBackground(makeHeaderBackground());
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView title = text("SimplePlayer", 27, Color.WHITE, true);
        title.setLetterSpacing(0.03f);
        header.addView(title, new LinearLayout.LayoutParams(-1, -2));

        MusicPlaybackService.SimplePlaybackState state = service == null ? null : service.getPlaybackState();
        String subtitleText;
        if (state != null && state.song != null) {
            subtitleText = (state.playing ? "Playing" : "Paused") + " • " + state.song.artist;
        } else {
            subtitleText = songs.size() + " songs loaded";
        }
        TextView subtitle = text(subtitleText, 13, TEXT_MUTED, false);
        subtitle.setPadding(0, dp(3), 0, dp(10));
        header.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        View accentLine = new View(this);
        accentLine.setBackground(makeHorizontalAccentGradient());
        header.addView(accentLine, new LinearLayout.LayoutParams(-1, dp(2)));
    }

    // Bottom mini-player: just enough context to be useful without becoming a second full player stapled on.
    private void buildNowPlayingBar() {
        if (service == null || "Player".equals(currentTab)) return;
        final MusicPlaybackService.SimplePlaybackState state = service.getPlaybackState();
        if (state == null || state.song == null) return;

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setBackground(makeGradientRect(SURFACE_RAISED, SURFACE_DARK, 0, accent, 1));
        bar.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            currentTab = "Player";
            render();
        }});

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(makeAlbumPlaceholder());
        if (state.song.albumArtUri.length() > 0) {
            try { thumb.setImageURI(Uri.parse(state.song.albumArtUri)); } catch (Exception ignored) {}
        }
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(46), dp(46));
        thumbLp.setMargins(0, 0, dp(10), 0);
        bar.addView(thumb, thumbLp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(state.song.title, 15, Color.WHITE, true);
        title.setSingleLine(true);
        TextView artist = text(state.song.artist, 12, TEXT_MUTED, false);
        artist.setSingleLine(true);
        info.addView(title, new LinearLayout.LayoutParams(-1, -2));
        info.addView(artist, new LinearLayout.LayoutParams(-1, -2));
        bar.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        Button play = button(state.playing ? "Pause" : "Play");
        nowPlayingPlayButton = play;
        play.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (service != null) service.togglePlayPause();
            updatePlaybackUi();
        }});
        bar.addView(play, new LinearLayout.LayoutParams(dp(88), dp(42)));

        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(66)));
    }

    private void buildTabs() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(false);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(10), dp(8), dp(10), dp(10));
        scroller.addView(tabs, new HorizontalScrollView.LayoutParams(-2, -2));

        String[] labels = new String[]{"Songs", "Albums", "Artists", "Playlists", "Player", "Settings"};
        for (final String label : labels) {
            TextView tab = pill(label, label.equals(currentTab));
            tab.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                currentTab = label;
                render();
            }});
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(40));
            lp.setMargins(dp(3), 0, dp(3), 0);
            tabs.addView(tab, lp);
        }
    }

    private void showCurrentTab() {
        if (content == null) return;
        content.removeAllViews();
        if ("Songs".equals(currentTab)) showSongs();
        else if ("Albums".equals(currentTab)) showAlbums();
        else if ("Artists".equals(currentTab)) showArtists();
        else if ("Playlists".equals(currentTab)) showPlaylists();
        else if ("Settings".equals(currentTab)) showSettings();
        else showPlayer();
    }

    private void showSongs() {
        LinearLayout outer = listContainer();
        String source = hasPickedFolder() ? "Picked folder: " + pickedFolderLabel() : "Folder: " + musicFolder;
        TextView folder = statusChip(source + (libraryScanRunning ? " • scanning…" : ""));
        LinearLayout.LayoutParams folderLp = new LinearLayout.LayoutParams(-1, -2);
        folderLp.setMargins(0, 0, 0, dp(12));
        outer.addView(folder, folderLp);

        if (songs.size() == 0) {
            String message = libraryScanRunning
                    ? "Scanning library. Songs will appear here when the scan finishes."
                    : (hasPickedFolder() ? "No local songs found in the picked folder." : (hasAudioPermission() ? "No local songs found in the selected folder." : "Grant audio/storage permission or pick your music folder in Settings."));
            outer.addView(emptyMessage(message), new LinearLayout.LayoutParams(-1, 0, 1));
            content.addView(outer, new FrameLayout.LayoutParams(-1, -1));
            return;
        }

        // ListView is old, but lazy rows beat hand-building a thousand thumbnails like a maniac.
        ListView listView = new ListView(this);
        listView.setBackground(makeAppBackground());
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setDivider(null);
        listView.setAdapter(new SongAdapter());
        outer.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(outer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showAlbums() {
        LinearLayout list = listContainer();
        Map<String, ArrayList<Song>> albums = new LinkedHashMap<>();
        for (Song song : songs) {
            String key = song.album + "\n" + song.artist;
            ArrayList<Song> bucket = albums.get(key);
            if (bucket == null) { bucket = new ArrayList<>(); albums.put(key, bucket); }
            bucket.add(song);
        }
        for (final Map.Entry<String, ArrayList<Song>> entry : albums.entrySet()) {
            String[] parts = entry.getKey().split("\n", 2);
            String title = parts.length > 0 ? parts[0] : "Unknown Album";
            String subtitle = (parts.length > 1 ? parts[1] : "Unknown Artist") + " • " + entry.getValue().size() + " songs";
            list.addView(row(title, subtitle, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (service != null) service.setQueueAndPlay(entry.getValue(), 0);
                    currentTab = "Player";
                    render();
                }
            }));
        }
        if (albums.size() == 0) list.addView(emptyMessage("No albums found in the selected folder."));
        content.addView(scroll(list));
    }

    private void showArtists() {
        LinearLayout list = listContainer();
        Map<String, ArrayList<Song>> artists = new LinkedHashMap<>();
        for (Song song : songs) {
            ArrayList<Song> bucket = artists.get(song.artist);
            if (bucket == null) { bucket = new ArrayList<>(); artists.put(song.artist, bucket); }
            bucket.add(song);
        }
        ArrayList<String> names = new ArrayList<>(artists.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        for (final String name : names) {
            final ArrayList<Song> artistSongs = artists.get(name);
            list.addView(row(name, artistSongs.size() + " songs", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (service != null) service.setQueueAndPlay(artistSongs, 0);
                    currentTab = "Player";
                    render();
                }
            }));
        }
        if (names.size() == 0) list.addView(emptyMessage("No artists found in the selected folder."));
        content.addView(scroll(list));
    }

    private void showPlaylists() {
        LinearLayout list = listContainer();
        TextView path = text("Playlist folder:\n" + PlaylistManager.playlistDirectory().getAbsolutePath(), 13, Color.LTGRAY, false);
        path.setPadding(dp(8), dp(8), dp(8), dp(12));
        list.addView(path);

        TextView note = text("Playlist files still use one filename or relative path per line. They resolve against the currently selected music folder/library.", 13, Color.GRAY, false);
        note.setPadding(dp(8), 0, dp(8), dp(12));
        list.addView(note);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button createPlaylist = button("Create playlist");
        createPlaylist.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { createPlaylistDialog(); }});
        LinearLayout.LayoutParams actionLeft = new LinearLayout.LayoutParams(0, dp(42), 1);
        actionLeft.setMargins(0, 0, dp(6), dp(12));
        actions.addView(createPlaylist, actionLeft);
        Button refresh = button("Refresh playlists");
        refresh.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { render(); }});
        LinearLayout.LayoutParams actionMid = new LinearLayout.LayoutParams(0, dp(42), 1);
        actionMid.setMargins(dp(3), 0, dp(3), dp(12));
        actions.addView(refresh, actionMid);
        Button saveQueue = button("Save queue");
        saveQueue.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { saveCurrentQueueDialog(); }});
        LinearLayout.LayoutParams actionRight = new LinearLayout.LayoutParams(0, dp(42), 1);
        actionRight.setMargins(dp(6), 0, 0, dp(12));
        actions.addView(saveQueue, actionRight);
        list.addView(actions);

        List<PlaylistManager.PlaylistInfo> playlists = PlaylistManager.listPlaylists();
        for (final PlaylistManager.PlaylistInfo playlist : playlists) {
            list.addView(row(playlist.name, playlist.file.getName(), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    PlaylistManager.PlaylistLoadResult loaded = PlaylistManager.load(playlist.file, songs);
                    if (loaded.songs.size() == 0) {
                        toast("No matching songs found in " + playlist.name);
                    } else {
                        if (service != null) service.setQueueAndPlay(loaded.songs, 0);
                        if (loaded.missingLines.size() > 0) toast("Skipped " + loaded.missingLines.size() + " missing lines");
                        currentTab = "Player";
                        render();
                    }
                }
            }));
        }
        if (playlists.size() == 0) {
            list.addView(emptyMessage("No .txt playlists yet. Drop one in the folder above, one filename per line."));
        }
        content.addView(scroll(list));
    }

    private void showPlayer() {
        MusicPlaybackService.SimplePlaybackState state = service == null ? null : service.getPlaybackState();
        final Song song = state == null ? null : state.song;
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(18), dp(14), dp(18), dp(18));
        outer.setBackground(makeAppBackground());
        content.addView(outer, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout artCard = new LinearLayout(this);
        artCard.setOrientation(LinearLayout.VERTICAL);
        artCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        artCard.setBackground(makeGradientRect(SURFACE_GLOW, SURFACE_DARK, 0, accent, 1));
        LinearLayout.LayoutParams artCardLp = landscape
                ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, 0, 1);
        artCardLp.setMargins(0, 0, landscape ? dp(18) : 0, landscape ? 0 : dp(16));
        outer.addView(artCard, artCardLp);

        ImageView art = new ImageView(this);
        art.setBackground(makeAlbumPlaceholder());
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (song != null && song.albumArtUri.length() > 0) {
            try { art.setImageURI(Uri.parse(song.albumArtUri)); } catch (Exception ignored) {}
        }
        artCard.addView(art, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(18), dp(18), dp(18), dp(18));
        controls.setBackground(makeGradientRect(SURFACE_RAISED, SURFACE_DARK, 0, STROKE, 1));
        outer.addView(controls, landscape ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, -2));

        TextView kicker = text("NOW PLAYING", 11, accent, true);
        kicker.setGravity(Gravity.CENTER);
        kicker.setLetterSpacing(0.12f);
        kicker.setPadding(0, 0, 0, dp(8));
        controls.addView(kicker, new LinearLayout.LayoutParams(-1, -2));

        TextView title = text(song == null ? "Nothing playing" : song.title, 25, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(false);
        controls.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = text(song == null ? "Pick a song, album, artist, or playlist." : song.artist + " • " + song.album, 15, TEXT_MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(6), 0, dp(16));
        controls.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        progressBar = new SeekBar(this);
        progressBar.setMax(state == null ? 0 : Math.max(0, state.durationMs));
        progressBar.setProgress(state == null ? 0 : Math.max(0, state.positionMs));
        if (Build.VERSION.SDK_INT >= 21) {
            progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
            progressBar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
            progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(STROKE));
        }
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                if (service != null) service.seekTo(seekBar.getProgress());
            }
        });
        controls.addView(progressBar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setPadding(dp(4), 0, dp(4), dp(12));
        TextView elapsed = text(state == null ? "0:00" : formatTime(state.positionMs), 12, TEXT_MUTED, false);
        elapsedLabel = elapsed;
        TextView duration = text(state == null ? "0:00" : formatTime(state.durationMs), 12, TEXT_MUTED, false);
        durationLabel = duration;
        duration.setGravity(Gravity.RIGHT);
        timeRow.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        timeRow.addView(duration, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(timeRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(4), 0, dp(10));
        controls.addView(row, new LinearLayout.LayoutParams(-1, -2));

        Button prev = button("Previous");
        prev.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (service != null) service.previous(); render(); }});
        row.addView(prev, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button play = filledButton(state != null && state.playing ? "Pause" : "Play");
        playerPlayButton = play;
        play.setTextSize(18);
        play.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (service != null) service.togglePlayPause(); updatePlaybackUi(); }});
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(0, dp(54), 1.25f);
        playLp.setMargins(dp(8), 0, dp(8), 0);
        row.addView(play, playLp);
        Button next = button("Next");
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (service != null) service.next(); render(); }});
        row.addView(next, new LinearLayout.LayoutParams(0, dp(54), 1));

        Button shuffle = button(state != null && state.shuffle ? "Shuffle: On" : "Shuffle: Off");
        playerShuffleButton = shuffle;
        shuffle.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (service != null) { service.setShuffleEnabled(!service.isShuffleEnabled()); render(); }
        }});
        controls.addView(shuffle, new LinearLayout.LayoutParams(-1, dp(46)));

        TextView queueInfo = text(state == null ? "" : "Queue " + Math.max(0, state.queueIndex + 1) + " of " + state.queueSize, 13, TEXT_MUTED, false);
        queueInfoLabel = queueInfo;
        queueInfo.setGravity(Gravity.CENTER);
        queueInfo.setPadding(0, dp(10), 0, 0);
        controls.addView(queueInfo, new LinearLayout.LayoutParams(-1, -2));

        if (state != null && state.shuffle) {
            String mode = state.adaptiveShuffle ? "Adaptive" : "Seeded";
            TextView seedInfo = text(mode + " shuffle • Seed: " + state.shuffleSeed, 12, TEXT_MUTED, false);
            seedInfoLabel = seedInfo;
            seedInfo.setGravity(Gravity.CENTER);
            seedInfo.setPadding(0, dp(3), 0, 0);
            controls.addView(seedInfo, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    // Settings is where all the storage and shuffle awkwardness goes to look intentional.
    private void showSettings() {
        LinearLayout list = listContainer();
        list.addView(sectionTitle("Library folder"));

        TextView folderHelp = text("SimplePlayer can scan the Android media library, or you can pick the exact folder that contains your music. Pick folder is the most reliable option for big WAV libraries on newer Android.", 13, Color.LTGRAY, false);
        folderHelp.setPadding(dp(8), 0, dp(8), dp(8));
        list.addView(folderHelp);

        String picked = hasPickedFolder() ? "Picked folder: " + pickedFolderLabel() : "Picked folder: none";
        TextView pickedStatus = text(picked, 13, hasPickedFolder() ? Color.LTGRAY : Color.GRAY, false);
        pickedStatus.setPadding(dp(8), 0, dp(8), dp(8));
        list.addView(pickedStatus);

        Button pickFolder = button("Pick music folder");
        pickFolder.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            try {
                startActivityForResult(intent, REQUEST_TREE);
            } catch (Exception e) {
                toast("Folder picker is not available on this device.");
            }
        }});
        addButtonBlock(list, pickFolder);

        Button clearPickedFolder = button("Clear picked folder");
        clearPickedFolder.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            musicTreeUri = "";
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MUSIC_TREE_URI, "").apply();
            if (!hasAudioPermission()) requestAudioPermission();
            else loadLibrary(true);
            toast("Picked folder cleared");
            currentTab = "Settings";
            render();
        }});
        addButtonBlock(list, clearPickedFolder);

        final EditText folderInput = input(musicFolder, "/sdcard/Music");
        addInputBlock(list, folderInput);

        Button saveFolder = button("Apply typed folder");
        saveFolder.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            musicTreeUri = "";
            musicFolder = MusicLibrary.cleanRoot(folderInput.getText().toString());
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_MUSIC_TREE_URI, "")
                    .putString(KEY_MUSIC_FOLDER, musicFolder)
                    .putBoolean(KEY_MUSIC_FOLDER_MANUAL, true)
                    .apply();
            currentTab = "Songs";
            render();
            if (!hasAudioPermission()) requestAudioPermission();
            else loadLibrary(true);
        }});
        addButtonBlock(list, saveFolder);

        Button autoFolder = button("Auto-detect Music folder");
        autoFolder.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (!hasAudioPermission()) {
                requestAudioPermission();
                return;
            }
            musicTreeUri = "";
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_MUSIC_TREE_URI, "")
                    .putBoolean(KEY_MUSIC_FOLDER_MANUAL, false)
                    .apply();
            maybeAutoDetectMusicFolder();
            folderInput.setText(musicFolder);
            folderInput.setSelection(folderInput.getText().length());
            currentTab = "Songs";
            render();
            loadLibrary(true);
        }});
        addButtonBlock(list, autoFolder);

        Button defaultFolder = button("Use /sdcard/Music");
        defaultFolder.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            folderInput.setText(MusicLibrary.defaultMusicDirectory().getAbsolutePath());
            folderInput.setSelection(folderInput.getText().length());
        }});
        addButtonBlock(list, defaultFolder);

        Button refreshLibrary = button("Refresh library");
        refreshLibrary.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (!hasPickedFolder() && !hasAudioPermission()) requestAudioPermission();
            else {
                if (!hasPickedFolder()) musicFolder = MusicLibrary.cleanRoot(folderInput.getText().toString());
                currentTab = "Songs";
                render();
                loadLibrary(true);
            }
        }});
        addButtonBlock(list, refreshLibrary);

        list.addView(sectionTitle("Accent color"));
        TextView accentHelp = text("Enter a hex color for selected tabs, sliders, outlines, and buttons.", 13, Color.LTGRAY, false);
        accentHelp.setPadding(dp(8), 0, dp(8), dp(8));
        list.addView(accentHelp);

        final EditText accentInput = input(String.format(Locale.US, "#%06X", 0xFFFFFF & accent), "#E53935");
        addInputBlock(list, accentInput);

        Button applyAccent = button("Apply accent color");
        applyAccent.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            applyAccentFromText(accentInput.getText().toString());
        }});
        addButtonBlock(list, applyAccent);

        list.addView(sectionTitle("Shuffle"));
        TextView shuffleHelp = text("Normal seeded shuffle uses the seed below to generate a stable no-repeat queue, then nudges the order to reduce artist/album clumps. Adaptive shuffle is optional: it still uses the seed, but songs skipped before 15 seconds are pushed later in future shuffled bags.", 13, Color.LTGRAY, false);
        shuffleHelp.setPadding(dp(8), 0, dp(8), dp(8));
        list.addView(shuffleHelp);

        Button adaptiveToggle = button(adaptiveShuffle ? "Adaptive shuffle: On" : "Adaptive shuffle: Off");
        adaptiveToggle.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            adaptiveShuffle = !adaptiveShuffle;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ADAPTIVE_SHUFFLE, adaptiveShuffle).apply();
            if (service != null) service.setAdaptiveShuffleEnabled(adaptiveShuffle);
            toast(adaptiveShuffle ? "Adaptive shuffle on" : "Adaptive shuffle off");
            render();
        }});
        addButtonBlock(list, adaptiveToggle);

        Button resetAdaptive = button("Reset adaptive song weighting");
        resetAdaptive.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            AdaptiveShuffleStore.reset(MainActivity.this);
            if (service != null) service.resetAdaptiveShuffleWeights();
            toast("Adaptive song weighting reset");
        }});
        addButtonBlock(list, resetAdaptive);

        final EditText seedInput = input(Long.toString(shuffleSeed), "shuffle seed");
        addInputBlock(list, seedInput);

        Button applySeed = button("Apply shuffle seed");
        applySeed.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            applyShuffleSeedFromText(seedInput.getText().toString());
            seedInput.setText(Long.toString(shuffleSeed));
            seedInput.setSelection(seedInput.getText().length());
        }});
        addButtonBlock(list, applySeed);

        Button regenerateSeed = button("Regenerate shuffle seed");
        regenerateSeed.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            shuffleSeed = makeNewShuffleSeed();
            saveShuffleSeed();
            if (service != null) service.setShuffleSeed(shuffleSeed);
            seedInput.setText(Long.toString(shuffleSeed));
            seedInput.setSelection(seedInput.getText().length());
            toast("Shuffle seed regenerated");
        }});
        addButtonBlock(list, regenerateSeed);

        list.addView(sectionTitle("Storage"));
        TextView playlistPath = text("Playlist folder:\n" + PlaylistManager.playlistDirectory().getAbsolutePath(), 13, Color.LTGRAY, false);
        playlistPath.setPadding(dp(8), 0, dp(8), dp(8));
        list.addView(playlistPath);

        TextView playlistHelp = text("Each playlist is a .txt file. The filename is the playlist name; each line is a filename or relative path.", 13, Color.GRAY, false);
        playlistHelp.setPadding(dp(8), 0, dp(8), dp(12));
        list.addView(playlistHelp);

        content.addView(scroll(list));
    }

    private void updatePlaybackUi() {
        if (service == null) return;
        MusicPlaybackService.SimplePlaybackState state = service.getPlaybackState();
        if ("Player".equals(currentTab) && playerNeedsRebuild(state)) {
            render();
            return;
        }

        if (progressBar != null && !userSeeking) {
            progressBar.setMax(Math.max(0, state.durationMs));
            progressBar.setProgress(Math.max(0, state.positionMs));
        }
        if (elapsedLabel != null) elapsedLabel.setText(formatTime(state.positionMs));
        if (durationLabel != null) durationLabel.setText(formatTime(state.durationMs));
        if (playerPlayButton != null) playerPlayButton.setText(state.playing ? "Pause" : "Play");
        if (nowPlayingPlayButton != null) nowPlayingPlayButton.setText(state.playing ? "Pause" : "Play");
        if (playerShuffleButton != null) playerShuffleButton.setText(state.shuffle ? "Shuffle: On" : "Shuffle: Off");
        if (queueInfoLabel != null) queueInfoLabel.setText("Queue " + Math.max(0, state.queueIndex + 1) + " of " + state.queueSize);
        if (seedInfoLabel != null && state.shuffle) {
            String mode = state.adaptiveShuffle ? "Adaptive" : "Seeded";
            seedInfoLabel.setText(mode + " shuffle • Seed: " + state.shuffleSeed);
        }
        rememberPlaybackSnapshot(state);
    }

    private boolean playerNeedsRebuild(MusicPlaybackService.SimplePlaybackState state) {
        if (state == null) return !"".equals(lastRenderedSongKey);
        String key = playbackSongKey(state);
        if (!key.equals(lastRenderedSongKey)) return true;
        if (state.queueIndex != lastRenderedQueueIndex) return true;
        if (state.queueSize != lastRenderedQueueSize) return true;
        if (state.shuffle != lastRenderedShuffle) return true;
        if (state.adaptiveShuffle != lastRenderedAdaptiveShuffle) return true;
        return state.shuffleSeed != lastRenderedShuffleSeed;
    }

    private void rememberPlaybackSnapshot(MusicPlaybackService.SimplePlaybackState state) {
        lastRenderedSongKey = playbackSongKey(state);
        if (state == null) {
            lastRenderedQueueIndex = Integer.MIN_VALUE;
            lastRenderedQueueSize = Integer.MIN_VALUE;
            lastRenderedShuffle = false;
            lastRenderedAdaptiveShuffle = false;
            lastRenderedShuffleSeed = Long.MIN_VALUE;
            return;
        }
        lastRenderedQueueIndex = state.queueIndex;
        lastRenderedQueueSize = state.queueSize;
        lastRenderedShuffle = state.shuffle;
        lastRenderedAdaptiveShuffle = state.adaptiveShuffle;
        lastRenderedShuffleSeed = state.shuffleSeed;
    }

    private String playbackSongKey(MusicPlaybackService.SimplePlaybackState state) {
        if (state == null || state.song == null) return "";
        return state.song.stableKey();
    }

    private void clearPlaybackViewRefs() {
        progressBar = null;
        elapsedLabel = null;
        durationLabel = null;
        queueInfoLabel = null;
        seedInfoLabel = null;
        playerPlayButton = null;
        playerShuffleButton = null;
        nowPlayingPlayButton = null;
        userSeeking = false;
    }

    private String formatTime(int ms) {
        if (ms <= 0) return "0:00";
        int total = ms / 1000;
        int minutes = total / 60;
        int seconds = total % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private LinearLayout listContainer() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(10), dp(14), dp(18));
        list.setBackground(makeAppBackground());
        return list;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(makeAppBackground());
        scroll.addView(child, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    // Song rows stay lightweight; album art loading here should not become a thumbnail stampede.
    private View songRow(final Song song, View.OnClickListener listener) {
        LinearLayout box = baseRow(listener);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(makeAlbumPlaceholder());
        if (song.albumArtUri.length() > 0) {
            try { thumb.setImageURI(Uri.parse(song.albumArtUri)); } catch (Exception ignored) {}
        }
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(54), dp(54));
        thumbLp.setMargins(0, 0, dp(12), 0);
        box.addView(thumb, thumbLp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(song.title, 16, Color.WHITE, true);
        title.setSingleLine(true);
        TextView subtitle = text(song.artist + " • " + song.album, 13, TEXT_MUTED, false);
        subtitle.setSingleLine(true);
        subtitle.setPadding(0, dp(3), 0, 0);
        info.addView(title, new LinearLayout.LayoutParams(-1, -2));
        info.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        box.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        return box;
    }

    private View row(String title, String subtitle, View.OnClickListener listener) {
        LinearLayout box = baseRow(listener);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView t = text(title, 16, Color.WHITE, true);
        TextView s = text(subtitle, 13, TEXT_MUTED, false);
        s.setPadding(0, dp(3), 0, 0);
        box.addView(t);
        box.addView(s);
        return box;
    }

    private LinearLayout baseRow(View.OnClickListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(makeGradientRect(SURFACE_RAISED, SURFACE, 0, STROKE_SOFT, 1));
        box.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(9));
        box.setLayoutParams(lp);
        return box;
    }

    private TextView emptyMessage(String message) {
        TextView view = text(message, 16, Color.LTGRAY, false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(22), dp(42), dp(22), dp(42));
        view.setBackground(makeGradientRect(SURFACE_RAISED, SURFACE_DARK, 0, STROKE_SOFT, 1));
        return view;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value.toUpperCase(Locale.US), 12, accent, true);
        title.setLetterSpacing(0.10f);
        title.setPadding(dp(8), dp(20), dp(8), dp(9));
        return title;
    }

    private EditText input(String value, String hint) {
        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setSingleLine(true);
        edit.setText(value == null ? "" : value);
        edit.setHint(hint);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(TEXT_DIM);
        edit.setSelectAllOnFocus(true);
        edit.setPadding(dp(10), 0, dp(10), 0);
        edit.setBackground(makeGradientRect(SURFACE_DARK, SURFACE, 0, STROKE, 1));
        return edit;
    }

    private TextView pill(String label, boolean selected) {
        TextView view = text(label, 13, selected ? Color.WHITE : TEXT_MUTED, true);
        view.setGravity(Gravity.CENTER);
        view.setLetterSpacing(0.02f);
        if (selected) {
            view.setBackground(makeGradientRect(brighten(accent, 0.18f), darken(accent, 0.18f), 0, accent, 1));
        } else {
            view.setBackground(makeGradientRect(SURFACE_RAISED, SURFACE_DARK, 0, STROKE_SOFT, 1));
        }
        return view;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(makeGradientRect(SURFACE_GLOW, SURFACE_DARK, 0, darken(accent, 0.12f), 1));
        return b;
    }

    private Button filledButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(makeGradientRect(brighten(accent, 0.24f), darken(accent, 0.18f), 0, accent, 1));
        return b;
    }

    private void addButtonBlock(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
        lp.setMargins(0, 0, 0, dp(8));
        parent.addView(button, lp);
    }

    private void addInputBlock(LinearLayout parent, View input) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.setMargins(0, 0, 0, dp(8));
        parent.addView(input, lp);
    }

    private LinearLayout.LayoutParams smallButtonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.setMargins(dp(6), 0, 0, 0);
        return lp;
    }

    private TextView statusChip(String value) {
        TextView view = text(value, 12, TEXT_MUTED, false);
        view.setPadding(dp(12), dp(9), dp(12), dp(9));
        view.setSingleLine(false);
        view.setBackground(makeGradientRect(SURFACE_DARK, BG_BOTTOM, 0, STROKE_SOFT, 1));
        return view;
    }

    private GradientDrawable makeRect(int color, int radius, int strokeColor, int strokeWidthDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        d.setStroke(dp(strokeWidthDp), strokeColor);
        return d;
    }

    // Tiny gradient factory: minimal UI polish without dragging in a design system wearing tap shoes.
    private GradientDrawable makeGradientRect(int topColor, int bottomColor, int radius, int strokeColor, int strokeWidthDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{topColor, bottomColor});
        d.setCornerRadius(radius);
        d.setStroke(dp(strokeWidthDp), strokeColor);
        return d;
    }

    private GradientDrawable makeAppBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG, BG_BOTTOM});
    }

    private GradientDrawable makeHeaderBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{SURFACE_DARK, BG});
    }

    private GradientDrawable makeHorizontalAccentGradient() {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{accent, darken(accent, 0.55f), BG});
    }

    private GradientDrawable makeAlbumPlaceholder() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{SURFACE_GLOW, SURFACE_DARK, BG_BOTTOM});
    }

    private int brighten(int color, float amount) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        r = Math.min(255, (int) (r + (255 - r) * amount));
        g = Math.min(255, (int) (g + (255 - g) * amount));
        b = Math.min(255, (int) (b + (255 - b) * amount));
        return Color.rgb(r, g, b);
    }

    private int darken(int color, float amount) {
        int r = Math.max(0, (int) (Color.red(color) * (1f - amount)));
        int g = Math.max(0, (int) (Color.green(color) * (1f - amount)));
        int b = Math.max(0, (int) (Color.blue(color) * (1f - amount)));
        return Color.rgb(r, g, b);
    }

    private void applyAccentFromText(String raw) {
        try {
            accent = Color.parseColor(raw.trim());
            SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            editor.putInt(KEY_ACCENT, accent);
            editor.apply();
            if (service != null) service.setAccent(accent);
            render();
        } catch (Exception e) {
            toast("That color did not parse.");
        }
    }

    private void applyShuffleSeedFromText(String raw) {
        shuffleSeed = parseShuffleSeed(raw);
        saveShuffleSeed();
        if (service != null) service.setShuffleSeed(shuffleSeed);
        toast("Shuffle seed applied");
    }

    private void saveShuffleSeed() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_SHUFFLE_SEED, shuffleSeed)
                .apply();
    }

    private long makeNewShuffleSeed() {
        long seed = System.currentTimeMillis() ^ System.nanoTime() ^ ((long) songs.size() << 32);
        return seed == 0L ? 0x5eed51a7L : seed;
    }

    // Accept numbers, hex, or text. Seeds should be useful, not a math entrance exam.
    private long parseShuffleSeed(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) return 0x5eed51a7L;
        try {
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return parseHexSeed(value.substring(2));
            }
            return Long.parseLong(value);
        } catch (Exception ignored) {
            long h = 0xcbf29ce484222325L;
            for (int i = 0; i < value.length(); i++) {
                h ^= value.charAt(i);
                h *= 0x100000001b3L;
            }
            return h == 0L ? 0x5eed51a7L : h;
        }
    }

    private long parseHexSeed(String value) {
        long out = 0L;
        for (int i = 0; i < value.length(); i++) {
            int digit = Character.digit(value.charAt(i), 16);
            if (digit < 0) throw new NumberFormatException("Bad hex seed");
            out = (out << 4) | digit;
        }
        return out == 0L ? 0x5eed51a7L : out;
    }


    // Adapter exists because the previous eager row build was a performance felony.
    private class SongAdapter extends BaseAdapter {
        @Override public int getCount() { return songs.size(); }
        @Override public Object getItem(int position) { return songs.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(final int position, View convertView, ViewGroup parent) {
            final Song song = songs.get(position);
            View row = songRow(song, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (service != null) service.setQueueAndPlay(songs, position);
                    currentTab = "Player";
                    render();
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showSongActions(song);
                    return true;
                }
            });
            return row;
        }
    }

    private void showAccentDialog() {
        final EditText input = input(String.format(Locale.US, "#%06X", 0xFFFFFF & accent), "#E53935");
        new AlertDialog.Builder(this)
                .setTitle("Accent color")
                .setMessage("Enter a hex color.")
                .setView(input)
                .setPositiveButton("Apply", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        applyAccentFromText(input.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // Long-press menu: for when tapping a song should not drag the whole library along for the ride.
    private void showSongActions(final Song song) {
        final String[] actions = new String[]{"Play this song only", "Add to playlist..."};
        new AlertDialog.Builder(this)
                .setTitle(song.title)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            if (service != null) service.setQueueAndPlay(Collections.singletonList(song), 0);
                            currentTab = "Player";
                            render();
                        } else if (which == 1) {
                            addSongToPlaylistDialog(song);
                        }
                    }
                })
                .show();
    }

    private void createPlaylistDialog() {
        final EditText input = input("New Playlist", "Playlist name");
        new AlertDialog.Builder(this)
                .setTitle("Create playlist")
                .setView(input)
                .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        PlaylistManager.CreateResult result = PlaylistManager.createPlaylist(input.getText().toString());
                        toast(result.message);
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addSongToPlaylistDialog(final Song song) {
        final List<PlaylistManager.PlaylistInfo> playlists = PlaylistManager.listPlaylists();
        final String[] names = new String[playlists.size() + 1];
        names[0] = "Create new playlist...";
        for (int i = 0; i < playlists.size(); i++) names[i + 1] = playlists.get(i).name;

        new AlertDialog.Builder(this)
                .setTitle("Add to playlist")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            createPlaylistAndAddSongDialog(song);
                        } else {
                            PlaylistManager.PlaylistInfo target = playlists.get(which - 1);
                            boolean ok = PlaylistManager.addSong(target.file, song);
                            toast(ok ? "Added to " + target.name : "Could not write playlist file.");
                        }
                    }
                })
                .show();
    }

    private void createPlaylistAndAddSongDialog(final Song song) {
        final EditText input = input("New Playlist", "Playlist name");
        new AlertDialog.Builder(this)
                .setTitle("Create playlist")
                .setMessage("The song will be added after the playlist file is created.")
                .setView(input)
                .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        PlaylistManager.CreateResult result = PlaylistManager.createPlaylist(input.getText().toString());
                        if (result.file != null && result.createdOrAlreadyExists) {
                            boolean ok = PlaylistManager.addSong(result.file, song);
                            toast(ok ? "Playlist created and song added." : "Playlist created, but the song could not be added.");
                        } else {
                            toast(result.message);
                        }
                        currentTab = "Playlists";
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveCurrentQueueDialog() {
        if (service == null || service.getQueueSnapshot().size() == 0) {
            toast("No current queue to save.");
            return;
        }
        final EditText input = input("New Playlist", "Playlist name");
        new AlertDialog.Builder(this)
                .setTitle("Save current queue")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        boolean ok = PlaylistManager.save(input.getText().toString(), service.getQueueSnapshot());
                        toast(ok ? "Playlist saved." : "Could not write playlist file.");
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
