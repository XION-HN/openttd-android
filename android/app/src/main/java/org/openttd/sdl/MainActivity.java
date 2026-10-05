package org.openttd.sdl;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.Settings;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * SDLActivity 的 OpenTTD 包装层。
 *
 * 数据/存档目录优先级：
 *   1. /sdcard/OpenTTD            （需要「所有文件访问权限」，方便文件管理器管理）
 *   2. /sdcard/Android/data/.../files/OpenTTD （无需权限，但目录较深）
 *   3. app 私有目录 .openttd      （兜底）
 *
 * 首次启动把 APK assets/data/ 解压到该目录，并自动配置：
 *   - 中文界面 + 内置 CJK 字体
 *   - 内置「中国地名」NewGRF（默认对新游戏生效，town_name = 21）
 */
public class MainActivity extends SDLActivity {
    private static final String TAG = "OpenTTD";
    private static final String APP_VERSION = "OpenTTD 15.3 (versionCode 12)";
    private static final String ASSET_ROOT = "data";
    private static final String ASSET_VERSION = "15.3-8";
    private static final String CJK_FONT = "baseset/OpenTTD-CJK.otf";
    private static final String TOWN_NAME_GRF = "Chinese_True_Town_Names.grf";
    private static final String OLD_TOWN_NAME_GRF = "chinese_town_names.grf";
    private static final String PUBLIC_DATA_DIR = "OpenTTD";

    private static ParcelFileDescriptor sCrashPfd;
    private static OutputStream sCrashOut;
    private static String sCrashPath = "(not created)";
    private static File sDataDir;

    private static native void nativeSetupCrashHandler(int fd, String appVersion);
    private static native void nativeLogMarker(String msg);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setupCrashLog();
        logLine("=== OpenTTD app onCreate ===");
        logLine("crash log path: " + sCrashPath);

        sDataDir = resolveDataDir();
        logLine("data dir: " + sDataDir.getAbsolutePath());
        try {
            Os.setenv("HOME", sDataDir.getAbsolutePath(), true);
            Os.setenv("OTTD_PERSONAL_DIR", sDataDir.getAbsolutePath(), true);
        } catch (Throwable t) {
            logLine("setenv failed: " + Log.getStackTraceString(t));
        }

        try {
            extractAssetsIfNeeded();
            logLine("assets extracted/up-to-date");
            File gf = new File(sDataDir, "baseset/OpenGFX/opengfx.obg");
            logLine("check opengfx.obg: exists=" + gf.isFile() + " size=" + gf.length());
            File grf = new File(sDataDir, "newgrf/" + TOWN_NAME_GRF);
            logLine("check town name grf: exists=" + grf.isFile() + " size=" + grf.length());
        } catch (Throwable t) {
            logLine("asset extraction FAILED: " + Log.getStackTraceString(t));
        }

        ensureChineseConfig();

        try {
            System.loadLibrary("SDL2");
            System.loadLibrary("main");
            logLine("native libs loaded");
            if (sCrashPfd != null) {
                nativeSetupCrashHandler(sCrashPfd.getFd(), APP_VERSION);
                nativeLogMarker("[java] native crash handler installed");
            }
        } catch (Throwable t) {
            logLine("FATAL loadLibrary failed: " + Log.getStackTraceString(t));
        }

        super.onCreate(savedInstanceState);
        logLine("super.onCreate returned");

