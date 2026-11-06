package com.github.tvbox.osc.util;

import android.content.Context;
import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.lzy.okgo.OkGo;
import dalvik.system.DexClassLoader;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.xwalk.core.XWalkDecompressor;
import org.xwalk.core.XWalkInitializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DynamicCodeTest {
    private static int assertions;
    private static File root;
    private static final byte[] GOOD = bytes("good plugin fixture");
    private static final byte[] DEX = new byte[]{'d', 'e', 'x', '\n', '0', '3', '5', 0, 1, 2, 3, 4};

    public static void main(String[] args) throws Throwable {
        root = new File(args[0]).getCanonicalFile();
        check(root.mkdirs(), "create isolated data root");
        sequenceAndStreams();
        boundariesAndFailureCleanup();
        migrationAndDiscovery();
        replacementAndConcurrency();
        jarAndJsLoaders();
        crosswalk();
        System.out.println("PASS dynamic-code regression: " + assertions + " assertions");
    }

    private static void sequenceAndStreams() throws Throwable {
        final File dir = directory("sequence");
        final boolean[] inspected = {false};
        final boolean[] closed = {false};
        InputStream input = new ByteArrayInputStream(GOOD) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                if (!inspected[0]) {
                    inspected[0] = true;
                    File[] files = dir.listFiles();
                    check(files != null && files.length == 1 && files[0].getName().endsWith(".stage"), "only stage visible before first read");
                    File stage = files[0];
                    check(stage.length() == 0 && !stage.canWrite(), "empty stage path read-only before content read/write");
                    try {
                        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(stage.toPath());
                        check(!permissions.contains(PosixFilePermission.OWNER_WRITE)
                                && !permissions.contains(PosixFilePermission.GROUP_WRITE)
                                && !permissions.contains(PosixFilePermission.OTHERS_WRITE), "no POSIX write bits");
                        try (FileOutputStream ignored = new FileOutputStream(stage)) {
                            throw new AssertionError("read-only stage reopened for writing");
                        } catch (IOException expected) {
                            check(true, "new path write-open denied");
                        }
                    } catch (IOException e) { throw new AssertionError(e); }
                }
                return super.read(b, off, len);
            }
            @Override public void close() { closed[0] = true; }
        };
        File ready = DynamicCodeFiles.writeReadOnly(input, GOOD.length, dir, "main", ".jar", " " + md5(GOOD).toUpperCase() + " ");
        check(inspected[0] && !closed[0], "caller keeps stream ownership");
        check(DynamicCodeFiles.isReadOnlyRegularFile(ready), "published file read-only");
        equal(GOOD, read(ready), "already-open writable FD completed after path became read-only");
        check(dir.listFiles().length == 1 && !ready.getName().endsWith(".stage"), "no stage after publish");
        DynamicCodeFiles.verifyMd5(ready, md5(GOOD));

        InputStream zeros = new ByteArrayInputStream(GOOD) {
            boolean zero = true;
            @Override public synchronized int read(byte[] b, int off, int len) {
                if (zero) { zero = false; return 0; }
                zero = true;
                return super.read(b, off, len);
            }
        };
        equal(GOOD, read(DynamicCodeFiles.writeReadOnly(zeros, -1, dir, "zero", ".jar", null)), "zero bulk reads still make progress");
        System.out.println("PASS FD-first sequence, checksum, ownership and zero/unknown-length streams");
    }

    private static void boundariesAndFailureCleanup() throws Throwable {
        final File dir = directory("failures");
        final File old = write(dir, "old.jar", GOOD);
        final File unknown = write(dir, "unknown.data", bytes("do not remove"));
        final int original = dir.listFiles().length;
        for (long length : new long[]{0, -2, DynamicCodeFiles.MAX_CODE_BYTES + 1}) {
            fails(() -> DynamicCodeFiles.writeReadOnly(new ByteArrayInputStream(GOOD), length, dir, "fail", ".jar", null), IOException.class);
        }
        fails(() -> DynamicCodeFiles.writeReadOnly(null, 2, dir, "fail", ".jar", null), IOException.class);
        fails(() -> DynamicCodeFiles.writeReadOnly(new ByteArrayInputStream(GOOD), GOOD.length, dir, "fail", "../bad.jar", null), IOException.class);
        fails(() -> DynamicCodeFiles.writeReadOnly(new ByteArrayInputStream(GOOD), GOOD.length, dir, "fail", ".jar", "xyz"), IOException.class);
        fails(() -> DynamicCodeFiles.writeReadOnly(new ByteArrayInputStream(GOOD), GOOD.length, dir, "fail", ".jar", md5(bytes("different"))), IOException.class);
        fails(() -> DynamicCodeFiles.writeReadOnly(new ByteArrayInputStream(GOOD), GOOD.length - 1, dir, "fail", ".jar", null), IOException.class);
        fails(() -> DynamicCodeFiles.writeReadOnly(new ByteArrayInputStream(GOOD), GOOD.length + 1, dir, "fail", ".jar", null), IOException.class);
        fails(() -> DynamicCodeFiles.writeReadOnly(new ByteArrayInputStream(new byte[0]), -1, dir, "fail", ".jar", null), IOException.class);
        for (final boolean runtime : new boolean[]{false, true}) {
            InputStream broken = new InputStream() {
                int calls;
                @Override public int read() throws IOException { throw new IOException("fixture"); }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (calls++ == 0) { b[off] = 1; return 1; }
                    if (runtime) throw new IllegalStateException("fixture runtime failure");
                    throw new IOException("fixture stream failure");
                }
            };
            fails(() -> DynamicCodeFiles.writeReadOnly(broken, -1, dir, "fail", ".jar", null), runtime ? IllegalStateException.class : IOException.class);
        }
        InputStream invalidCount = new InputStream() {
            public int read() { return 1; }
            public int read(byte[] b, int off, int len) { return len + 1; }
        };
        fails(() -> DynamicCodeFiles.writeReadOnly(invalidCount, -1, dir, "fail", ".jar", null), IOException.class);
        InputStream invalidByte = new InputStream() {
            public int read() { return 256; }
            public int read(byte[] b, int off, int len) { return 0; }
        };
        fails(() -> DynamicCodeFiles.writeReadOnly(invalidByte, -1, dir, "fail", ".jar", null), IOException.class);
        check(dir.listFiles().length == original, "failure cleanup leaves no staging or published candidates");
        equal(GOOD, read(old), "old cache untouched by failures");
        check(unknown.exists(), "unknown files retained");
        File exact = DynamicCodeFiles.writeReadOnly(repeated(DynamicCodeFiles.MAX_CODE_BYTES), -1, dir, "limit", ".jar", null);
        check(exact.length() == DynamicCodeFiles.MAX_CODE_BYTES, "exact maximum permitted");
        check(exact.delete(), "remove owned large test artifact");
        fails(() -> DynamicCodeFiles.writeReadOnly(repeated(DynamicCodeFiles.MAX_CODE_BYTES + 1), -1, dir, "limit", ".jar", null), IOException.class);
        check(dir.listFiles().length == original, "unknown-length oversized stream cleaned up");
        File blocked = directory("blocked");
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(blocked.toPath());
        Files.setPosixFilePermissions(blocked.toPath(), new HashSet<>(Arrays.asList(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)));
        try {
            fails(() -> DynamicCodeFiles.writeReadOnly(new ByteArrayInputStream(GOOD), GOOD.length, blocked, "fail", ".jar", null), IOException.class);
        } finally { Files.setPosixFilePermissions(blocked.toPath(), originalPermissions); }
        check(blocked.listFiles().length == 0, "unwritable directory publishes nothing");
        System.out.println("PASS size limits, truncated/oversized streams, checked/runtime failures and cleanup");
    }

    private static void migrationAndDiscovery() throws Throwable {
        File dir = directory("migration");
        File legacy = write(dir, "legacy.jar", GOOD);
        File privateDir = new File(dir, "private");
        File first = DynamicCodeFiles.prepareForLoading(legacy, privateDir, "main", ".jar", md5(GOOD));
        check(legacy.canWrite(), "legacy source not chmod-ed or removed");
        check(!legacy.getCanonicalFile().equals(first.getCanonicalFile()), "migrated inode is independent");
        check(first.setLastModified(1000), "set test snapshot timestamp");
        File second = DynamicCodeFiles.prepareForLoading(first, privateDir, "main", ".jar", null);
        check(second.setLastModified(2000), "set newer snapshot timestamp");
        File fake = write(privateDir, "main-unknown.jar", GOOD); fake.setReadOnly(); fake.setLastModified(9000);
        File corrupt = write(privateDir, "main-" + md5(GOOD) + "-" + UUID.randomUUID() + ".jar", bytes("tampered"));
        corrupt.setReadOnly(); corrupt.setLastModified(10000);
        File stage = write(privateDir, "main-extra.stage", GOOD); stage.setReadOnly();
        check(DynamicCodeFiles.latestReadOnly(privateDir, "main", ".jar").equals(second), "ignore unknown, stage and checksum-corrupt files");
        check(fake.exists() && corrupt.exists() && stage.exists(), "discovery does not delete unrecognized files");
        File sourceLink = new File(dir, "link.jar"); Files.createSymbolicLink(sourceLink.toPath(), legacy.toPath());
        fails(() -> DynamicCodeFiles.prepareForLoading(sourceLink, privateDir, "main", ".jar", null), IOException.class);
        File rootLink = new File(dir, "root-link"); Files.createSymbolicLink(rootLink.toPath(), privateDir.toPath());
        fails(() -> DynamicCodeFiles.prepareForLoading(legacy, rootLink, "main", ".jar", null), IOException.class);
        File dangling = new File(dir, "dangling"); Files.createSymbolicLink(dangling.toPath(), new File(dir, "absent").toPath());
        fails(() -> DynamicCodeFiles.prepareForLoading(legacy, dangling, "main", ".jar", null), IOException.class);
        fails(() -> DynamicCodeFiles.prepareForLoading(legacy, legacy, "main", ".jar", null), IOException.class);
        System.out.println("PASS independent legacy copies, strict snapshot discovery and symlink rejection");
    }

    private static void replacementAndConcurrency() throws Throwable {
        File dir = directory("replace");
        File target = write(dir, "cache.jar", GOOD);
        byte[] next = bytes("good newer plugin");
        File candidate = DynamicCodeFiles.writeReadOnly(new ByteArrayInputStream(next), next.length, dir, "download", ".jar", null);
        DynamicCodeFiles.replacePreservingPrevious(candidate, target);
        equal(next, read(target), "replacement published complete readonly candidate");
        check(!target.canWrite() && previousCount(dir, "cache.jar") == 1, "readonly replacement and old version retained");
        File failing = DynamicCodeFiles.writeReadOnly(new ByteArrayInputStream(GOOD), GOOD.length, dir, "download", ".jar", null);
        File renameFailure = new File(failing.getAbsolutePath()) { @Override public boolean renameTo(File destination) { return false; } };
        fails(() -> DynamicCodeFiles.replacePreservingPrevious(renameFailure, target), IOException.class);
        equal(next, read(target), "failed publish restores old target");
        check(failing.exists(), "caller still owns failed candidate");
        File throwingRename = new File(failing.getAbsolutePath()) {
            @Override public boolean renameTo(File destination) { throw new SecurityException("fixture rename failure"); }
        };
        fails(() -> DynamicCodeFiles.replacePreservingPrevious(throwingRename, target), SecurityException.class);
        equal(next, read(target), "unchecked rename failure also restores previous cache");
        File failedPreserve = new File(target.getAbsolutePath()) {
            @Override public boolean renameTo(File destination) { return false; }
        };
        fails(() -> DynamicCodeFiles.replacePreservingPrevious(failing, failedPreserve), IOException.class);
        equal(next, read(target), "failure preserving old target changes nothing");
        Set<PosixFilePermission> directoryPermissions = Files.getPosixFilePermissions(dir.toPath());
        File failedRestore = new File(failing.getAbsolutePath()) {
            @Override public boolean renameTo(File destination) {
                try {
                    Files.setPosixFilePermissions(dir.toPath(), new HashSet<>(Arrays.asList(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)));
                } catch (IOException e) { throw new AssertionError(e); }
                return false;
            }
        };
        try {
            fails(() -> DynamicCodeFiles.replacePreservingPrevious(failedRestore, target), IOException.class);
            check(!target.exists() && previousCount(dir, "cache.jar") == 2, "failed restoration retains old bytes at explicit previous path");
        } finally { Files.setPosixFilePermissions(dir.toPath(), directoryPermissions); }
        for (File f : dir.listFiles()) {
            if (f.getName().startsWith("cache.jar.previous-") && Arrays.equals(read(f), next)) {
                check(f.renameTo(target), "restore retained cache after permission-failure fixture"); break;
            }
        }
        fails(() -> DynamicCodeFiles.replacePreservingPrevious(target, target), IOException.class);
        File link = new File(dir, "cache-link.jar"); Files.createSymbolicLink(link.toPath(), target.toPath());
        fails(() -> DynamicCodeFiles.replacePreservingPrevious(failing, link), IOException.class);
        File other = directory("other-filesystem-directory");
        fails(() -> DynamicCodeFiles.replacePreservingPrevious(failing, new File(other, "cache.jar")), IOException.class);
        File writable = write(dir, "writable.jar", GOOD);
        fails(() -> DynamicCodeFiles.replacePreservingPrevious(writable, target), IOException.class);

        File concurrent = directory("concurrent");
        File concurrentTarget = write(concurrent, "cache.jar", GOOD);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<File>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                final byte[] value = bytes("good concurrent plugin " + i);
                pending.add(executor.submit(() -> DynamicCodeFiles.writeReadOnly(new ByteArrayInputStream(value), value.length, concurrent, "same-key", ".jar", md5(value))));
            }
            Set<String> paths = new HashSet<>();
            List<File> results = new ArrayList<>();
            for (Future<File> future : pending) {
                File result = future.get(); results.add(result);
                check(paths.add(result.getName()) && DynamicCodeFiles.isReadOnlyRegularFile(result), "concurrent publication uses independent immutable paths");
            }
            List<Future<?>> replacements = new ArrayList<>();
            for (File result : results) {
                replacements.add(executor.submit(() -> { DynamicCodeFiles.replacePreservingPrevious(result, concurrentTarget); return null; }));
            }
            for (Future<?> future : replacements) future.get();
            check(previousCount(concurrent, "cache.jar") == 16, "all concurrent previous versions retained");
            check(DynamicCodeFiles.isReadOnlyRegularFile(concurrentTarget), "concurrent final target complete and readonly");
            check(new String(read(concurrentTarget), StandardCharsets.UTF_8).startsWith("good concurrent plugin "), "no interleaved concurrent bytes");
        } finally { executor.shutdownNow(); }
        System.out.println("PASS replacement rollback, old-version retention and concurrent same-key publication");
    }

    private static void jarAndJsLoaders() throws Throwable {
        App app = new App(directory("loaders"));
        String mainUrl = "img+https://fixture.invalid/main";
        ApiConfig.spider = mainUrl + ";md5;" + md5(GOOD);
        File main = write(new File(app.getFilesDir(), "csp"), MD5.string2MD5(mainUrl) + ".jar", GOOD);
        JarLoader jar = new JarLoader();
        check(jar.load(main.getAbsolutePath()), "main legacy cache copied and loaded");
        File mainPrivate = lastLoaded();
        check(mainPrivate.getParent().endsWith("dynamic_code/jar") && main.canWrite(), "main uses private snapshot, leaves acquisition cache unchanged");
        jar.clear(); Files.write(main.toPath(), bytes("bad truncated refresh"));
        check(jar.load(main.getAbsolutePath()), "main falls back to matching immutable snapshot after destructive caller refresh");
        check(lastLoaded().equals(mainPrivate), "main fallback is prior successful version");
        jar.clear(); ApiConfig.spider = mainUrl + ";md5;" + md5(bytes("other"));
        check(!jar.load(main.getAbsolutePath()), "main never downgrades declared checksum for fallback");
        File candidate = write(new File(app.getFilesDir(), "candidates"), "random-name.jar", GOOD);
        JarLoader validating = new JarLoader();
        check(validating.loadCandidate(candidate.getAbsolutePath(), main.getName(), md5(GOOD)), "fresh candidate validated without cache fallback");
        check(lastLoaded().getName().startsWith("main-" + main.getName() + "-"), "candidate snapshot uses stable source identity");
        check(!validating.loadCandidate(main.getAbsolutePath(), main.getName(), ""), "already loaded class cannot validate a second candidate");
        check(!new JarLoader().loadCandidate(main.getAbsolutePath(), main.getName(), ""), "invalid candidate cannot borrow successful stable snapshot");
        check(!new JarLoader().loadCandidate(candidate.getAbsolutePath(), "../outside.jar", ""), "candidate identity cannot escape its basename");
        check(!new JarLoader().loadCandidate(candidate.getAbsolutePath(), main.getName(), md5(bytes("wrong"))), "candidate checksum never falls back");
        ApiConfig.spider = mainUrl + ";md5;" + md5(GOOD);
        check(new JarLoader().load(main.getAbsolutePath()), "normal fallback still sees stable candidate snapshot");

        for (boolean js : new boolean[]{false, true}) {
            Object loader = js ? new JsLoader() : new JarLoader();
            String url = "https://fixture.invalid/" + (js ? "js" : "sub");
            String key = MD5.string2MD5(url);
            File cache = write(new File(app.getFilesDir(), "csp"), key + ".jar", GOOD);
            OkGo.calls.set(0); OkGo.fail = false;
            check(internal(loader, url, md5(GOOD), key) != null, "legacy " + js + " cache loads privately");
            check(OkGo.calls.get() == 0 && cache.canWrite(), "cache hit keeps load-before-download order");
            File previous = lastLoaded();
            clear(loader);
            check(cache.setLastModified(1), "expire fixture cache");
            OkGo.fail = true;
            check(internal(loader, url, "", key) != null, "offline stale cache fallback " + js);
            equal(GOOD, read(cache), "offline failure preserves old bytes " + js);
            clear(loader);
            check(cache.delete(), "simulate absent acquisition cache");
            check(internal(loader, url, md5(GOOD), key) != null, "missing legacy cache falls back to private snapshot " + js);
            check(DynamicCodeFiles.isReadOnlyRegularFile(previous), "previous private snapshot retained " + js);
            clear(loader);
            OkGo.fail = false;
            ResponseBody unknown = response(GOOD, -1, true);
            String newKey = MD5.string2MD5(url + "new");
            check(internal(loader, url, md5(GOOD), newKey) != null, "unknown Content-Length download " + js);
            check(unknown.closed, "HTTP stream closed " + js);
            File fresh = new File(app.getFilesDir(), "csp/" + newKey + ".jar");
            equal(GOOD, read(fresh), "new cache bytes valid " + js);
            check(!fresh.canWrite(), "download cache readonly " + js);
            clear(loader);
            check(fresh.setLastModified(1), "expire downloaded fixture");
            ResponseBody bad = response(bytes("bad plugin"), -1, true);
            check(internal(loader, url, "", newKey) != null, "invalid downloaded runtime falls back " + js);
            check(bad.closed, "runtime failure closes body " + js);
            equal(GOOD, read(fresh), "runtime-invalid refresh never replaces old cache " + js);
            clear(loader);
            response(bytes("different digest"), -1, true);
            String wrongKey = MD5.string2MD5(url + "wrong");
            check(internal(loader, url, md5(GOOD), wrongKey) == null, "declared checksum mismatch rejected " + js);
            check(!new File(app.getFilesDir(), "csp/" + wrongKey + ".jar").exists(), "mismatch not cached " + js);
            clear(loader);
            response(GOOD, GOOD.length + 1, true);
            check(internal(loader, url, "", MD5.string2MD5(url + "short")) == null, "short HTTP response rejected " + js);
            ResponseBody status = response(GOOD, GOOD.length, false);
            check(internal(loader, url, "", MD5.string2MD5(url + "status")) == null && status.closed, "HTTP error not loaded " + js);
            OkGo.next = new Response(null, true);
            check(internal(loader, url, "", MD5.string2MD5(url + "null")) == null, "null HTTP body rejected " + js);
            response(new byte[0], -1, true);
            check(internal(loader, url, "", MD5.string2MD5(url + "empty")) == null, "empty download rejected " + js);
            clear(loader);
        }
        check(mainPrivate.exists(), "unknown/old successful main plugin never removed");
        Files.write(main.toPath(), GOOD);
        ApiConfig.spider = mainUrl + ";md5;" + md5(GOOD);
        JarLoader concurrentJar = new JarLoader();
        JsLoader concurrentJs = new JsLoader();
        String concurrentKey = MD5.string2MD5("https://fixture.invalid/concurrent-js");
        write(new File(app.getFilesDir(), "csp"), concurrentKey + ".jar", GOOD);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> loads = new ArrayList<>();
            for (int i = 0; i < 16; i++) loads.add(workers.submit(() -> concurrentJar.load(main.getAbsolutePath())));
            for (Future<Boolean> load : loads) check(load.get(), "concurrent main loads succeed without shared writable paths");
            List<Future<Object>> jsLoads = new ArrayList<>();
            for (int i = 0; i < 16; i++) jsLoads.add(workers.submit(() -> {
                try { return internal(concurrentJs, "https://fixture.invalid/concurrent-js", md5(GOOD), concurrentKey); }
                catch (Throwable t) { throw new IllegalStateException(t); }
            }));
            for (Future<Object> load : jsLoads) check(load.get() != null, "concurrent JS loads retain one usable class entry");
            check(concurrentJar.proxyInvoke(new java.util.HashMap<String, String>()) != null, "concurrent JAR loader retains its proxy method");
        } finally { workers.shutdownNow(); concurrentJs.clear(); concurrentJar.clear(); }
        System.out.println("PASS main/child/JS legacy and HTTP loaders, MD5, failed-refresh rollback and private ART inputs");
    }

    private static void crosswalk() throws Throwable {
        Context context = new Context(directory("xwalk"));
        File runtime = new File(XWalkUtils.libExtractPath(context));
        for (String name : new String[]{"classes.dex", "icudtl.dat", "libxwalkcore.so", "xwalk.pak", "xwalk_100_percent.pak"}) {
            write(runtime, name, name.equals("classes.dex") ? DEX : GOOD);
        }
        File unknown = write(runtime, "unknown-plugin.data", GOOD);
        check(XWalkUtils.xWalkLibExist(context), "legacy XWalk DEX migrated at actual fixed load path");
        File dex = new File(runtime, "classes.dex");
        check(!dex.canWrite() && previousCount(runtime, "classes.dex") == 1, "legacy writable DEX retained as previous inode");
        check(unknown.exists(), "XWalk migration retains unknown files");
        check(XWalkUtils.xWalkLibExist(context) && previousCount(runtime, "classes.dex") == 1, "readonly DEX not recopied on every readiness check");

        byte[] apkBytes = runtimeApk(DEX);
        File archive = write(context.getCacheDir(), "bundle.zip", zip("nested/XWalkRuntimeLib.apk", apkBytes));
        XWalkUtils.unzipXWalkZip(context, archive.getAbsolutePath());
        File apk = new File(XWalkUtils.apkPath(context));
        check(DynamicCodeFiles.isReadOnlyRegularFile(apk) && archive.exists(), "readonly APK published without deleting caller ZIP");
        XWalkUtils.extractXWalkLib(context);
        equal(DEX, read(new File(runtime, "classes.dex")), "actual extracted DEX preserved");
        check(DynamicCodeFiles.isReadOnlyRegularFile(new File(runtime, "classes.dex")), "actual XWalk load path readonly, not only APK");
        check(!XWalkDecompressor.lastStage.exists(), "isolated complete directory published atomically");
        File previousDir = findPreviousDirectory(runtime);
        check(previousDir != null && new File(previousDir, unknown.getName()).exists(), "old whole runtime including unknown files retained");
        check(XWalkUtils.xWalkLibExist(context), "new extracted runtime passes readiness checks");

        XWalkDecompressor.corruptDex = true;
        fails(() -> XWalkUtils.extractXWalkLib(context), IOException.class);
        XWalkDecompressor.corruptDex = false;
        equal(DEX, read(new File(runtime, "classes.dex")), "CRC-invalid actual decoded DEX never replaces old runtime");
        check(!XWalkDecompressor.lastStage.exists(), "failed owned extraction stage cleaned");
        XWalkDecompressor.fail = true;
        fails(() -> XWalkUtils.extractXWalkLib(context), IOException.class);
        XWalkDecompressor.fail = false;
        equal(DEX, read(new File(runtime, "classes.dex")), "decompressor failure retains old runtime");
        File duplicate = write(context.getCacheDir(), "duplicate.zip", zipMany(
                new String[]{"a/XWalkRuntimeLib.apk", "b/XWalkRuntimeLib.apk"}, new byte[][]{apkBytes, apkBytes}));
        fails(() -> XWalkUtils.unzipXWalkZip(context, duplicate.getAbsolutePath()), IOException.class);
        equal(apkBytes, read(apk), "duplicate runtime candidate never overwrites prior APK");
        File missing = write(context.getCacheDir(), "missing.zip", zip("other.bin", GOOD));
        fails(() -> XWalkUtils.unzipXWalkZip(context, missing.getAbsolutePath()), IOException.class);
        File invalid = write(context.getCacheDir(), "invalid.zip", zip("XWalkRuntimeLib.apk", GOOD));
        fails(() -> XWalkUtils.unzipXWalkZip(context, invalid.getAbsolutePath()), IOException.class);
        equal(apkBytes, read(apk), "invalid APK retains old APK");
        byte[] badCrc = zip("XWalkRuntimeLib.apk", apkBytes);
        for (int i = 0; i + 20 < badCrc.length; i++) {
            if (badCrc[i] == 'P' && badCrc[i + 1] == 'K' && badCrc[i + 2] == 1 && badCrc[i + 3] == 2) {
                badCrc[i + 16] ^= 1; break; // Change only central-directory CRC, leave payload intact.
            }
        }
        File crcArchive = write(context.getCacheDir(), "crc-invalid.zip", badCrc);
        fails(() -> XWalkUtils.unzipXWalkZip(context, crcArchive.getAbsolutePath()), IOException.class);
        equal(apkBytes, read(apk), "outer ZIP CRC mismatch never publishes APK");
        File compressedOnly = write(context.getCacheDir(), "compressed-only.zip", zip("XWalkRuntimeLib.apk", zip("assets/classes.dex.xz", GOOD)));
        fails(() -> XWalkUtils.unzipXWalkZip(context, compressedOnly.getAbsolutePath()), IOException.class);
        equal(apkBytes, read(apk), "unsupported compressed-only DEX fails without reduced validation");
        File parentAlias = new File(root, "xwalk-parent-alias");
        Files.createSymbolicLink(parentAlias.toPath(), context.root.toPath());
        Context aliasedContext = new Context(parentAlias);
        check(XWalkUtils.xWalkLibExist(aliasedContext), "canonical parent aliases do not falsely reject Android private paths");
        check(XWalkUtils.extractXWalkLib(aliasedContext), "XWalk publication supports canonical parent aliases");

        Context broken = new Context(directory("xwalk-broken"));
        File brokenDir = new File(XWalkUtils.libExtractPath(broken));
        for (String name : new String[]{"classes.dex", "icudtl.dat", "libxwalkcore.so", "xwalk.pak", "xwalk_100_percent.pak"}) write(brokenDir, name, GOOD);
        final int[] states = {0, 0, 0};
        XWalkUtils.XWalkState state = new XWalkUtils.XWalkState() {
            public void success() { states[0]++; } public void fail() { states[1]++; } public void ignore() { states[2]++; }
        };
        int initBefore = XWalkInitializer.created;
        XWalkUtils.tryUseXWalk(broken, state);
        check(states[1] == 1 && states[0] == 0 && states[2] == 0, "noncompliant XWalk clearly fails instead of ignoring checks");
        check(XWalkInitializer.created == initBefore, "unsafe DEX never reaches XWalk initializer");
        Context absent = new Context(directory("xwalk-absent"));
        XWalkUtils.tryUseXWalk(absent, state);
        check(states[2] == 1, "genuinely absent optional XWalk remains ignored");
        XWalkUtils.tryUseXWalk(context, state);
        check(states[0] == 1, "compliant XWalk reaches initializer");
        System.out.println("PASS XWalk actual DEX migration/publication, extraction/CRC failures and safe initialization gates");
    }

    private static Object internal(Object loader, String url, String md5, String key) throws Throwable {
        Method method = loader.getClass().getDeclaredMethod("loadJarInternal", String.class, String.class, String.class);
        method.setAccessible(true);
        try { return method.invoke(loader, url, md5, key); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    private static void clear(Object loader) { if (loader instanceof JarLoader) ((JarLoader) loader).clear(); else ((JsLoader) loader).clear(); }
    private static ResponseBody response(byte[] content, long length, boolean success) {
        ResponseBody body = new ResponseBody(new ByteArrayInputStream(content), length);
        OkGo.next = new Response(body, success); return body;
    }
    private static File lastLoaded() { return DexClassLoader.loaded.get(DexClassLoader.loaded.size() - 1); }
    private static File findPreviousDirectory(File target) {
        for (File f : target.getParentFile().listFiles()) if (f.getName().startsWith(target.getName() + ".previous-") && f.isDirectory()) return f;
        return null;
    }
    private static int previousCount(File dir, String target) {
        int count = 0; for (File f : dir.listFiles()) if (f.getName().startsWith(target + ".previous-")) count++; return count;
    }
    private static InputStream repeated(final long length) {
        return new InputStream() {
            long remaining = length;
            public int read() { if (remaining == 0) return -1; remaining--; return 1; }
            public int read(byte[] b, int off, int len) {
                if (remaining == 0) return -1;
                int n = (int) Math.min(remaining, len); Arrays.fill(b, off, off + n, (byte) 1); remaining -= n; return n;
            }
        };
    }
    private static byte[] runtimeApk(byte[] dex) throws IOException {
        return zipMany(new String[]{"classes.dex", "assets/icudtl.dat", "lib/arm64-v8a/libxwalkcore.so", "assets/xwalk.pak", "assets/xwalk_100_percent.pak"},
                new byte[][]{dex, GOOD, GOOD, GOOD, GOOD});
    }
    private static byte[] zip(String name, byte[] content) throws IOException { return zipMany(new String[]{name}, new byte[][]{content}); }
    private static byte[] zipMany(String[] names, byte[][] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < names.length; i++) { zip.putNextEntry(new ZipEntry(names[i])); zip.write(content[i]); zip.closeEntry(); }
        }
        return out.toByteArray();
    }
    private static File directory(String name) { File dir = new File(root, name); check(dir.mkdirs(), "create test directory " + name); return dir; }
    private static File write(File dir, String name, byte[] value) throws IOException {
        dir.mkdirs(); File file = new File(dir, name); Files.write(file.toPath(), value); return file;
    }
    private static byte[] read(File file) throws IOException { return Files.readAllBytes(file.toPath()); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String md5(byte[] value) {
        try {
            StringBuilder result = new StringBuilder(); for (byte b : MessageDigest.getInstance("MD5").digest(value)) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void equal(byte[] a, byte[] b, String message) { check(Arrays.equals(a, b), message); }
    private static void check(boolean success, String message) { assertions++; if (!success) throw new AssertionError(message); }
    private interface Throwing { void run() throws Throwable; }
    private static void fails(Throwing code, Class<? extends Throwable> expected) throws Throwable {
        try { code.run(); } catch (Throwable e) { check(expected.isInstance(e), "expected " + expected.getSimpleName() + ", got " + e); return; }
        throw new AssertionError("expected failure " + expected.getSimpleName());
    }
}
