package org.openttd.sdl;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.Os;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * SDLActivity 的 OpenTTD 包装层。
 *
 * 负责三件事：
 *  1. 首次启动把 APK assets/data/ 解压到 app 私有目录 files/.opttd/
 *     （OpenTTD 的 PERSONAL_DIR=".opttd"，HOME=filesDir）；
 *  2. Java 未捕获异常写日志到 sdcard：Download/OpenTTD/OpenTTD-crash-*.txt；
 *  3. 提前加载 native 库并安装 native 信号崩溃处理器（写同一份日志），
 *     捕获 SIGSEGV/SIGABRT 等，并顺带把 native stderr 重定向进日志。
 */
public class MainActivity extends SDLActivity {
    private static final String TAG = "OpenTTD";
    private static final String APP_VERSION = "OpenTTD 15.3 (versionCode 6)";
    private static final String ASSET_ROOT = "data";
    private static final String DATA_SUBDIR = ".openttd";
    private static final String ASSET_VERSION = "15.3-5";

    private static ParcelFileDescriptor sCrashPfd;
    private static OutputStream sCrashOut;
    private static String sCrashPath = "(not created)";

    private static native void nativeSetupCrashHandler(int fd, String appVersion);
    private static native void nativeLogMarker(String msg);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setupCrashLog();
        logLine("=== OpenTTD app onCreate ===");
        logLine("crash log path: " + sCrashPath);

        try {
            Os.setenv("HOME", getFilesDir().getAbsolutePath(), true);
            logLine("HOME=" + getFilesDir().getAbsolutePath());
        } catch (Throwable t) {
            logLine("setenv HOME failed: " + Log.getStackTraceString(t));
        }

        try {
            extractAssetsIfNeeded();
            logLine("assets extracted/up-to-date");
            File gf = new File(dataDir(), "baseset/OpenGFX/opengfx.obg");
            logLine("check baseset/OpenGFX/opengfx.obg: exists=" + gf.isFile() + " size=" + gf.length());
            File langEn = new File(dataDir(), "lang/english.lng");
            logLine("check lang/english.lng: exists=" + langEn.isFile() + " size=" + langEn.length());
            File baseDir = new File(dataDir(), "baseset");
            String[] baseEntries = baseDir.list();
            logLine("baseset entries: " + (baseEntries == null ? "null" : baseEntries.length));
        } catch (Throwable t) {
            logLine("asset extraction FAILED: " + Log.getStackTraceString(t));
        }

        // 配置中文界面与内置 CJK 字体（FreeType + NotoSansCJK 子集）。
        ensureChineseConfig();

        // 提前加载 native 库，让崩溃处理器尽早生效（SDLActivity 之后还会再 load 一次，幂等）
        try {
            System.loadLibrary("SDL2");
            System.loadLibrary("main");
            logLine("native libs loaded");
            if (sCrashPfd != null) {
                nativeSetupCrashHandler(sCrashPfd.getFd(), APP_VERSION);
                nativeLogMarker("[java] native crash handler installed");
            } else {
                logLine("crash fd unavailable; native handler not installed");
            }
        } catch (Throwable t) {
            logLine("FATAL loadLibrary failed: " + Log.getStackTraceString(t));
        }

