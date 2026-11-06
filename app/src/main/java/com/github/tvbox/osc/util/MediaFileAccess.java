package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.content.pm.PackageManager;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Stages only the selected file, not entire configuration/backup directories. */
public final class MediaFileAccess {
    private static final String TAG = "MediaFileAccess";
    private static final long MAX_SUBTITLE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 8L * 1024 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MediaFileAccess() { }

    public interface SubtitleResult {
        void complete(String path, String error);
    }

    public static void importSubtitle(Context context, Uri uri, SubtitleResult result) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            String path = null;
            String error = null;
            try {
                if (!"content".equals(uri.getScheme())) throw new IOException("请选择文档提供方中的字幕文件");
                String name = null;
                try (Cursor cursor = app.getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                }
                String extension = subtitleExtension(name);
                File directory = new File(app.getCacheDir(), "subtitles");
                try (InputStream input = app.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("字幕提供方没有返回内容");
                    path = copy(input, directory, extension, MAX_SUBTITLE_BYTES).getAbsolutePath();
                }
            } catch (IOException | SecurityException | IllegalArgumentException e) {
                Log.w(TAG, "Subtitle import failed", e);
                error = "字幕导入失败：" + e.getMessage();
            }
            String selected = path;
            String failure = error;
            MAIN.post(() -> result.complete(selected, failure));
        });
    }

    static String subtitleExtension(String name) throws IOException {
        if (name == null) throw new IOException("字幕缺少文件名");
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        if (!extension.matches("\\.(srt|ass|scc|stl|ttml)")) throw new IOException("不支持的字幕格式");
        return extension;
    }

    static File copy(InputStream input, File directory, String extension, long limit) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建媒体暂存目录");
        File target = File.createTempFile("selected-", extension, directory);
        boolean complete = false;
        try {
            try (FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                long count = 0;
                for (int read; (read = input.read(buffer)) != -1;) {
                    count += read;
                    if (count > limit) throw new IOException("所选文件超过导入大小限制");
                    output.write(buffer, 0, read);
                }
                if (count == 0) throw new IOException("所选文件为空");
            }
            complete = true;
            return target;
        } finally {
            if (!complete && !target.delete()) Log.w(TAG, "Could not remove incomplete media copy");
        }
    }

    /** Must run off the main thread for a local file. Content URIs keep their existing grant. */
    public static Uri readableVideoUri(Context context, String url) throws IOException {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        // A document grant may be forwarded; never manufacture access to an arbitrary provider URI.
        if ("content".equalsIgnoreCase(scheme)) {
            requireDocumentGrant(context, uri);
            return uri;
        }
        // Network/custom player protocols are kept byte-for-byte; only local files need staging.
        if (scheme != null && !"file".equalsIgnoreCase(scheme)) return uri;
        if (uri.getAuthority() != null && !uri.getAuthority().isEmpty()) throw new IOException("无效的本地媒体地址");
        String path = scheme == null ? url : uri.getPath();
        if (path == null || !new File(path).isAbsolute()) throw new IOException("本地媒体必须使用绝对路径");
        File file = new File(path).getCanonicalFile();
        // Never export private backups, preferences, databases, plugins or other app data.
        File workspace = new File(context.getFilesDir(), "config-workspace").getCanonicalFile();
        if (!inside(file, workspace) && !isPublicMediaFile(context, file)) {
            throw new IOException("此文件不可读或不在可分享的公共存储卷中");
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        if (!extension.matches("\\.(mp4|mkv|avi|mov|ts|m2ts|webm|flv|m4v|mpg|mpeg|wmv|3gp|mp3|m4a|flac|ogg|wav)")) {
            throw new IOException("此文件不是可分享的单文件媒体");
        }
        File staged;
        try (InputStream input = new FileInputStream(file)) {
            staged = copy(input, new File(context.getCacheDir(), "shared-media"), extension, MAX_VIDEO_BYTES);
        }
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", staged);
    }

    private static boolean inside(File file, File root) {
        return file.getPath().startsWith(root.getPath() + File.separator);
    }

    /** Public StorageManager APIs identify secondary volumes; a /storage prefix is not authority. */
    static boolean isPublicMediaFile(Context context, File file) throws IOException {
        file = file.getCanonicalFile();
        if (!file.isFile() || !file.canRead()) return false;
        // Even a permissive/OEM volume mapping must not authorize app-private data.
        if (inside(file, context.getFilesDir().getCanonicalFile().getParentFile())) return false;
        String path = file.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("/android/data/") || path.contains("/android/obb/")) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            StorageManager manager = context.getSystemService(StorageManager.class);
            StorageVolume volume = manager == null ? null : manager.getStorageVolume(file);
            if (volume == null || (!Environment.MEDIA_MOUNTED.equals(volume.getState())
                    && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState()))) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                File directory = volume.getDirectory();
                return directory != null && inside(file, directory.getCanonicalFile());
            }
            // API 24-29 exposes lookup by file, but no public getDirectory().
            return true;
        }
        // API 23 has no public secondary-volume lookup: use the document picker for those volumes.
        return inside(file, Environment.getExternalStorageDirectory().getCanonicalFile());
    }

    private static void requireDocumentGrant(Context context, Uri uri) throws IOException {
        if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw new IOException("没有所选媒体文档的读取授权，请重新选择文件");
        }
    }

    /** Main-thread-only mailbox. A paused owner keeps the result; old attempts/views cannot launch it. */
    public static final class PendingExternalPlayback {
        private long request;
        private long generation;
        private Runnable pending;

        public long begin() {
            cancel();
            return request;
        }

        public void complete(long request, long generation, Runnable launch) {
            if (request != this.request) return;
            this.generation = generation;
            pending = launch;
        }

        public void consume(long currentGeneration, boolean resumed) {
            if (pending == null) return;
            if (generation != currentGeneration) {
                cancel();
                return;
            }
            if (!resumed) return;
            Runnable launch = pending;
            cancel(); // Consume once, before launch can cause a reentrant lifecycle callback.
            launch.run();
        }

        public void cancel() {
            request++;
            pending = null;
        }
    }

    public static void grantRead(Intent intent, Uri uri) {
        if (!"content".equalsIgnoreCase(uri.getScheme())) return;
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (intent.getClipData() == null) intent.setClipData(ClipData.newRawUri("media", uri));
        else intent.getClipData().addItem(new ClipData.Item(uri));
    }

    public interface ExternalResult {
        void complete(String video, String subtitle, String error);
    }

    public static void prepareExternal(Context context, String video, String subtitle, ExternalResult result) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                String preparedVideo = readableVideoUri(app, video).toString();
                String preparedSubtitle = subtitle;
                if (subtitle != null && !subtitle.isEmpty()) {
                    Uri uri = Uri.parse(subtitle);
                    if (uri.getScheme() == null || "file".equalsIgnoreCase(uri.getScheme())) {
                        String path = uri.getScheme() == null ? subtitle : uri.getPath();
                        if (path == null || !new File(path).isAbsolute()) throw new IOException("无效的字幕路径");
                        File file = new File(path).getCanonicalFile();
                        if (!inside(file, new File(app.getCacheDir(), "subtitles").getCanonicalFile())
                                && !inside(file, new File(app.getFilesDir(), "config-workspace").getCanonicalFile())
                                && !isPublicMediaFile(app, file)) {
                            throw new IOException("此私有字幕不能分享");
                        }
                        File staged;
                        try (InputStream input = new FileInputStream(file)) {
                            staged = copy(input, new File(app.getCacheDir(), "shared-media"),
                                    subtitleExtension(file.getName()), MAX_SUBTITLE_BYTES);
                        }
                        preparedSubtitle = FileProvider.getUriForFile(app,
                                app.getPackageName() + ".fileprovider", staged).toString();
                    }
                }
                String finalSubtitle = preparedSubtitle;
                MAIN.post(() -> result.complete(preparedVideo, finalSubtitle, null));
            } catch (IOException | SecurityException | IllegalArgumentException e) {
                Log.w(TAG, "External media preparation failed", e);
                MAIN.post(() -> result.complete(null, null, e.getMessage()));
            }
        });
    }

    public static void openVideo(Activity activity, String url) {
        Context app = activity.getApplicationContext();
        IO.execute(() -> {
            try {
                Uri uri = readableVideoUri(app, url);
                MAIN.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    Intent view = new Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_DEFAULT);
                    view.setDataAndType(uri, "video/*");
                    grantRead(view, uri);
                    try {
                        activity.startActivity(Intent.createChooser(view, "Open Video with ..."));
                    } catch (android.content.ActivityNotFoundException | SecurityException e) {
                        Log.w(TAG, "External player could not open media", e);
                        Toast.makeText(activity, "无法打开外部播放器", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException | SecurityException | IllegalArgumentException e) {
                Log.w(TAG, "Could not prepare external media", e);
                MAIN.post(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed())
                        Toast.makeText(activity, "无法分享媒体：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
}
