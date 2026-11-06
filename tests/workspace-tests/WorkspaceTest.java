package com.github.tvbox.osc.util;

import com.github.tvbox.osc.base.App;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

public class WorkspaceTest {
    interface Checked { void run() throws Exception; }
    private static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static void fails(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IOException expected) { checks++; }
    }
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static InputStream input(String text) { return new ByteArrayInputStream(bytes(text)); }
    static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) zip.write(bytes("fixture:" + name));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    static void rejectZip(File root, byte[] zip) throws Exception {
        File stage = WorkspaceIO.newStage(root);
        try { fails(() -> WorkspaceIO.unzip(new ByteArrayInputStream(zip), stage, new WorkspaceIO.Budget())); }
        finally { WorkspaceIO.deleteTree(stage); }
    }
    public static void main(String[] args) throws Exception {
        new App(new File(args[0]));
        File root = WorkspaceFiles.root();
        File staging = WorkspaceIO.directory(new File(args[0], "test-staging"));
        for (String path : new String[]{"../secret", "/etc/passwd", "a/../b", "a/./b", "a\\b", "C:/windows", ".hidden", "a/.hidden", "%2e%2e/a", "%252e/a", "a\u0000b"}) {
            fails(() -> WorkspaceFiles.resolve(path));
        }
        check(WorkspaceFiles.resolve("").equals(root), "root");
        File outside = WorkspaceIO.directory(new File(args[0], "outside"));
        Files.createSymbolicLink(new File(root, "alias").toPath(), outside.toPath());
        fails(() -> WorkspaceFiles.resolve("alias/nope"));
        Files.delete(new File(root, "alias").toPath());
        File good = new File(root, "keep.json");
        WorkspaceIO.writeAtomic(input("old"), good, 10);
        fails(() -> WorkspaceIO.writeAtomic(input("too-long"), good, 3));
        check(WorkspaceIO.text(good).equals("old"), "failed refresh preserved old bytes");
        fails(() -> WorkspaceIO.writeAtomic(new InputStream() {
            public int read() throws IOException { throw new IOException("read denied"); }
        }, good, 20));
        check(WorkspaceIO.text(good).equals("old"), "provider failure preserved old bytes");
        WorkspaceIO.writeAtomic(input("new"), good, 10);
        check(WorkspaceIO.text(good).equals("new"), "atomic replace");
        check(Arrays.stream(root.list()).noneMatch(n -> n.startsWith(".write-")), "no stage leaks");
        InputStream shortStream = new ByteArrayInputStream(bytes("short-read")) {
            public int available() { return 0; }
            public int read(byte[] b, int o, int l) { return super.read(b, o, Math.min(l, 1)); }
        };
        check(new String(WorkspaceIO.read(shortStream, 10), StandardCharsets.UTF_8).equals("short-read"), "available is not length");
        Thread.currentThread().interrupt();
        fails(() -> WorkspaceIO.copy(input("x"), new ByteArrayOutputStream(), 1));
        Thread.interrupted();
        WorkspaceIO.Budget budget = new WorkspaceIO.Budget();
        budget.add(WorkspaceIO.MAX_TOTAL_BYTES);
        fails(() -> budget.add(1));
        for (int i = 0; i < WorkspaceIO.MAX_ENTRIES; i++) budget.entry();
        fails(budget::entry);
        rejectZip(staging, zip("first.json", "../escape"));
        rejectZip(staging, zip("/absolute"));
        rejectZip(staging, zip("a\\b"));
        rejectZip(staging, zip("a/.secret"));
        rejectZip(staging, zip(String.join("/", Collections.nCopies(18, "a"))));
        String[] tooMany = new String[WorkspaceIO.MAX_ENTRIES + 1];
        for (int i = 0; i < tooMany.length; i++) tooMany[i] = "entry-" + i;
        rejectZip(staging, zip(tooMany));
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (ZipOutputStream compressed = new ZipOutputStream(bomb)) {
            compressed.putNextEntry(new ZipEntry("oversize.bin"));
            byte[] zeros = new byte[16384];
            for (long i = 0; i <= WorkspaceIO.MAX_FILE_BYTES; i += zeros.length) compressed.write(zeros);
            compressed.closeEntry();
        }
        rejectZip(staging, bomb.toByteArray());
        check(Arrays.stream(staging.list()).noneMatch(n -> n.startsWith(".archive-")), "failed ZIP spool cleaned");
        byte[] complete = zip("config.json", "lib/plugin.jar", "lib/cat.js", "images/wall.png");
        rejectZip(staging, Arrays.copyOf(complete, complete.length - 24));
        File stage = WorkspaceIO.newStage(staging);
        WorkspaceIO.unzip(new ByteArrayInputStream(complete), stage, new WorkspaceIO.Budget());
        File bundle = WorkspaceIO.publish(stage, root);
        check(!stage.exists(), "bundle rename committed");
        check(new File(bundle, "lib/plugin.jar").isFile(), "nested JAR preserved");
        check(new File(bundle, "images/wall.png").isFile(), "image preserved");
        File copy = WorkspaceIO.newStage(staging);
        WorkspaceIO.copyTree(bundle, copy, new WorkspaceIO.Budget());
        File migrated = WorkspaceIO.publish(copy, root);
        check(bundle.exists() && migrated.exists(), "migration never moves originals");
        String base = WorkspaceFiles.clan(new File(bundle, "config.json"));
        String local = WorkspaceFiles.toHttp(base);
        for (String path : new String[]{"./lib/plugin.jar", "./lib/cat.js", "./images/wall.png", "lib/plugin.jar"}) {
            File resolved = WorkspaceFiles.fromLocalUrl(WorkspaceFiles.reference(base, path));
            check(resolved != null && resolved.isFile(), "all config resources share HTTP/clan root: " + path);
        }
        String child = WorkspaceFiles.reference(base, "./lib/plugin.jar;md5;abc");
        check(child.endsWith("/lib/plugin.jar;md5;abc"), "md5 suffix");
        check(WorkspaceFiles.reference(base, "img+./images/wall.png").startsWith("img+http://"), "image JAR prefix");
        check(WorkspaceFiles.fromLocalUrl(local).equals(new File(bundle, "config.json")), "clan URL roundtrip");
        check(WorkspaceFiles.fromLocalUrl("http://127.0.0.1:9000/file/config.json") == null, "other loopback service unchanged");
        check(WorkspaceFiles.fromLocalUrl("http://127.0.0.1:9978/proxy") == null, "proxy unchanged");
        fails(() -> WorkspaceFiles.fromLocalUrl("http://127.0.0.1:9978/file/%2e%2e/private-backups/hawk"));
        fails(() -> WorkspaceFiles.reference(base, "../../../../secret.jar"));
        String space = WorkspaceFiles.reference(base, "./lib/a%20b.js");
        check(space.endsWith("a%20b.js"), "encoded filename is not double encoded");
        check(WorkspaceFiles.fromLocalUrl(space).getName().equals("a b.js"), "decode once");
        String unicode = WorkspaceFiles.clan(new File(bundle, "图片 #1.png"));
        check(WorkspaceFiles.fromLocalUrl(unicode).getName().equals("图片 #1.png"), "Unicode space hash roundtrip");
        check(WorkspaceFiles.fromLocalUrl("file://TVBox/config.json").equals(new File(root, "TVBox/config.json")), "historic file authority alias");
        String remoteBase = "clan://192.168.1.10:9978/TVBox/config.json";
        String remotePlugin = "http://192.168.1.10:9978/file/TVBox/spider.jar";
        check(WorkspaceFiles.reference(remoteBase, "clan://TVBox/spider.jar").equals(remotePlugin), "legacy clan root reference uses configuration server");
        check(WorkspaceFiles.reference("http://192.168.1.10:9978/file/TVBox/config.json", "clan://TVBox/spider.jar").equals(remotePlugin), "already converted clan base retains root semantics");
        check(WorkspaceFiles.reference(remoteBase, "img+clan://TVBox/spider.jar;md5;abc").equals("img+" + remotePlugin + ";md5;abc"), "legacy clan prefix and checksum preserved");
        check(WorkspaceFiles.reference(remoteBase, "clan://TVBox.v2/spider.jar").endsWith("/file/TVBox.v2/spider.jar"), "dotted directory is not a remote host");
        check(WorkspaceFiles.reference(remoteBase, "clan://TVBox/a%20b.js").endsWith("/file/TVBox/a%20b.js"), "legacy clan resource decoded once");
        check(WorkspaceFiles.reference("https://example.org/file/config.json", "clan://TVBox/spider.jar").equals("https://example.org/file/TVBox/spider.jar"), "legacy clan reference retains HTTPS");
        check(WorkspaceFiles.fromLocalUrl(WorkspaceFiles.reference(base, "clan://TVBox/spider.jar")).equals(new File(root, "TVBox/spider.jar")), "local configuration uses private workspace root");
        check(WorkspaceFiles.reference(remoteBase, "clan://localhost/TVBox/spider.jar").equals("http://127.0.0.1:9978/file/TVBox/spider.jar"), "explicit localhost remains local");
        check(WorkspaceFiles.reference(remoteBase, "clan://192.168.1.20:9978/other.jar").equals("http://192.168.1.20:9978/file/other.jar"), "explicit port remains a complete remote address");
        check(WorkspaceFiles.toHttp(remoteBase).equals("http://192.168.1.10:9978/file/TVBox/config.json"), "top-level clan address still identifies server");
        fails(() -> WorkspaceFiles.reference(remoteBase, "clan://TVBox/../../private-backups/hawk"));
        fails(() -> WorkspaceFiles.reference(base, "clan://TVBox/%252e%252e/private-backups/hawk"));
        check(!WorkspaceFiles.backups().getPath().startsWith(root.getPath() + File.separator), "backups outside served root");
        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        WorkspaceIO.zip(bundle, exported);
        File restored = WorkspaceIO.newStage(staging);
        WorkspaceIO.unzip(new ByteArrayInputStream(exported.toByteArray()), restored, new WorkspaceIO.Budget());
        check(WorkspaceIO.text(new File(restored, "config.json")).equals(WorkspaceIO.text(new File(bundle, "config.json"))), "ZIP roundtrip");
        WorkspaceIO.deleteTree(restored);
        System.out.println("PASS WorkspaceTest: " + checks + " checks");
    }
}