        super.onCreate(savedInstanceState);
        logLine("super.onCreate returned");
    }

    // ------------------------------------------------------------- language

    private static final String CJK_FONT = "baseset/OpenTTD-CJK.otf";

    /** 配置中文界面 + 打包进去的 CJK 字体（FreeType 路径）。 */
    private void ensureChineseConfig() {
        try {
            File cfg = new File(dataDir(), "openttd.cfg");
            String content = cfg.isFile() ? readText(cfg) : "";
            content = setIniKey(content, "language", "simplified_chinese.lng");
            content = setIniKey(content, "small_font", CJK_FONT);
            content = setIniKey(content, "medium_font", CJK_FONT);
            content = setIniKey(content, "large_font", CJK_FONT);
            content = setIniKey(content, "mono_font", CJK_FONT);
            writeText(cfg, content);
            logLine("cfg: language=simplified_chinese.lng font=" + CJK_FONT);
        } catch (Throwable t) {
            logLine("ensureChineseConfig failed: " + Log.getStackTraceString(t));
        }
    }

    /** 在 ini 文本里设置 key=value；没有该行就插到 [misc] 段下，没有 [misc] 就新建。 */
    private static String setIniKey(String content, String key, String value) {
        String[] lines = content.split("\\r?\\n", -1);
        boolean replaced = false;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            int eq = t.indexOf('=');
            if (eq > 0 && t.substring(0, eq).trim().equals(key)) {
                lines[i] = key + " = " + value;
                replaced = true;
            }
        }
        if (!replaced) {
            int misc = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().equalsIgnoreCase("[misc]")) { misc = i; break; }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
                if (i == misc) sb.append(key).append(" = ").append(value).append('\n');
            }
            if (misc < 0) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                sb.append("[misc]\n").append(key).append(" = ").append(value).append('\n');
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append('\n');
        return sb.toString();
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    private static void writeText(File f, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }


    // ---------------------------------------------------------------- crash log

    private void setupCrashLog() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String name = "OpenTTD-crash-" + stamp + ".txt";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OpenTTD");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    sCrashPfd = getContentResolver().openFileDescriptor(uri, "rw");
                    sCrashPath = "/sdcard/Download/OpenTTD/" + name;
                }
            }
            if (sCrashPfd != null) {
                sCrashOut = new FileOutputStream(sCrashPfd.getFileDescriptor());
            } else {
                File dir = new File(Environment.getExternalStorageDirectory(), "Download/OpenTTD");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir failed: " + dir);
                File f = new File(dir, name);
                sCrashOut = new FileOutputStream(f, true);
                sCrashPath = f.getAbsolutePath();
            }
        } catch (Throwable t) {
            try {
                File f = new File(getFilesDir(), name);
                sCrashOut = new FileOutputStream(f, true);
                sCrashPath = f.getAbsolutePath();
            } catch (Throwable ignored) {
                sCrashPath = "(failed to create)";
            }
        }
        installJavaCrashHandler();
    }

    private void installJavaCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            logLine("=== JAVA UNCAUGHT EXCEPTION ===");
            logLine("thread: " + thread.getName());
            logLine(Log.getStackTraceString(error));
            try {
                if (sCrashOut != null) sCrashOut.flush();
            } catch (Throwable ignored) { }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private static synchronized void logLine(String msg) {
        try {
            if (sCrashOut != null) {
                sCrashOut.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
                sCrashOut.flush();
            }
        } catch (Throwable ignored) { }
        try {
            if (msg.length() > 3000) {
                Log.i(TAG, msg.substring(0, 3000) + " ...(truncated)");
            } else {
                Log.i(TAG, msg);
            }
        } catch (Throwable ignored) { }
    }

    // -------------------------------------------------------------- assets/data

    private File dataDir() {
        return new File(getFilesDir(), DATA_SUBDIR);
    }

    private boolean assetsUpToDate() {
        File stamp = new File(dataDir(), ".assets-version");
        if (!stamp.isFile()) return false;
        try (InputStream in = new FileInputStream(stamp)) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            return n > 0 && new String(buf, 0, n, StandardCharsets.UTF_8).trim().equals(ASSET_VERSION);
        } catch (IOException e) {
            return false;
        }
    }

    private void extractAssetsIfNeeded() throws IOException {
        if (assetsUpToDate()) return;

        File base = dataDir();
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IOException("cannot create data dir: " + base);
        }
        copyAssetDir(ASSET_ROOT, base);
        try (OutputStream out = new FileOutputStream(new File(base, ".assets-version"))) {
            out.write(ASSET_VERSION.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void copyAssetDir(String assetPath, File targetDir) throws IOException {
        String[] children = getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            copyAssetFile(assetPath, targetDir);
            return;
        }
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            throw new IOException("cannot create dir: " + targetDir);
        }
        for (String child : children) {
            String childAsset = assetPath + "/" + child;
            String[] grandChildren = getAssets().list(childAsset);
            if (grandChildren != null && grandChildren.length > 0) {
                copyAssetDir(childAsset, new File(targetDir, child));
            } else {
                copyAssetFile(childAsset, new File(targetDir, child));
            }
        }
    }

    private void copyAssetFile(String assetPath, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create dir: " + parent);
        }
        try (InputStream in = getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
