package com.zoeykl.simpleplayer;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.SessionInfo;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.validation.HostValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SimplePlayerCarAppService extends CarAppService {
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // Testing/sideloading friendly. Production apps should restrict this to trusted car hosts,
        // because apparently even the dashboard gets a bouncer now.
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession(@NonNull SessionInfo sessionInfo) {
        return new Session() {
            @NonNull
            @Override
            public Screen onCreateScreen(@NonNull Intent intent) {
                return new HomeScreen(getCarContext());
            }
        };
    }

    private static class HomeScreen extends Screen {
        HomeScreen(@NonNull CarContext carContext) { super(carContext); }

        @NonNull
        @Override
        public Template onGetTemplate() {
            final ArrayList<Song> songs = loadSongs(getCarContext());
            ItemList.Builder list = new ItemList.Builder();
            list.addItem(navRow("Songs", songs.size() + " songs", new SongsScreen(getCarContext(), "Songs", songs)));
            list.addItem(navRow("Albums", albumBuckets(songs).size() + " albums", new AlbumIndexScreen(getCarContext(), songs)));
            list.addItem(navRow("Artists", artistBuckets(songs).size() + " artists", new ArtistIndexScreen(getCarContext(), songs)));
            list.addItem(navRow("Now Playing", "Open playback controls", new NowPlayingScreen(getCarContext())));

            return new ListTemplate.Builder()
                    .setTitle("SimplePlayer")
                    .setHeaderAction(Action.APP_ICON)
                    .setSingleList(list.build())
                    .build();
        }

        private Row navRow(String title, String text, final Screen target) {
            return new Row.Builder()
                    .setTitle(title)
                    .addText(text)
                    .setBrowsable(true)
                    .setOnClickListener(() -> getScreenManager().push(target))
                    .build();
        }
    }

    private static class SongsScreen extends Screen {
        private final String title;
        private final ArrayList<Song> songs;

        SongsScreen(@NonNull CarContext carContext, String title, List<Song> songs) {
            super(carContext);
            this.title = title == null ? "Songs" : title;
            this.songs = new ArrayList<>(songs == null ? Collections.<Song>emptyList() : songs);
        }

        @NonNull
        @Override
        public Template onGetTemplate() {
            if (songs.size() == 0) {
                return new MessageTemplate.Builder("No cached songs yet. Open SimplePlayer on the phone and refresh the library first.")
                        .setTitle(title)
                        .setHeaderAction(Action.BACK)
                        .build();
            }

            ItemList.Builder list = new ItemList.Builder();
            int limit = Math.min(songs.size(), 200);
            for (int i = 0; i < limit; i++) {
                final Song song = songs.get(i);
                list.addItem(new Row.Builder()
                        .setTitle(song.title)
                        .addText(song.artist + " • " + song.album)
                        .setOnClickListener(() -> playFromCar(getCarContext(), song))
                        .build());
            }
            if (songs.size() > limit) {
                list.addItem(new Row.Builder()
                        .setTitle("Library clipped for driving")
                        .addText("Showing first " + limit + " songs so Android Auto does not throw a fit.")
                        .build());
            }

            return new ListTemplate.Builder()
                    .setTitle(title)
                    .setHeaderAction(Action.BACK)
                    .setSingleList(list.build())
                    .build();
        }
    }

    private static class AlbumIndexScreen extends Screen {
        private final ArrayList<Song> songs;
        AlbumIndexScreen(@NonNull CarContext carContext, List<Song> songs) {
            super(carContext);
            this.songs = new ArrayList<>(songs == null ? Collections.<Song>emptyList() : songs);
        }
        @NonNull @Override public Template onGetTemplate() {
            ItemList.Builder list = new ItemList.Builder();
            for (Map.Entry<String, ArrayList<Song>> entry : albumBuckets(songs).entrySet()) {
                final String key = entry.getKey();
                final ArrayList<Song> albumSongs = entry.getValue();
                list.addItem(new Row.Builder()
                        .setTitle(key)
                        .addText(albumSongs.size() + " songs")
                        .setBrowsable(true)
                        .setOnClickListener(() -> getScreenManager().push(new SongsScreen(getCarContext(), key, albumSongs)))
                        .build());
            }
            return new ListTemplate.Builder().setTitle("Albums").setHeaderAction(Action.BACK).setSingleList(list.build()).build();
        }
    }

    private static class ArtistIndexScreen extends Screen {
        private final ArrayList<Song> songs;
        ArtistIndexScreen(@NonNull CarContext carContext, List<Song> songs) {
            super(carContext);
            this.songs = new ArrayList<>(songs == null ? Collections.<Song>emptyList() : songs);
        }
        @NonNull @Override public Template onGetTemplate() {
            ItemList.Builder list = new ItemList.Builder();
            for (Map.Entry<String, ArrayList<Song>> entry : artistBuckets(songs).entrySet()) {
                final String key = entry.getKey();
                final ArrayList<Song> artistSongs = entry.getValue();
                list.addItem(new Row.Builder()
                        .setTitle(key)
                        .addText(artistSongs.size() + " songs")
                        .setBrowsable(true)
                        .setOnClickListener(() -> getScreenManager().push(new SongsScreen(getCarContext(), key, artistSongs)))
                        .build());
            }
            return new ListTemplate.Builder().setTitle("Artists").setHeaderAction(Action.BACK).setSingleList(list.build()).build();
        }
    }

    private static class NowPlayingScreen extends Screen {
        NowPlayingScreen(@NonNull CarContext carContext) { super(carContext); }
        @NonNull @Override public Template onGetTemplate() {
            ItemList.Builder list = new ItemList.Builder();
            list.addItem(new Row.Builder()
                    .setTitle("Playback controls")
                    .addText("Use the Android Auto media bar, steering wheel, or headset controls.")
                    .build());
            list.addItem(actionRow("Previous", MusicPlaybackService.ACTION_PREVIOUS));
            list.addItem(actionRow("Play / Pause", MusicPlaybackService.ACTION_TOGGLE));
            list.addItem(actionRow("Next", MusicPlaybackService.ACTION_NEXT));
            return new ListTemplate.Builder().setTitle("Now Playing").setHeaderAction(Action.BACK).setSingleList(list.build()).build();
        }
        private Row actionRow(String title, final String action) {
            return new Row.Builder()
                    .setTitle(title)
                    .setOnClickListener(() -> getCarContext().startService(new Intent(getCarContext(), MusicPlaybackService.class).setAction(action)))
                    .build();
        }
    }

    private static void playFromCar(CarContext context, Song song) {
        Intent intent = new Intent(context, MusicPlaybackService.class)
                .setAction(MusicPlaybackService.ACTION_PLAY_STABLE_KEY)
                .putExtra(MusicPlaybackService.EXTRA_STABLE_KEY, song.stableKey());
        context.startService(intent);
    }

    private static ArrayList<Song> loadSongs(CarContext context) {
        LibraryCache.Result cached = LibraryCache.load(context);
        if (cached.loaded) return cached.songs;
        return new ArrayList<>();
    }

    private static LinkedHashMap<String, ArrayList<Song>> albumBuckets(List<Song> songs) {
        LinkedHashMap<String, ArrayList<Song>> out = new LinkedHashMap<>();
        ArrayList<Song> sorted = new ArrayList<>(songs == null ? Collections.<Song>emptyList() : songs);
        Collections.sort(sorted, (a, b) -> (safe(a.album) + "\u0000" + safe(a.artist)).compareToIgnoreCase(safe(b.album) + "\u0000" + safe(b.artist)));
        for (Song song : sorted) {
            String key = safe(song.album) + " — " + safe(song.artist);
            ArrayList<Song> bucket = out.get(key);
            if (bucket == null) { bucket = new ArrayList<>(); out.put(key, bucket); }
            bucket.add(song);
        }
        return out;
    }

    private static LinkedHashMap<String, ArrayList<Song>> artistBuckets(List<Song> songs) {
        LinkedHashMap<String, ArrayList<Song>> out = new LinkedHashMap<>();
        ArrayList<Song> sorted = new ArrayList<>(songs == null ? Collections.<Song>emptyList() : songs);
        Collections.sort(sorted, (a, b) -> safe(a.artist).compareToIgnoreCase(safe(b.artist)));
        for (Song song : sorted) {
            String key = safe(song.artist);
            ArrayList<Song> bucket = out.get(key);
            if (bucket == null) { bucket = new ArrayList<>(); out.put(key, bucket); }
            bucket.add(song);
        }
        return out;
    }

    private static String safe(String value) {
        return value == null || value.trim().length() == 0 ? "Unknown" : value.trim();
    }
}
