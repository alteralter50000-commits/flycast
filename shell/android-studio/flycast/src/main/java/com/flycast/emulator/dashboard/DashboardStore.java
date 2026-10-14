package com.flycast.emulator.dashboard;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent state of the dashboard: game library, background image, music and click sounds.
 * Custom media files chosen by the user are copied into the app's private storage so they keep
 * working without any storage permission.
 */
public class DashboardStore {
    public static class Game {
        public final String uri;
        public final String name;
        /** Local path of a cover picture, or null */
        public final String cover;

        public Game(String uri, String name) {
            this(uri, name, null);
        }

        public Game(String uri, String name, String cover) {
            this.uri = uri;
            this.name = name;
            this.cover = cover;
        }
    }

    private static final String PREFS = "xdashboard";
    private static final String KEY_GAMES = "games";
    private static final String KEY_SCANNED = "scanned";
    private static final String KEY_BACKGROUND = "background";
    private static final String KEY_MUSIC = "music";
    private static final String KEY_CLICK = "click";
    private static final String KEY_MUSIC_ON = "music_on";
    private static final String KEY_SFX_ON = "sfx_on";
    private static final String KEY_VOLUME = "volume";

    private final Context context;
    private final SharedPreferences prefs;
    private final List<Game> games = new ArrayList<>();

    public DashboardStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadGames();
    }

    // ---- game library ----

    private void loadGames() {
        games.clear();
        String json = prefs.getString(KEY_GAMES, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                games.add(new Game(o.getString("uri"), o.getString("name")));
            }
        } catch (JSONException e) {
            games.clear();
        }
    }

    private void saveGames() {
        JSONArray array = new JSONArray();
        try {
            for (Game g : games) {
                JSONObject o = new JSONObject();
                o.put("uri", g.uri);
                o.put("name", g.name);
                array.put(o);
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY_GAMES, array.toString()).apply();
    }

    public synchronized List<Game> getGames() {
        return new ArrayList<>(games);
    }

    /** Adds a game unless its URI is already present. Returns true if it was added. */
    public synchronized boolean addGame(String uri, String name) {
        for (Game g : games) {
            if (g.uri.equals(uri))
                return false;
        }
        games.add(new Game(uri, name));
        sortGames();
        saveGames();
        return true;
    }

    public synchronized void addGames(List<Game> newGames) {
        boolean changed = false;
        for (Game n : newGames) {
            boolean exists = false;
            for (Game g : games) {
                if (g.uri.equals(n.uri)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                games.add(n);
                changed = true;
            }
        }
        if (changed) {
            sortGames();
            saveGames();
        }
    }

    public synchronized void removeGame(String uri) {
        for (int i = 0; i < games.size(); i++) {
            if (games.get(i).uri.equals(uri)) {
                games.remove(i);
                saveGames();
                return;
            }
        }
    }

    public synchronized void clearGames() {
        games.clear();
        saveGames();
    }

    private void sortGames() {
        java.util.Collections.sort(games, new java.util.Comparator<Game>() {
            @Override
            public int compare(Game a, Game b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    // ---- games found automatically (cache of the last scan) ----

    public synchronized List<Game> getScanned() {
        List<Game> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_SCANNED, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                out.add(new Game(o.getString("uri"), o.getString("name"), o.optString("cover", null)));
            }
        } catch (JSONException e) {
            out.clear();
        }
        return out;
    }

    public synchronized void setScanned(List<Game> list) {
        JSONArray array = new JSONArray();
        try {
            for (Game g : list) {
                JSONObject o = new JSONObject();
                o.put("uri", g.uri);
                o.put("name", g.name);
                if (g.cover != null)
                    o.put("cover", g.cover);
                array.put(o);
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY_SCANNED, array.toString()).apply();
    }

    // ---- display names ----

    public static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public String queryDisplayName(Uri uri) {
        String name = null;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            Cursor c = null;
            try {
                c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (c != null && c.moveToFirst())
                    name = c.getString(0);
            } catch (Exception ignored) {
            } finally {
                if (c != null)
                    c.close();
            }
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
            if (name == null)
                name = "Game";
            int slash = name.lastIndexOf('/');
            if (slash >= 0)
                name = name.substring(slash + 1);
        }
        return stripExtension(name);
    }

    // ---- media settings ----

    public String getBackgroundPath() {
        return existingPath(KEY_BACKGROUND);
    }

    public String getMusicPath() {
        return existingPath(KEY_MUSIC);
    }

    public String getClickPath() {
        return existingPath(KEY_CLICK);
    }

    private String existingPath(String key) {
        String path = prefs.getString(key, null);
        if (path != null && !new File(path).exists())
            return null;
        return path;
    }

    public boolean importBackground(Uri uri) {
        return importMedia(uri, "background", KEY_BACKGROUND);
    }

    public boolean importMusic(Uri uri) {
        return importMedia(uri, "music", KEY_MUSIC);
    }

    public boolean importClick(Uri uri) {
        return importMedia(uri, "click", KEY_CLICK);
    }

    public void resetBackground() {
        clearMedia(KEY_BACKGROUND);
    }

    public void resetMusic() {
        clearMedia(KEY_MUSIC);
    }

    public void resetClick() {
        clearMedia(KEY_CLICK);
    }

    private void clearMedia(String key) {
        String path = prefs.getString(key, null);
        if (path != null)
            new File(path).delete();
        prefs.edit().remove(key).apply();
    }

    private boolean importMedia(Uri uri, String baseName, String key) {
        File dir = new File(context.getFilesDir(), "dashboard");
        if (!dir.exists() && !dir.mkdirs())
            return false;
        // Use a fresh file name each time so cached decoders never see stale content
        File dest = new File(dir, baseName + "_" + System.currentTimeMillis());
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null)
                return false;
            out = new FileOutputStream(dest);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            out.flush();
        } catch (IOException | SecurityException e) {
            dest.delete();
            return false;
        } finally {
            try {
                if (in != null)
                    in.close();
            } catch (IOException ignored) {
            }
            try {
                if (out != null)
                    out.close();
            } catch (IOException ignored) {
            }
        }
        String old = prefs.getString(key, null);
        if (old != null)
            new File(old).delete();
        prefs.edit().putString(key, dest.getAbsolutePath()).apply();
        return true;
    }

    public boolean isMusicOn() {
        return prefs.getBoolean(KEY_MUSIC_ON, true);
    }

    public void setMusicOn(boolean on) {
        prefs.edit().putBoolean(KEY_MUSIC_ON, on).apply();
    }

    public boolean isSfxOn() {
        return prefs.getBoolean(KEY_SFX_ON, true);
    }

    public void setSfxOn(boolean on) {
        prefs.edit().putBoolean(KEY_SFX_ON, on).apply();
    }

    /** Music volume in percent, 0..100 */
    public int getVolume() {
        return prefs.getInt(KEY_VOLUME, 60);
    }

    public void setVolume(int percent) {
        prefs.edit().putInt(KEY_VOLUME, Math.max(0, Math.min(100, percent))).apply();
    }
}
