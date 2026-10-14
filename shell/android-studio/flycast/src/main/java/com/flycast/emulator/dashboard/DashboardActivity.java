package com.flycast.emulator.dashboard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.window.OnBackInvokedDispatcher;
import android.widget.Toast;

import com.flycast.emulator.NativeGLActivity;
import com.flycast.emulator.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Console-style start screen. Games are started by handing their URI to the regular Flycast
 * activity, so every emulator setting (video resolution, controls, ...) keeps working unchanged.
 */
public class DashboardActivity extends Activity {
    private static final int REQ_GAMES = 7101;
    private static final int REQ_FOLDER = 7102;
    private static final int REQ_BACKGROUND = 7103;
    private static final int REQ_MUSIC = 7104;
    private static final int REQ_CLICK = 7105;
    private static final int REQ_PLAY = 7106;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DashboardStore store;
    private SoundManager sounds;
    private DashboardView view;
    private volatile boolean scanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new DashboardStore(this);
        sounds = new SoundManager(this, store);

        view = new DashboardView(this);
        view.setHints(getString(R.string.dash_hint_select), getString(R.string.dash_hint_back),
                getString(R.string.dash_hint_tabs));
        view.setListener(new DashboardView.Listener() {
            @Override
            public void onMove() {
                sounds.playMove();
            }

            @Override
            public void onSelect() {
                sounds.playSelect();
            }

            @Override
            public void onBack() {
                sounds.playBack();
            }

            @Override
            public void onExit() {
                finish();
            }
        });
        setContentView(view);
        view.requestFocus();

