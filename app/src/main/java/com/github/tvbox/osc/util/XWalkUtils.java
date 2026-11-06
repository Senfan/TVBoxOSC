package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import org.xwalk.core.XWalkInitializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class XWalkUtils {

    private static XWalkInitializer xWalkInitializer = null;
    private static final String[] LIB_FILES = {"classes.dex", "icudtl.dat", "libxwalkcore.so",
            "xwalk.pak", "xwalk_100_percent.pak"};

    public interface XWalkState {
        void success();

        void fail();

        void ignore();
    }

    public static String downUrl() {
//        return String.format("https://download.01.org/crosswalk/releases/crosswalk/android/stable/23.53.589.4/%s/crosswalk-apks-23.53.589.4-%s.zip", getRuntimeAbi(), getRuntimeAbi());
//        return String.format("https://xwalk.herokuapp.com/crosswalk-apks-23.53.589.4-%s.zip", getRuntimeAbi(), getRuntimeAbi());
        return String.format("https://github.com/o0HalfLife0o/crosswalk/raw/master/releases/crosswalk/android/stable/23.53.589.4/%s/crosswalk-apks-23.53.589.4-%s.zip", getRuntimeAbi(), getRuntimeAbi());
    }

    public static String saveZipFile() {
        return String.format("crosswalk-apks-23.53.589.4-%s.zip", getRuntimeAbi());
    }

    public static synchronized boolean xWalkLibExist(Context context) {
        File prepared = null;
        try {
            File dir = new File(libExtractPath(context));
            if (!runtimeFilesExist(dir)) return false;
            File dex = new File(dir, "classes.dex");
            requireDex(dex);
            if (!DynamicCodeFiles.isReadOnlyRegularFile(dex)) {
                // Migrate legacy extracted DEX by copying, never by chmod after its original write.
                prepared = DynamicCodeFiles.prepareForLoading(dex, dir, "xwalk-dex", ".dex", null);
                DynamicCodeFiles.replacePreservingPrevious(prepared, dex);
            }
            return DynamicCodeFiles.isReadOnlyRegularFile(dex);
        } catch (Throwable e) {
            LOG.e("XWalk动态代码安全检查失败，拒绝加载: " + e.getMessage());
            return false;
        } finally {
            if (prepared != null && prepared.exists()) prepared.delete();
        }
    }

    public static synchronized void tryUseXWalk(Context context, XWalkState state) {
        if (!xWalkLibExist(context)) {
            if (new File(libExtractPath(context), "classes.dex").exists()) {
                LOG.e("XWalk组件不完整或DEX不能安全只读加载");
                state.fail();
            } else {
                state.ignore();
            }
            return;
        }
        if (xWalkInitializer == null) {
            xWalkInitializer = new XWalkInitializer(new XWalkInitializer.XWalkInitListener() {
                @Override
                public void onXWalkInitStarted() {

                }

                @Override
                public void onXWalkInitCancelled() {

                }

                @Override
                public void onXWalkInitFailed() {
                    if (state != null)
                        state.fail();
                }

                @Override
                public void onXWalkInitCompleted() {
                    if (state != null)
                        state.success();
                }
            }, context);
        }
        if (xWalkInitializer.isXWalkReady()) {
            state.success();
        } else {
            xWalkInitializer.initAsync();
        }
    }

    private static boolean checkEmbedded(Activity activity) {
        try {
            Class clazz = Class.forName("org.xwalk.core.XWalkCoreWrapper");
            if (clazz != null) {
                Constructor constructor = clazz.getDeclaredConstructor(new Class[]{Context.class, int.class});
                constructor.setAccessible(true);
                Object obj = constructor.newInstance(activity, -1);
                Method fe = clazz.getDeclaredMethod("findEmbeddedCore", new Class[]{});
                fe.setAccessible(true);
                if (!(boolean) fe.invoke(obj)) {
                    return false;
                }
            } else {
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean apkHadDown(Context context) {
        return new File(apkPath(context)).exists();
    }

    public static synchronized void unzipXWalkZip(Context context, String archive) throws Throwable {
        File candidate = null;
        try (ZipFile zf = new ZipFile(archive)) {
            ZipEntry runtime = null;
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.substring(name.lastIndexOf("/") + 1).equals("XWalkRuntimeLib.apk")) {
                    if (runtime != null) throw new IOException("XWalk压缩包包含重复运行组件");
                    runtime = entry;
                }
            }
            if (runtime == null) throw new IOException("XWalk压缩包缺少运行组件");
            CRC32 crc = new CRC32();
            try (InputStream input = new CheckedInputStream(zf.getInputStream(runtime), crc)) {
                candidate = DynamicCodeFiles.writeReadOnly(input, runtime.getSize(), context.getCacheDir(),
                        "xwalk-apk", ".apk", null);
            }
            if (runtime.getCrc() == -1 || crc.getValue() != runtime.getCrc()) {
                throw new IOException("XWalk运行组件CRC校验失败");
            }
            validateRuntimeApk(candidate);
            DynamicCodeFiles.replacePreservingPrevious(candidate, new File(apkPath(context)));
            LOG.i(apkPath(context));
        } finally {
            if (candidate != null && candidate.exists()) candidate.delete();
        }
        // The caller owns the downloaded ZIP; do not delete unrelated archives or old components.
    }

    public static String getRuntimeAbi() {
        String result = "arm";
        try {
            Class cls = Class.forName("org.xwalk.core.XWalkEnvironment");
            Method method = cls.getMethod("getRuntimeAbi");
            String obj = (String) method.invoke(null);
            switch (obj) {
                case "arm64-v8a": {
                    result = "arm64";
                    break;
                }
                case "x86": {
                    result = "x86";
                    break;
                }
                case "x86_64": {
                    result = "x86_64";
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    public static synchronized boolean extractXWalkLib(Context context) throws Throwable {
        File apk = null;
        File preparedDex = null;
        File stage = null;
        boolean published = false;
        try {
            File target = new File(libExtractPath(context));
            if (!independentPath(target)) throw new IOException("XWalk解压目录不是独立私有目录");
            target = target.getCanonicalFile();
            File parent = target.getParentFile();
            if (parent == null) throw new IOException("XWalk解压目录无效");
            stage = new File(parent, "xwalk-stage-" + UUID.randomUUID());
            if (!stage.mkdir()) throw new IOException("无法创建XWalk私有暂存目录");
            apk = DynamicCodeFiles.prepareForLoading(new File(apkPath(context)),
                    context.getDir("dynamic_xwalk", Context.MODE_PRIVATE), "xwalk-apk", ".apk", null);
            validateRuntimeApk(apk);
            Class cls = Class.forName("org.xwalk.core.XWalkDecompressor");
            Method method = cls.getMethod("extractResource", String.class, String.class);
            if (!(boolean) method.invoke(null, apk.getAbsolutePath(), stage.getAbsolutePath()) || !runtimeFilesExist(stage)) {
                throw new IOException("XWalk资源解压失败，保留旧组件");
            }
            File dex = new File(stage, "classes.dex");
            requireDex(dex);
            // Bundled XWalk writes a writable DEX. Copy the ACTUAL extracted DEX using the FD-first
            // sequence before publishing the fixed classes.dex path used by findDownloadedCore.
            preparedDex = DynamicCodeFiles.prepareForLoading(dex, stage, "xwalk-dex", ".dex", null);
            requireDex(preparedDex);
            verifyExtractedDex(apk, preparedDex);
            if (!dex.delete() || !preparedDex.renameTo(dex) || !DynamicCodeFiles.isReadOnlyRegularFile(dex)) {
                throw new IOException("XWalk DEX无法安全只读发布，拒绝加载");
            }
            File previous = new File(parent, target.getName() + ".previous-" + UUID.randomUUID());
            if (!target.renameTo(previous)) throw new IOException("无法保留旧XWalk组件");
            try {
                published = stage.renameTo(target);
                if (!published) throw new IOException("XWalk发布失败，回退旧组件");
            } finally {
                if (!published && !previous.renameTo(target)) {
                    throw new IOException("XWalk发布与回退失败，旧组件位于 " + previous);
                }
            }
            Toast.makeText(context, "解压XWalkView运行组件完成!", Toast.LENGTH_LONG).show();
            return true;
        } catch (Throwable e) {
            LOG.e("XWalk动态代码安全处理失败: " + e.getMessage());
            Toast.makeText(context, "XWalk组件不能安全只读加载，已停止", Toast.LENGTH_LONG).show();
            throw e;
        } finally {
            if (apk != null) apk.delete();
            if (!published && stage != null) {
                if (preparedDex != null) preparedDex.delete();
                for (String name : LIB_FILES) new File(stage, name).delete();
                stage.delete(); // Never recursively delete unknown files.
            }
        }
    }

    private static boolean runtimeFilesExist(File dir) throws IOException {
        if (!independentPath(dir)) return false;
        for (String name : LIB_FILES) {
            File file = new File(dir, name);
            if (!file.isFile() || file.length() == 0 || file.length() > DynamicCodeFiles.MAX_CODE_BYTES
                    || !independentPath(file)) return false;
        }
        return true;
    }

    private static boolean independentPath(File file) throws IOException {
        File absolute = file.getAbsoluteFile();
        File parent = absolute.getParentFile();
        return parent != null && new File(parent.getCanonicalFile(), absolute.getName()).equals(file.getCanonicalFile());
    }

    private static void verifyExtractedDex(File apk, File dex) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("classes.dex");
            CRC32 crc = new CRC32();
            try (InputStream input = new CheckedInputStream(new FileInputStream(dex), crc)) {
                byte[] buffer = new byte[16 * 1024];
                long count = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > DynamicCodeFiles.MAX_CODE_BYTES) throw new IOException("XWalk DEX超过大小上限");
                }
                if (entry == null || count != entry.getSize() || crc.getValue() != entry.getCrc()) {
                    throw new IOException("XWalk实际解出的DEX长度或CRC校验失败");
                }
            }
        }
    }

    private static void requireDex(File dex) throws IOException {
        if (dex.length() < 8 || dex.length() > DynamicCodeFiles.MAX_CODE_BYTES) throw new IOException("XWalk DEX大小无效");
        try (FileInputStream input = new FileInputStream(dex)) {
            byte[] magic = new byte[8];
            if (input.read(magic) != magic.length || magic[0] != 'd' || magic[1] != 'e' || magic[2] != 'x'
                    || magic[3] != '\n' || magic[7] != 0) throw new IOException("XWalk缺少可用的原始DEX");
            for (int i = 4; i < 7; i++) {
                if (magic[i] < '0' || magic[i] > '9') throw new IOException("XWalk DEX版本无效");
            }
        }
    }

    private static void validateRuntimeApk(File apk) throws IOException {
        // ZIP structure/size/CRC and DEX magic checks are integrity checks, not publisher authentication.
        try (ZipFile zip = new ZipFile(apk)) {
            Set<String> names = new HashSet<>();
            long total = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!names.add(entry.getName())) throw new IOException("XWalk APK包含重复资源");
                if (entry.isDirectory()) continue;
                long size = entry.getSize();
                if (size < 0 || size > DynamicCodeFiles.MAX_CODE_BYTES || size > 2 * DynamicCodeFiles.MAX_CODE_BYTES - total) {
                    throw new IOException("XWalk APK资源超过安全大小上限");
                }
                total += size;
            }
            ZipEntry dex = zip.getEntry("classes.dex");
            if (dex == null || dex.getSize() < 8) throw new IOException("XWalk APK缺少可合规加载的classes.dex");
        }
    }

    static String apkPath(Context context) {
        return context.getCacheDir().getAbsolutePath() + "/XWalkRuntimeLib.apk";
    }

    static String libExtractPath(Context context) {
        // XWalkEnvironment.getExtractedCoreDir
        return context.getDir(/*XWALK_CORE_EXTRACTED_DIR*/"extracted_xwalkcore", Context.MODE_PRIVATE).getAbsolutePath();
    }

}