        // 后台做个 DNS+TCP 自检（联网模组下载依赖内容服务器）
        new Thread(() -> {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("content.openttd.org", 3978), 8000);
                logLine("[net] content.openttd.org:3978 OK");
            } catch (Throwable t) {
                logLine("[net] check failed: " + t);
            }
        }, "ottd-netcheck").start();

        maybeAskForStoragePermission();
    }

    // --------------------------------------------------------------- storage

    private File resolveDataDir() {
        boolean granted = false;
        if (Build.VERSION.SDK_INT >= 30) {
            granted = Environment.isExternalStorageManager();
        } else {
            granted = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        if (granted) {
            return new File(Environment.getExternalStorageDirectory(), PUBLIC_DATA_DIR);
        }
        File ext = getExternalFilesDir(null);
        if (ext != null) return new File(ext, PUBLIC_DATA_DIR);
        return new File(getFilesDir(), ".openttd");
    }

    private void maybeAskForStoragePermission() {
        if (Build.VERSION.SDK_INT < 30) return;
        if (Environment.isExternalStorageManager()) return;
        try {
            new AlertDialog.Builder(this)
                    .setTitle("存档目录 / Storage")
                    .setMessage("授予「所有文件访问权限」后，OpenTTD 的存档与配置会放在 /sdcard/OpenTTD，方便用文件管理器管理；\n\n授权后请重新打开 OpenTTD。未授权时会暂存在 Android/data 目录。")
                    .setPositiveButton("去授权", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Throwable t) {
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            } catch (Throwable ignored) { }
                        }
                    })
                    .setNegativeButton("稍后", null)
                    .show();
        } catch (Throwable t) {
            logLine("storage dialog failed: " + t);
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
            try { if (sCrashOut != null) sCrashOut.flush(); } catch (Throwable ignored) { }
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
            Log.i(TAG, msg.length() > 3000 ? msg.substring(0, 3000) + " ...(truncated)" : msg);
        } catch (Throwable ignored) { }
    }

    // -------------------------------------------------------------- assets/data

    private boolean assetsUpToDate() {
        File stamp = new File(sDataDir, ".assets-version");
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
        if (!sDataDir.isDirectory() && !sDataDir.mkdirs()) {
            throw new IOException("cannot create data dir: " + sDataDir);
        }
        copyAssetDir(ASSET_ROOT, sDataDir);
        try (OutputStream out = new FileOutputStream(new File(sDataDir, ".assets-version"))) {
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
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // -------------------------------------------------------------- openttd.cfg

    private void ensureChineseConfig() {
        try {
            File cfg = new File(sDataDir, "openttd.cfg");
            String content = cfg.isFile() ? readText(cfg) : "";
            // 32bpp 画质模组需要 32bpp blitter
            content = setIniKey(content, "misc", "blitter", "32bpp-optimized");
            content = setIniKey(content, "misc", "language", "simplified_chinese.lng");
            content = setIniKey(content, "misc", "small_font", CJK_FONT);
            content = setIniKey(content, "misc", "medium_font", CJK_FONT);
            content = setIniKey(content, "misc", "large_font", CJK_FONT);
            content = setIniKey(content, "misc", "mono_font", CJK_FONT);
            // 内置 Chinese True Town Names：默认加入新游戏，并使用它作为地名生成器
            content = removeIniKeyContaining(content, "newgrf", OLD_TOWN_NAME_GRF);
            if (!hasIniKeyNamed(content, "newgrf", TOWN_NAME_GRF)) {
                content = setIniKey(content, "newgrf", TOWN_NAME_GRF, null);
            }
            content = setIniKey(content, "game_creation", "town_name", "21");
            writeText(cfg, content);
            logLine("cfg: zh-CN + CJK font + town name grf");
        } catch (Throwable t) {
            logLine("ensureChineseConfig failed: " + Log.getStackTraceString(t));
        }
    }

    /** 在 ini 的 [section] 下设置 key=value；value 为 null 时写成空值。 */
    private static String setIniKey(String content, String section, String key, String value) {
        List<String> lines = new ArrayList<>();
        for (String l : content.split("\\r?\\n", -1)) lines.add(l);
        String header = "[" + section + "]";
        String newLine = value == null ? (key + " =") : (key + " = " + value);

        int secStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equalsIgnoreCase(header)) { secStart = i; break; }
        }
        if (secStart < 0) {
            if (lines.isEmpty() || !lines.get(lines.size() - 1).isEmpty()) lines.add("");
            lines.add(header);
            lines.add(newLine);
            return joinLines(lines);
        }
        int secEnd = lines.size();
        for (int i = secStart + 1; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("[") && t.endsWith("]")) { secEnd = i; break; }
        }
        for (int i = secStart + 1; i < secEnd; i++) {
            String t = lines.get(i).trim();
            int eq = t.indexOf('=');
            String k = eq >= 0 ? t.substring(0, eq).trim() : t;
            if (k.equals(key)) {
                lines.set(i, newLine);
                return joinLines(lines);
            }
        }
        lines.add(secEnd, newLine);
        return joinLines(lines);
    }

    /** ini 某个 section 下是否有名为 filename、或以 "|filename" 结尾的 key。 */
    private static boolean hasIniKeyNamed(String content, String section, String filename) {
        boolean in = false;
        for (String l : content.split("\\r?\\n", -1)) {
            String t = l.trim();
            if (t.equalsIgnoreCase("[" + section + "]")) { in = true; continue; }
            if (in && t.startsWith("[") && t.endsWith("]")) break;
            if (!in) continue;
            int eq = t.indexOf('=');
            String k = eq >= 0 ? t.substring(0, eq).trim() : t;
            if (k.equals(filename) || k.endsWith("|" + filename)) return true;
        }
        return false;
    }

    /** 删除 ini 某个 section 下所有“包含”指定文本的 key。 */
    private static String removeIniKeyContaining(String content, String section, String needle) {
        List<String> lines = new ArrayList<>();
        for (String l : content.split("\\r?\\n", -1)) lines.add(l);
        String header = "[" + section + "]";
        int secStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equalsIgnoreCase(header)) { secStart = i; break; }
        }
        if (secStart < 0) return content;
        int secEnd = lines.size();
        for (int i = secStart + 1; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("[") && t.endsWith("]")) { secEnd = i; break; }
        }
        for (int i = secEnd - 1; i > secStart; i--) {
            if (lines.get(i).contains(needle)) lines.remove(i);
        }
        return joinLines(lines);
    }

    /** 删除 ini 某个 section 下的 key（存在才删）。 */
    private static String removeIniKey(String content, String section, String key) {
        List<String> lines = new ArrayList<>();
        for (String l : content.split("\\r?\\n", -1)) lines.add(l);
        String header = "[" + section + "]";
        int secStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equalsIgnoreCase(header)) { secStart = i; break; }
        }
        if (secStart < 0) return content;
        int secEnd = lines.size();
        for (int i = secStart + 1; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("[") && t.endsWith("]")) { secEnd = i; break; }
        }
        for (int i = secEnd - 1; i > secStart; i--) {
            String t = lines.get(i).trim();
            int eq = t.indexOf('=');
            String k = eq >= 0 ? t.substring(0, eq).trim() : t;
            if (k.equals(key)) lines.remove(i);
        }
        return joinLines(lines);
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
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
}
