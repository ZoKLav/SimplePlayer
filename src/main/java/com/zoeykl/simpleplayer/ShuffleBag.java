package com.zoeykl.simpleplayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ShuffleBag {
    public static ArrayList<Song> makeQueue(List<Song> source, Song first) {
        return makeQueue(source, first, System.nanoTime());
    }

    public static ArrayList<Song> makeQueue(List<Song> source, Song first, long seed) {
        return makeQueue(source, first, seed, null, false);
    }

    // Deterministic chaos: same seed, same library, same order. Random-per-button-press can stay in the penalty box.
    public static ArrayList<Song> makeQueue(List<Song> source, Song first, long seed, Map<String, Integer> penalties, boolean adaptive) {
        ArrayList<Song> queue = new ArrayList<>();
        if (source == null || source.size() == 0) return queue;

        for (Song song : source) {
            if (first != null && song.sameFileAs(first)) continue;
            queue.add(song);
        }

        seededSongSort(queue, seed, penalties, adaptive);
        declump(queue, seed);

        if (first != null) queue.add(0, first);
        return queue;
    }

    private static void seededSongSort(ArrayList<Song> list, final long seed, final Map<String, Integer> penalties, final boolean adaptive) {
        Collections.sort(list, new Comparator<Song>() {
            @Override public int compare(Song a, Song b) {
                if (adaptive) {
                    double av = adaptiveRank(a, seed, penalties);
                    double bv = adaptiveRank(b, seed, penalties);
                    if (av < bv) return -1;
                    if (av > bv) return 1;
                    return a.stableKey().compareTo(b.stableKey());
                }
                long av = songScore(a, seed);
                long bv = songScore(b, seed);
                if (av == bv) return a.stableKey().compareTo(b.stableKey());
                return unsignedLess(av, bv) ? -1 : 1;
            }
        });
    }

    private static double adaptiveRank(Song song, long seed, Map<String, Integer> penalties) {
        long base = songScore(song, seed);
        int penalty = 0;
        if (penalties != null && song != null) {
            Integer value = penalties.get(song.stableKey());
            if (value != null) penalty = Math.max(0, Math.min(9, value));
        }
        // A quick-skip penalty pushes a song later in the bag, but it does not remove the song.
        // Higher penalties matter more, while the seed still decides the order inside each weight band.
        return unsignedUnit(base) + (penalty * 0.135d);
    }

    private static double unsignedUnit(long value) {
        return (double) (value >>> 11) / (double) (1L << 53);
    }

    private static void declump(ArrayList<Song> list, long seed) {
        for (int i = 1; i < list.size(); i++) {
            if (!tooSimilarToRecent(list, i)) continue;
            int best = -1;
            long bestScore = 0L;
            for (int j = i + 1; j < list.size(); j++) {
                if (wouldConflictAt(list, j, i)) continue;
                long score = mix64(seed ^ stableHash(list.get(j).stableKey()) ^ ((long) i * 0x9E3779B97F4A7C15L));
                if (best < 0 || unsignedLess(score, bestScore)) {
                    best = j;
                    bestScore = score;
                }
            }
            if (best > -1) Collections.swap(list, i, best);
        }
    }

    private static long songScore(Song song, long seed) {
        String key = song == null ? "" : song.stableKey();
        long artist = stableHash(song == null ? "" : clean(song.artist));
        long album = stableHash(song == null ? "" : clean(song.album));
        long file = stableHash(key);
        return mix64(seed ^ file ^ Long.rotateLeft(artist, 17) ^ Long.rotateLeft(album, 37));
    }

    private static boolean tooSimilarToRecent(ArrayList<Song> list, int index) {
        Song current = list.get(index);
        Song previous = list.get(index - 1);
        if (same(current.album, previous.album)) return true;
        if (same(current.artist, previous.artist)) return true;
        if (index > 1 && same(current.artist, list.get(index - 2).artist)) return true;
        return false;
    }

    private static boolean wouldConflictAt(ArrayList<Song> list, int candidateIndex, int targetIndex) {
        Song candidate = list.get(candidateIndex);
        Song previous = list.get(targetIndex - 1);
        if (same(candidate.artist, previous.artist)) return true;
        if (same(candidate.album, previous.album)) return true;
        if (targetIndex > 1 && same(candidate.artist, list.get(targetIndex - 2).artist)) return true;
        if (targetIndex + 1 < list.size()) {
            Song next = list.get(targetIndex + 1);
            if (same(candidate.artist, next.artist)) return true;
            if (same(candidate.album, next.album)) return true;
        }
        return false;
    }

    private static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        return clean(a).equals(clean(b));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
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

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static boolean unsignedLess(long a, long b) {
        return (a + Long.MIN_VALUE) < (b + Long.MIN_VALUE);
    }
}
