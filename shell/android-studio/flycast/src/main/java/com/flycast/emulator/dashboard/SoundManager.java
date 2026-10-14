package com.flycast.emulator.dashboard;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;

import com.flycast.emulator.R;

/**
 * Background music (looping) and short UI sounds for the dashboard.
 * Built-in original sounds are bundled in res/raw; the user can replace the music and the click sound.
 */
public class SoundManager {
    private final Context context;
    private final DashboardStore store;

    private SoundPool pool;
    private int moveSound, selectSound, backSound, launchSound;
    private boolean customClick;
    private final java.util.Set<Integer> loaded = new java.util.HashSet<>();

    private MediaPlayer music;
    private String musicKey;
    private boolean started;

    public SoundManager(Context context, DashboardStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
    }

    // ---- UI sounds ----

    public void reloadClickSounds() {
        releasePool();
        loaded.clear();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build();
        pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                if (status == 0) {
                    synchronized (loaded) {
                        loaded.add(sampleId);
                    }
                }
            }
        });
        String custom = store.getClickPath();
        customClick = false;
        try {
            if (custom != null) {
                int id = pool.load(custom, 1);
                if (id != 0) {
                    customClick = true;
                    moveSound = id;
                    selectSound = id;
                }
            }
            if (!customClick) {
                moveSound = pool.load(context, R.raw.dashboard_move, 1);
                selectSound = pool.load(context, R.raw.dashboard_select, 1);
            }
            backSound = pool.load(context, R.raw.dashboard_back, 1);
            launchSound = pool.load(context, R.raw.dashboard_launch, 1);
        } catch (Exception ignored) {
        }
    }

    private void play(int id, float volume) {
        if (pool == null)
            reloadClickSounds();
        if (pool == null || id == 0 || !store.isSfxOn())
            return;
        boolean ok;
        synchronized (loaded) {
            ok = loaded.contains(id);
        }
        if (ok)
            pool.play(id, volume, volume, 1, 0, 1f);
    }

    public void playMove() {
        play(moveSound, customClick ? 0.6f : 0.8f);
    }

    public void playSelect() {
        play(selectSound, 1f);
    }

    public void playBack() {
        play(customClick ? selectSound : backSound, 0.9f);
    }

    public void playLaunch() {
        play(customClick ? selectSound : launchSound, 1f);
    }

    // ---- background music ----

    /** Call from onResume. */
    public void startMusic() {
        started = true;
        updateMusic();
    }

    /** Call from onPause (also when a game is launched). */
    public void pauseMusic() {
        started = false;
        if (music != null) {
            try {
                if (music.isPlaying())
                    music.pause();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    /** Re-evaluates the music state after the settings changed. */
    public void updateMusic() {
        String custom = store.getMusicPath();
        String key = custom != null ? custom : "builtin";
        boolean shouldPlay = started && store.isMusicOn();
        if (!shouldPlay) {
            if (music != null) {
                try {
                    if (music.isPlaying())
                        music.pause();
                } catch (IllegalStateException ignored) {
                }
            }
            return;
        }
        try {
            if (music == null || musicKey == null || !musicKey.equals(key)) {
                releaseMusic();
                music = createPlayer(custom);
                musicKey = key;
            }
            if (music == null)
                return;
            float vol = store.getVolume() / 100f;
            music.setVolume(vol, vol);
            if (!music.isPlaying())
                music.start();
        } catch (Exception e) {
            releaseMusic();
        }
    }

    private MediaPlayer createPlayer(String custom) {
        MediaPlayer mp = null;
        if (custom != null) {
            try {
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                mp.setDataSource(custom);
                mp.setLooping(true);
                mp.prepare();
                return mp;
            } catch (Exception e) {
                if (mp != null)
                    mp.release();
                mp = null;
            }
        }
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            AssetFileDescriptor afd = context.getResources().openRawResourceFd(R.raw.dashboard_ambient);
            try {
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } finally {
                afd.close();
            }
            mp.setLooping(true);
            mp.prepare();
            return mp;
        } catch (Exception e) {
            if (mp != null)
                mp.release();
            return null;
        }
    }

    private void releaseMusic() {
        if (music != null) {
            try {
                music.release();
            } catch (Exception ignored) {
            }
            music = null;
            musicKey = null;
        }
    }

    private void releasePool() {
        if (pool != null) {
            pool.release();
            pool = null;
        }
    }

    public void release() {
        releaseMusic();
        releasePool();
    }
}
