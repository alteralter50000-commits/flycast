package com.flycast.emulator.dashboard;

import android.content.Context;
import android.net.Uri;
import android.preference.PreferenceManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the games the emulator already knows about: it reads the game folders from Flycast's own
 * configuration file (emu.cfg), scans them, and pairs each game with its downloaded cover if any.
 */
public class GameLibrary {
    private static final String[] EXTENSIONS = {"gdi", "cdi", "chd", "cue", "zip", "7z", "lst", "cdi"};
    private static final int MAX_DEPTH = 4;
    private static final int MAX_GAMES = 2000;

    /** Folder where Flycast keeps emu.cfg and its data. */
    public static File homeDir(Context context) {
        String home = null;
        try {
            home = PreferenceManager.getDefaultSharedPreferences(context).getString("home_directory", null);
        } catch (Exception ignored) {
        }
        if (home != null && !home.isEmpty()) {
            File f = new File(home);
            if (f.isDirectory())
                return f;
        }
        File ext = context.getExternalFilesDir(null);
        if (ext != null && new File(ext, "emu.cfg").exists())
            return ext;
        File internal = context.getFilesDir();
        if (new File(internal, "emu.cfg").exists())
            return internal;
        return ext != null ? ext : internal;
    }

    /** Game folders listed in emu.cfg. */
    public static List<String> contentPaths(File home) {
        List<String> result = new ArrayList<>();
        File cfg = new File(home, "emu.cfg");
        if (!cfg.isFile())
            return result;
        String value = null;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(cfg), "UTF-8"));
            boolean inConfig = false;
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("[")) {
                    inConfig = t.equalsIgnoreCase("[config]");
                } else if (inConfig) {
                    int eq = t.indexOf('=');
                    if (eq > 0 && t.substring(0, eq).trim().equals("Dreamcast.ContentPath")) {
                        value = t.substring(eq + 1).trim();
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (r != null)
                    r.close();
            } catch (Exception ignored) {
            }
        }
        if (value == null || value.isEmpty())
            return result;
        return splitPaths(value);
    }

    /** Same rules as Flycast: items separated by ';', items containing ';' are quoted with "" escaping. */
    static List<String> splitPaths(String paths) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = paths.length();
        while (i < n) {
            if (paths.charAt(i) == '"') {
                StringBuilder v = new StringBuilder();
                i++;
                while (i < n) {
                    char c = paths.charAt(i);
                    if (c == '"') {
                        if (i + 1 >= n) {
                            i = n + 1;
                            break;
                        }
                        char nx = paths.charAt(i + 1);
                        if (nx == '"') {
                            v.append('"');
                            i += 2;
                        } else if (nx == ';') {
                            i += 2;
                            break;
                        } else {
                            v.append(c);
                            i++;
                        }
                    } else {
                        v.append(c);
                        i++;
                    }
                }
                out.add(v.toString());
            } else {
                int end = paths.indexOf(';', i);
                if (end < 0)
                    end = n;
                if (end > i)
                    out.add(paths.substring(i, end));
                i = end + 1;
            }
        }
        return out;
    }

    /** Scans every folder of the emulator configuration. Must be called off the UI thread. */
    public static List<DashboardStore.Game> scanAll(Context context) {
        File home = homeDir(context);
        Map<String, String> covers = loadCovers(home);
        Map<String, DashboardStore.Game> found = new LinkedHashMap<>();
        for (String path : contentPaths(home)) {
            if (found.size() >= MAX_GAMES)
                break;
            List<DashboardStore.Game> games = new ArrayList<>();
            if (path.startsWith("content://")) {
                games = GameScanner.scan(context, Uri.parse(path));
            } else {
                scanDir(new File(path), 0, games);
            }
            for (DashboardStore.Game g : games) {
                if (!found.containsKey(g.uri))
                    found.put(g.uri, withCover(g, covers));
            }
        }
        List<DashboardStore.Game> list = new ArrayList<>(found.values());
        sort(list);
        return list;
    }

    /** Adds covers to games that do not have one yet. */
    public static List<DashboardStore.Game> attachCovers(Context context, List<DashboardStore.Game> games) {
        Map<String, String> covers = loadCovers(homeDir(context));
        List<DashboardStore.Game> out = new ArrayList<>();
        for (DashboardStore.Game g : games)
            out.add(g.cover != null ? g : withCover(g, covers));
        return out;
    }

    private static DashboardStore.Game withCover(DashboardStore.Game g, Map<String, String> covers) {
        String c = covers.get(key(fileNameOf(g)));
        if (c == null)
            c = covers.get(key(g.name));
        return c == null ? g : new DashboardStore.Game(g.uri, g.name, c);
    }

    private static String fileNameOf(DashboardStore.Game g) {
        String u = g.uri;
        try {
            u = Uri.decode(u);
        } catch (Exception ignored) {
        }
        int slash = Math.max(u.lastIndexOf('/'), u.lastIndexOf(':'));
        return slash >= 0 ? u.substring(slash + 1) : u;
    }

    private static String key(String name) {
        if (name == null)
            return "";
        String n = DashboardStore.stripExtension(name);
        return n.toLowerCase(Locale.ROOT).trim();
    }

    /** Reads the cover metadata the emulator saved when it downloaded box art. */
    private static Map<String, String> loadCovers(File home) {
        Map<String, String> map = new HashMap<>();
        File[] dirs = {new File(home, "data/boxart"), new File(home, "boxart")};
        for (File dir : dirs) {
            File[] files = dir.listFiles();
            if (files == null)
                continue;
            for (File f : files) {
                if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".json"))
                    continue;
                try {
                    String text = readAll(f);
                    JSONObject o = new JSONObject(text);
                    String fileName = o.optString("file_name", "");
                    String art = o.optString("boxart_path", "");
                    if (fileName.isEmpty() || art.isEmpty())
                        continue;
                    File img = new File(art);
                    if (!img.isAbsolute())
                        img = new File(dir, art);
                    if (img.isFile())
                        map.put(key(fileName), img.getAbsolutePath());
                } catch (Exception ignored) {
                }
            }
        }
        return map;
    }

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static void scanDir(File dir, int depth, List<DashboardStore.Game> out) {
        if (depth > MAX_DEPTH || out.size() >= MAX_GAMES)
            return;
        File[] files = dir.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanDir(f, depth + 1, out);
            } else if (isGameFile(f.getName())) {
                out.add(new DashboardStore.Game(f.getAbsolutePath(), DashboardStore.stripExtension(f.getName())));
            }
        }
    }

    static boolean isGameFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0)
            return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : EXTENSIONS) {
            if (e.equals(ext))
                return true;
        }
        return false;
    }

    /** Merges lists, keeping the first occurrence of each URI. Sorted by name. */
    public static List<DashboardStore.Game> merge(List<DashboardStore.Game> a, List<DashboardStore.Game> b) {
        Map<String, DashboardStore.Game> m = new LinkedHashMap<>();
        for (DashboardStore.Game g : a)
            m.put(g.uri, g);
        for (DashboardStore.Game g : b) {
            DashboardStore.Game old = m.get(g.uri);
            if (old == null || (old.cover == null && g.cover != null))
                m.put(g.uri, g);
        }
        List<DashboardStore.Game> list = new ArrayList<>(m.values());
        sort(list);
        return list;
    }

    private static void sort(List<DashboardStore.Game> list) {
        Collections.sort(list, new Comparator<DashboardStore.Game>() {
            @Override
            public int compare(DashboardStore.Game x, DashboardStore.Game y) {
                return x.name.compareToIgnoreCase(y.name);
            }
        });
    }
}