        // Back gesture / button on Android 13+ does not always arrive as a key event
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, new android.window.OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() {
                            view.handleBack();
                        }
                    });
        }

        sounds.reloadClickSounds();
        loadBackground();
        rebuild();
        rescan(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        view.requestFocus();
        sounds.startMusic();
        // games may have been added in the emulator while we were away
        rescan(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sounds.pauseMusic();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sounds.release();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus)
            hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onBackPressed() {
        // Used on Android 12 and older
        view.handleBack();
    }

    // ---- content ----

    private void rebuild() {
        List<DashboardView.Tab> tabs = new ArrayList<>();

        // Games
        DashboardView.Tab games = new DashboardView.Tab(getString(R.string.dash_tab_games), DashboardView.ICON_GAMES);
        games.emptyText = getString(R.string.dash_empty_games);
        for (final DashboardStore.Game g : allGames()) {
            DashboardView.Item gi = new DashboardView.Item(g.name, getString(R.string.dash_play), true, new Runnable() {
                @Override
                public void run() {
                    launchGame(g.uri);
                }
            });
            gi.cover = g.cover;
            games.items.add(gi);
        }
        if (scanning && games.items.isEmpty())
            games.emptyText = getString(R.string.dash_scanning);
        tabs.add(games);

        // Add games
        DashboardView.Tab add = new DashboardView.Tab(getString(R.string.dash_tab_add), DashboardView.ICON_ADD);
        add.items.add(item(R.string.dash_add_files, R.string.dash_add_files_desc, new Runnable() {
            @Override
            public void run() {
                pickGameFiles();
            }
        }));
        add.items.add(item(R.string.dash_add_folder, R.string.dash_add_folder_desc, new Runnable() {
            @Override
            public void run() {
                pickGameFolder();
            }
        }));
        add.items.add(item(R.string.dash_rescan, R.string.dash_rescan_desc, new Runnable() {
            @Override
            public void run() {
                rescan(true);
            }
        }));
        add.items.add(item(R.string.dash_clear, R.string.dash_clear_desc, new Runnable() {
            @Override
            public void run() {
                store.clearGames();
                toast(getString(R.string.dash_toast_cleared));
                rebuild();
            }
        }));
        tabs.add(add);

        // Files
        DashboardView.Tab files = new DashboardView.Tab(getString(R.string.dash_tab_files), DashboardView.ICON_FILES);
        files.items.add(item(R.string.dash_files_browse, R.string.dash_files_browse_desc, new Runnable() {
            @Override
            public void run() {
                openFlycast();
            }
        }));
        files.items.add(item(R.string.dash_files_pick, R.string.dash_files_pick_desc, new Runnable() {
            @Override
            public void run() {
                pickAndPlay();
            }
        }));
        tabs.add(files);

        // Settings
        DashboardView.Tab settings = new DashboardView.Tab(getString(R.string.dash_tab_settings), DashboardView.ICON_SETTINGS);
        settings.items.add(item(R.string.dash_bg, R.string.dash_bg_desc, new Runnable() {
            @Override
            public void run() {
                pickMedia("image/*", REQ_BACKGROUND);
            }
        }));
        settings.items.add(item(R.string.dash_bg_reset, R.string.dash_bg_reset_desc, new Runnable() {
            @Override
            public void run() {
                store.resetBackground();
                loadBackground();
            }
        }));
        settings.items.add(item(R.string.dash_music, R.string.dash_music_desc, new Runnable() {
            @Override
            public void run() {
                pickMedia("audio/*", REQ_MUSIC);
            }
        }));
        settings.items.add(new DashboardView.Item(
                getString(R.string.dash_music_state, onOff(store.isMusicOn())),
                getString(R.string.dash_music_state_desc), false, new Runnable() {
            @Override
            public void run() {
                store.setMusicOn(!store.isMusicOn());
                sounds.updateMusic();
                rebuild();
            }
        }));
        settings.items.add(new DashboardView.Item(
                getString(R.string.dash_volume, store.getVolume()),
                getString(R.string.dash_volume_desc), false, new Runnable() {
            @Override
            public void run() {
                int v = store.getVolume() + 20;
                store.setVolume(v > 100 ? 0 : v);
                sounds.updateMusic();
                rebuild();
            }
        }));
        settings.items.add(item(R.string.dash_music_reset, R.string.dash_music_reset_desc, new Runnable() {
            @Override
            public void run() {
                store.resetMusic();
                sounds.updateMusic();
            }
        }));
        settings.items.add(item(R.string.dash_click, R.string.dash_click_desc, new Runnable() {
            @Override
            public void run() {
                pickMedia("audio/*", REQ_CLICK);
            }
        }));
        settings.items.add(new DashboardView.Item(
                getString(R.string.dash_click_state, onOff(store.isSfxOn())),
                getString(R.string.dash_click_state_desc), false, new Runnable() {
            @Override
            public void run() {
                store.setSfxOn(!store.isSfxOn());
                rebuild();
            }
        }));
        settings.items.add(item(R.string.dash_click_reset, R.string.dash_click_reset_desc, new Runnable() {
            @Override
            public void run() {
                store.resetClick();
                sounds.reloadClickSounds();
            }
        }));
        tabs.add(settings);

        // Emulator (Flycast's own menu: video resolution, controls, ...)
        DashboardView.Tab emu = new DashboardView.Tab(getString(R.string.dash_tab_emulator), DashboardView.ICON_EMULATOR);
        emu.items.add(item(R.string.dash_emu_open, R.string.dash_emu_open_desc, new Runnable() {
            @Override
            public void run() {
                openFlycast();
            }
        }));
        tabs.add(emu);

        view.setTabs(tabs);
    }

    // ---- game library (games the emulator already knows + games added here) ----

    private List<DashboardStore.Game> allGames() {
        List<DashboardStore.Game> scannedGames = GameLibrary.attachCovers(this, store.getScanned());
        List<DashboardStore.Game> manual = GameLibrary.attachCovers(this, store.getGames());
        return GameLibrary.merge(scannedGames, manual);
    }

    /** Re-reads the game folders from the emulator settings in the background. */
    private void rescan(final boolean announce) {
        if (scanning)
            return;
        scanning = true;
        if (announce)
            toast(getString(R.string.dash_toast_scanning));
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<DashboardStore.Game> found = new ArrayList<>();
                try {
                    found = GameLibrary.scanAll(DashboardActivity.this);
                    store.setScanned(found);
                } catch (Throwable ignored) {
                }
                final int count = found.size();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        if (isFinishing())
                            return;
                        rebuild();
                        if (announce)
                            toast(getString(R.string.dash_toast_found, count));
                    }
                });
            }
        }).start();
    }

    private DashboardView.Item item(int titleRes, int descRes, Runnable action) {
        return new DashboardView.Item(getString(titleRes), getString(descRes), false, action);
    }

    private String onOff(boolean on) {
        return getString(on ? R.string.dash_on : R.string.dash_off);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ---- launching ----

    private void launchGame(String uri) {
        sounds.playLaunch();
        Intent intent = new Intent(this, NativeGLActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    /** Opens the regular Flycast screen (game browser and its settings menu). */
    private void openFlycast() {
        startActivity(new Intent(this, NativeGLActivity.class));
    }

    // ---- pickers ----

    private void pickGameFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startPicker(i, REQ_GAMES);
    }

    private void pickAndPlay() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startPicker(i, REQ_PLAY);
    }

    private void pickGameFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startPicker(i, REQ_FOLDER);
    }

    private void pickMedia(String mime, int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        startPicker(i, request);
    }

    private void startPicker(Intent intent, int request) {
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.dash_toast_no_picker));
        }
    }

    private void keepAccess(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // some providers do not support persistable grants; the game may need to be added again later
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null)
            return;

        switch (requestCode) {
            case REQ_GAMES: {
                List<Uri> uris = new ArrayList<>();
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++)
                        uris.add(clip.getItemAt(i).getUri());
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                int added = 0;
                for (Uri uri : uris) {
                    keepAccess(uri);
                    if (store.addGame(uri.toString(), store.queryDisplayName(uri)))
                        added++;
                }
                toast(getString(R.string.dash_toast_added, added));
                rebuild();
                break;
            }
            case REQ_FOLDER: {
                final Uri tree = data.getData();
                if (tree == null)
                    break;
                keepAccess(tree);
                toast(getString(R.string.dash_toast_scanning));
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final List<DashboardStore.Game> found = GameScanner.scan(DashboardActivity.this, tree);
                        final int before = store.getGames().size();
                        store.addGames(found);
                        final int added = store.getGames().size() - before;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing())
                                    return;
                                toast(getString(R.string.dash_toast_added, added));
                                rebuild();
                            }
                        });
                    }
                }).start();
                break;
            }
            case REQ_PLAY: {
                Uri uri = data.getData();
                if (uri != null) {
                    keepAccess(uri);
                    launchGame(uri.toString());
                }
                break;
            }
            case REQ_BACKGROUND: {
                Uri uri = data.getData();
                if (uri != null && store.importBackground(uri))
                    loadBackground();
                else
                    toast(getString(R.string.dash_toast_failed));
                break;
            }
            case REQ_MUSIC: {
                Uri uri = data.getData();
                if (uri != null && store.importMusic(uri))
                    sounds.updateMusic();
                else
                    toast(getString(R.string.dash_toast_failed));
                break;
            }
            case REQ_CLICK: {
                Uri uri = data.getData();
                if (uri != null && store.importClick(uri)) {
                    sounds.reloadClickSounds();
                    sounds.playSelect();
                } else {
                    toast(getString(R.string.dash_toast_failed));
                }
                break;
            }
            default:
                break;
        }
    }

    // ---- background picture ----

    private void loadBackground() {
        final String path = store.getBackgroundPath();
        if (path == null) {
            view.setBackgroundBitmap(null);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = decodeScaled(path, 1920, 1080);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing())
                            view.setBackgroundBitmap(bmp);
                    }
                });
            }
        }).start();
    }

    private static Bitmap decodeScaled(String path, int maxW, int maxH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH)
                sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
