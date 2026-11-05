package com.github.tvbox.osc.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class CacheCleanupTest {
    private static int assertions;

    private static void check(boolean condition, String detail) {
        assertions++;
        if (!condition) throw new AssertionError(detail);
    }

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    public static void main(String[] args) throws Exception {
        Path root = new File(args[0]).toPath();
        Path cache = Files.createDirectories(root.resolve("cache"));
        Path prefs = write(root.resolve("shared_prefs/secret.xml"), "keep key and settings");
        Path source = write(root.resolve("files/csp/source.jar"), "keep content source");
        Path history = write(root.resolve("databases/history.db"), "keep history");
        Path apk = write(cache.resolve("updates/" + "a".repeat(64) + ".apk"), "pending installation");
        Path part = write(cache.resolve("updates/download-active.part"), "active download");
        Path receipt = write(cache.resolve("updates/unknown/file"), "unknown data");
        byte[] apkBytes = Files.readAllBytes(apk);
        byte[] partBytes = Files.readAllBytes(part);
        Path old = write(cache.resolve("old.tmp"), "unused");
        Path nested = write(cache.resolve("images/old/file"), "manual cache");
        CacheCleanup.clearExceptUpdates(cache.toFile());
        check(!Files.exists(old), "manual cache file removed");
        check(!Files.exists(nested), "nested manual cache removed");
        check(!Files.exists(cache.resolve("images")), "empty manual cache directories removed");
        check(Arrays.equals(apkBytes, Files.readAllBytes(apk)), "installer APK bytes preserved");
        check(Arrays.equals(partBytes, Files.readAllBytes(part)), "active download bytes preserved");
        check(Files.exists(receipt), "entire update subtree preserved for coordinator");
        check(Files.readString(prefs).equals("keep key and settings"), "preferences unchanged");
        check(Files.readString(source).equals("keep content source"), "source unchanged");
        check(Files.readString(history).equals("keep history"), "database unchanged");
        CacheCleanup.clearExceptUpdates(cache.toFile());
        check(Files.exists(apk) && Files.exists(part), "repeated manual cleanup remains safe");
        CacheCleanup.clearExceptUpdates(null);
        CacheCleanup.clearExceptUpdates(root.resolve("missing").toFile());
        check(!Files.exists(root.resolve("missing")), "missing cache not recreated");

        Path outside = Files.createDirectories(root.resolve("outside"));
        Path keep = write(outside.resolve("keep.txt"), "do not follow links");
        Path link = cache.resolve("alias");
        Files.createSymbolicLink(link, outside);
        CacheCleanup.clearExceptUpdates(cache.toFile());
        check(Files.exists(link), "symlink not traversed or deleted");
        check(Files.readString(keep).equals("do not follow links"), "outside symlink target unchanged");
        Path linkedRoot = root.resolve("linked-cache");
        Files.createSymbolicLink(linkedRoot, outside);
        CacheCleanup.clearExceptUpdates(linkedRoot.toFile());
        check(Files.exists(linkedRoot) && Files.exists(keep), "symlink cache root rejected");

        Path empty = Files.createDirectories(root.resolve("external-cache/jpali"));
        CacheCleanup.removeEmptyDirectory(empty.toFile());
        check(!Files.exists(empty), "empty known parent removed");
        Path nonempty = Files.createDirectories(root.resolve("external-cache/jpali"));
        Path runtime = write(nonempty.resolve("runtime.dat"), "keep runtime");
        CacheCleanup.removeEmptyDirectory(nonempty.toFile());
        check(Files.readString(runtime).equals("keep runtime"), "nonempty known parent preserved");
        Path emptyTarget = Files.createDirectories(root.resolve("empty-outside"));
        Path emptyLink = root.resolve("external-cache/empty-link");
        Files.createSymbolicLink(emptyLink, emptyTarget);
        CacheCleanup.removeEmptyDirectory(emptyLink.toFile());
        check(Files.exists(emptyLink) && Files.exists(emptyTarget), "empty parent symlink rejected");
        CacheCleanup.removeEmptyDirectory(null);

        Path deep = cache.resolve("deep");
        for (int i = 0; i < 40; i++) deep = deep.resolve("next");
        Path deepFile = write(deep.resolve("keep"), "beyond traversal budget");
        CacheCleanup.clearExceptUpdates(cache.toFile());
        check(Files.exists(deepFile), "depth bound stops traversal");
        check(Files.exists(apk) && Files.exists(part), "bounded cleanup still protects updates");
        System.out.println("CacheCleanupTest: " + assertions + " assertions passed");
    }
}
