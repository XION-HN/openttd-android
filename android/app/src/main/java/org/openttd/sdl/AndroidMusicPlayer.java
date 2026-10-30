package org.openttd.sdl;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * 用 Android 的 MediaPlayer 播放 OpenTTD 交给我们的音乐文件。
 *
 * OpenTTD 的音乐集里可以放 OGG/MP3/WAV 等真实音频文件（MIDI 播放不了，会静默失败）。
 * 播放、换曲、调音量都在单独的 HandlerThread 上串行执行，避免阻塞游戏主线程。
 */
public final class AndroidMusicPlayer {
    private static final String TAG = "OpenTTD-Music";
    private static final Object LOCK = new Object();
    /** 播放失败后让 OpenTTD 等一会儿再尝试下一首，避免疯狂重试。 */
    private static final long ERROR_BACKOFF_MS = 5000;

    private static HandlerThread sThread;
    private static Handler sHandler;
    private static MediaPlayer sPlayer;
    private static volatile boolean sPlaying;
    /** 每次 play/stop 自增，用来丢弃过期的异步任务。 */
    private static int sGeneration;

    private AndroidMusicPlayer() {
    }

    private static void ensureThreadLocked() {
        if (sHandler != null) return;
        sThread = new HandlerThread(TAG);
        sThread.start();
        sHandler = new Handler(sThread.getLooper());
    }

    /** 释放当前 MediaPlayer（必须在播放线程上调用）。 */
    private static void releasePlayerOnThread() {
        MediaPlayer player = sPlayer;
        sPlayer = null;
        if (player == null) return;
        try {
            player.reset();
        } catch (Exception ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
    }

    /** 播放指定文件，volume 为 0.0–1.0。 */
    public static void play(final String path, final float volume) {
        synchronized (LOCK) {
            ensureThreadLocked();
            final int generation = ++sGeneration;
            sPlaying = true;
            sHandler.post(() -> playOnThread(generation, path, volume));
        }
    }

    private static void playOnThread(int generation, String path, float volume) {
        synchronized (LOCK) {
            if (generation != sGeneration) return;
            releasePlayerOnThread();
        }

        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(path);
            player.setOnCompletionListener(mp -> {
                synchronized (LOCK) {
                    if (mp == sPlayer) sPlaying = false;
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                synchronized (LOCK) {
                    if (generation == sGeneration) {
                        // 保持 sPlaying 一小会儿，让 OpenTTD 别立刻切曲
                        sPlaying = true;
                        sHandler.postDelayed(() -> {
                            synchronized (LOCK) {
                                if (generation == sGeneration) sPlaying = false;
                            }
                        }, ERROR_BACKOFF_MS);
                    }
                    releasePlayerOnThread();
                }
                return true;
            });
            player.prepare();
            player.setVolume(volume, volume);
            player.start();

            synchronized (LOCK) {
                if (generation == sGeneration) {
                    sPlayer = player;
                } else {
                    try {
                        player.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to play: " + path, e);
            synchronized (LOCK) {
                if (generation == sGeneration) {
                    sPlaying = true;
                    sHandler.postDelayed(() -> {
                        synchronized (LOCK) {
                            if (generation == sGeneration) sPlaying = false;
                        }
                    }, ERROR_BACKOFF_MS);
                }
                releasePlayerOnThread();
            }
        }
    }

    /** 停止播放并释放播放器。 */
    public static void stop() {
        synchronized (LOCK) {
            sGeneration++;
            sPlaying = false;
            if (sHandler == null) {
                releasePlayerOnThread();
            } else {
                sHandler.post(() -> {
                    synchronized (LOCK) {
                        releasePlayerOnThread();
                    }
                });
            }
        }
    }

    /** 当前是否在播放。 */
    public static boolean isPlaying() {
        return sPlaying;
    }

    /** 调整音量，volume 为 0.0–1.0。 */
    public static void setVolume(final float volume) {
        synchronized (LOCK) {
            if (sHandler == null) return;
            sHandler.post(() -> {
                synchronized (LOCK) {
                    if (sPlayer == null) return;
                    try {
                        sPlayer.setVolume(volume, volume);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }
}
