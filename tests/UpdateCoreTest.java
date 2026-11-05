package com.github.tvbox.osc.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/** Standalone policy tests; not Android lifecycle, PackageManager or installer acceptance tests. */
public final class UpdateCoreTest {
    private static int cases;
    private static int failures;
    private static final String PACKAGE = "com.github.tvbox.osc";
    private static final String BRAND = "tvbox";
    private static final String MODE = "release";
    private static final String ABI = "arm64-v8a";
    private static final String HASH = "0123456789abcdef".repeat(4);

    interface Checked { void run() throws Exception; }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static <T extends Throwable> T expect(Class<T> type, Checked operation) throws Exception {
        try { operation.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return type.cast(error);
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + type.getSimpleName() + " but operation succeeded");
    }

    private static void test(String name, Checked operation) {
        cases++;
        try { operation.run(); }
        catch (Throwable error) {
            failures++;
            System.err.println("FAIL UpdateCoreTest: " + name);
            error.printStackTrace(System.err);
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return UpdateIntegrity.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static JsonObject artifactJson(String url, long size, String hash) {
        JsonObject object = new JsonObject();
        object.addProperty("applicationId", PACKAGE);
        object.addProperty("brand", BRAND);
        object.addProperty("mode", MODE);
        object.addProperty("abi", ABI);
        object.addProperty("versionName", "2.0");
        object.addProperty("versionCode", 200);
        object.addProperty("minSdk", 21);
        object.addProperty("apkUrl", url);
        object.addProperty("sha256", hash);
        object.addProperty("sizeBytes", size);
        return object;
    }

    private static JsonObject valid() {
        return artifactJson("https://updates.example.com/app.apk", 12345, HASH);
    }

    private static JsonObject version(int version) {
        JsonObject object = valid();
        object.addProperty("versionCode", version);
        return object;
    }

    private static JsonObject manifestJson(JsonObject... artifacts) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonArray array = new JsonArray();
        for (JsonObject artifact : artifacts) array.add(artifact);
        root.add("artifacts", array);
        return root;
    }

    private static UpdateManifest manifest(JsonObject... artifacts) {
        return UpdateManifest.parse(manifestJson(artifacts).toString(), false);
    }

    private static UpdateManifest.Selection select(UpdateManifest manifest, long installed) {
        return manifest.select(PACKAGE, BRAND, MODE, ABI, 21, installed);
    }

    private static void urls() {
        for (boolean debug : new boolean[]{false, true}) {
            for (String url : new String[]{"https://example.com/a.apk", "HTTPS://example.com/a?x=1",
                    "https://127.0.0.1:443/a", "https://[::1]/a", "https://example.com:65535/a"}) {
                test("HTTPS debug=" + debug + " " + url,
                        () -> require(url.equals(UpdateUrlPolicy.validate(url, debug)), "URL changed"));
            }
            test("trim URL debug=" + debug, () -> require("https://example.com/a".equals(
                    UpdateUrlPolicy.validate(" \nhttps://example.com/a\t ", debug)), "Not trimmed"));
            for (String url : new String[]{"", " ", "https:///a", "/relative", "//example.com/a",
                    "ftp://127.0.0.1/a", "https://user:secret@example.com/a", "https://@example.com/a",
                    "https://us%65r@example.com/a", "https://example.com/a#fragment",
                    "https://example.com/a#", "https://example.com:0/a", "https://example.com:65536/a",
                    "https://example.com:-2/a", "https://example.com/a b", "https://example.com/%ZZ"}) {
                test("reject URL debug=" + debug + " " + url,
                        () -> expect(IllegalArgumentException.class, () -> UpdateUrlPolicy.validate(url, debug)));
            }
            test("null URL debug=" + debug,
                    () -> expect(IllegalArgumentException.class, () -> UpdateUrlPolicy.validate(null, debug)));
        }
        for (String host : new String[]{"127.0.0.1", "localhost", "LOCALHOST", "[::1]", "10.0.2.2"}) {
            String url = "http://" + host + ":8080/a";
            test("debug HTTP allow " + host, () -> require(url.equals(UpdateUrlPolicy.validate(url, true)), "Not allowed"));
            test("release HTTP deny " + host,
                    () -> expect(IllegalArgumentException.class, () -> UpdateUrlPolicy.validate(url, false)));
        }
        for (String host : new String[]{"example.com", "127.0.0.2", "127.1", "2130706433",
                "localhost.example.com", "10.0.2.3", "192.168.1.1", "0.0.0.0", "[::ffff:127.0.0.1]"}) {
            for (boolean debug : new boolean[]{false, true}) {
                test("HTTP host deny " + host + " debug=" + debug,
                        () -> expect(IllegalArgumentException.class,
                                () -> UpdateUrlPolicy.validate("http://" + host + "/a", debug)));
            }
        }
        String prefix = "https://example.com/";
        test("URL 4096 boundary", () -> require(UpdateUrlPolicy.validate(
                prefix + "x".repeat(4096 - prefix.length()), false).length() == 4096, "Wrong limit"));
        test("URL 4097 boundary", () -> expect(IllegalArgumentException.class, () ->
                UpdateUrlPolicy.validate(prefix + "x".repeat(4097 - prefix.length()), false)));
    }

    private static void fields() {
        String[] numericFields = {"schemaVersion", "versionCode", "minSdk", "sizeBytes"};
        long[] maxima = {1, 2100000000L, 10000, UpdateArtifact.MAX_APK_BYTES};
        for (int i = 0; i < numericFields.length; i++) {
            String field = numericFields[i];
            long max = maxima[i];
            for (long value : new long[]{1, max}) {
                test(field + " allowed " + value, () -> {
                    JsonObject object = field.equals("schemaVersion") ? manifestJson(valid()) : valid();
                    object.addProperty(field, value);
                    if (field.equals("schemaVersion")) UpdateManifest.parse(object.toString(), false);
                    else require(UpdateArtifact.parse(object, false).toJson().get(field).getAsLong() == value,
                            "Numeric value changed");
                });
            }
            String[] invalid = {"0", "-1", Long.toString(max + 1), "1.0", "1e0", "1E+0", "\"1\"",
                    "true", "false", "null", "[]", "{}", "99999999999", "9223372036854775808",
                    "9999999999999999999999999999999999999999"};
            for (String value : invalid) {
                test(field + " reject " + value, () -> {
                    JsonObject object = field.equals("schemaVersion") ? manifestJson(valid()) : valid();
                    object.add(field, JsonParser.parseString(value));
                    expect(IllegalArgumentException.class, () -> {
                        if (field.equals("schemaVersion")) UpdateManifest.parse(object.toString(), false);
                        else UpdateArtifact.parse(object, false);
                    });
                });
            }
            test(field + " missing", () -> {
                JsonObject object = field.equals("schemaVersion") ? manifestJson(valid()) : valid();
                object.remove(field);
                expect(IllegalArgumentException.class, () -> {
                    if (field.equals("schemaVersion")) UpdateManifest.parse(object.toString(), false);
                    else UpdateArtifact.parse(object, false);
                });
            });
        }
        String[] textFields = {"applicationId", "brand", "mode", "abi", "versionName", "apkUrl", "sha256", "notes"};
        for (String field : textFields) {
            for (String value : new String[]{"1", "true", "null", "[]", "{}"}) {
                test(field + " reject type " + value, () -> {
                    JsonObject object = valid();
                    object.add(field, JsonParser.parseString(value));
                    expect(IllegalArgumentException.class, () -> UpdateArtifact.parse(object, false));
                });
            }
            if (!field.equals("notes")) {
                test(field + " missing", () -> {
                    JsonObject object = valid();
                    object.remove(field);
                    expect(IllegalArgumentException.class, () -> UpdateArtifact.parse(object, false));
                });
                test(field + " blank", () -> {
                    JsonObject object = valid();
                    object.addProperty(field, " \t ");
                    expect(IllegalArgumentException.class, () -> UpdateArtifact.parse(object, false));
                });
            }
        }
        String[] boundedFields = {"applicationId", "brand", "mode", "abi", "versionName", "notes"};
        int[] bounds = {200, 40, 40, 40, 100, 8192};
        for (int i = 0; i < boundedFields.length; i++) {
            String field = boundedFields[i];
            int limit = bounds[i];
            test(field + " exact length", () -> {
                JsonObject object = valid();
                object.addProperty(field, "a".repeat(limit));
                UpdateArtifact.parse(object, false);
            });
            test(field + " excessive length", () -> {
                JsonObject object = valid();
                object.addProperty(field, "a".repeat(limit + 1));
                expect(IllegalArgumentException.class, () -> UpdateArtifact.parse(object, false));
            });
        }
        for (String hash : new String[]{"a".repeat(63), "a".repeat(65), "g".repeat(64), "_".repeat(64)}) {
            test("invalid SHA " + hash, () -> {
                JsonObject object = valid();
                object.addProperty("sha256", hash);
                expect(IllegalArgumentException.class, () -> UpdateArtifact.parse(object, false));
            });
        }
        test("SHA case normalization and round trip", () -> {
            JsonObject object = valid();
            object.addProperty("sha256", " " + HASH.toUpperCase(java.util.Locale.ROOT) + " ");
            object.addProperty("notes", " release notes ");
            UpdateArtifact artifact = UpdateArtifact.parse(object, false);
            require(HASH.equals(artifact.sha256), "Hash not normalized");
            require("release notes".equals(artifact.notes), "Notes not trimmed");
            require(artifact.toJson().equals(UpdateArtifact.parse(artifact.toJson(), false).toJson()), "Round trip changed");
        });
        test("optional notes", () -> require(UpdateArtifact.parse(valid(), false).notes.isEmpty(), "Not empty"));
        test("empty notes", () -> {
            JsonObject object = valid();
            object.addProperty("notes", " \n ");
            require(UpdateArtifact.parse(object, false).notes.isEmpty(), "Not empty");
        });
        for (boolean debug : new boolean[]{false, true}) {
            test("artifact HTTP debug=" + debug, () -> {
                JsonObject object = valid();
                object.addProperty("apkUrl", "http://127.0.0.1/a.apk");
                if (debug) UpdateArtifact.parse(object, true);
                else expect(IllegalArgumentException.class, () -> UpdateArtifact.parse(object, false));
            });
        }
    }

    private static void manifests() {
        for (String text : new String[]{"[]", "null", "true", "42", "\"text\"", "{", "{\"schemaVersion\":1}",
                "{\"schemaVersion\":1,\"artifacts\":{}}", "{\"schemaVersion\":1,\"artifacts\":null}",
                "{\"schemaVersion\":1,\"artifacts\":[null]}", "{\"schemaVersion\":1,\"artifacts\":[1]}"}) {
            test("reject manifest " + text,
                    () -> expect(RuntimeException.class, () -> UpdateManifest.parse(text, false)));
        }
        test("empty manifest", () -> {
            UpdateManifest.Selection result = select(manifest(), 100);
            require(!result.compatible && result.artifact == null, "Empty manifest selected");
        });
        for (int count : new int[]{256, 257}) {
            test("manifest entry boundary " + count, () -> {
                JsonObject[] artifacts = new JsonObject[count];
                Arrays.fill(artifacts, valid());
                if (count == 256) manifest(artifacts);
                else expect(IllegalArgumentException.class, () -> manifest(artifacts));
            });
        }
        for (int[] order : new int[][]{{101, 500, 300}, {500, 101, 300}, {300, 101, 500}}) {
            test("highest version " + Arrays.toString(order), () -> {
                UpdateManifest.Selection result = select(manifest(version(order[0]), version(order[1]), version(order[2])), 100);
                require(result.compatible && result.artifact.versionCode == 500, "Highest not selected");
            });
        }
        for (long installed : new long[]{200, 201, Long.MAX_VALUE}) {
            test("no downgrade installed=" + installed, () -> {
                UpdateManifest.Selection result = select(manifest(version(199), version(200)), installed);
                require(result.compatible && result.artifact == null, "Offered downgrade/equal version");
            });
        }
        test("newer one-step version", () -> require(select(manifest(version(201)), 200).artifact.versionCode == 201, "Not selected"));
        for (int[] order : new int[][]{{200, 200, 500}, {500, 200, 200}, {200, 500, 200}}) {
            test("lower duplicate ignored " + Arrays.toString(order), () -> require(
                    select(manifest(version(order[0]), version(order[1]), version(order[2])), 100).artifact.versionCode == 500,
                    "Lower duplicate obscured highest"));
        }
        for (String conflict : new String[]{"identical", "apkUrl", "sha256", "sizeBytes"}) {
            test("highest duplicate conflict " + conflict, () -> {
                JsonObject first = version(500);
                JsonObject second = first.deepCopy();
                if (conflict.equals("apkUrl")) second.addProperty("apkUrl", "https://other.example.com/a.apk");
                if (conflict.equals("sha256")) second.addProperty("sha256", "a".repeat(64));
                if (conflict.equals("sizeBytes")) second.addProperty("sizeBytes", 54321);
                expect(IllegalArgumentException.class, () -> select(manifest(version(200), first, second), 100));
            });
        }
        test("duplicate installed versions need no update", () -> require(
                select(manifest(version(200), version(200)), 200).artifact == null, "Installed duplicate rejected"));
        String[] fields = {"applicationId", "brand", "mode", "abi"};
        for (String field : fields) {
            for (String mismatch : new String[]{"other", valid().get(field).getAsString().toUpperCase(java.util.Locale.ROOT)}) {
                test("mismatch " + field + "=" + mismatch, () -> {
                    JsonObject wrong = version(999);
                    wrong.addProperty(field, mismatch);
                    UpdateManifest.Selection none = select(manifest(wrong), 100);
                    require(!none.compatible && none.artifact == null, "Mismatched artifact selected");
                    require(select(manifest(wrong, version(200)), 100).artifact.versionCode == 200,
                            "Mismatch interfered with compatible version");
                });
            }
        }
        test("minSDK above device", () -> {
            JsonObject tooNew = version(999);
            tooNew.addProperty("minSdk", 22);
            UpdateManifest.Selection none = select(manifest(tooNew), 100);
            require(!none.compatible && none.artifact == null, "SDK mismatch selected");
            require(select(manifest(tooNew, version(200)), 100).artifact.versionCode == 200, "SDK mismatch won");
        });
        test("minSDK exact boundary", () -> require(select(manifest(version(200)), 100).artifact != null, "Boundary rejected"));
        test("minSDK lower accepted", () -> require(manifest(version(200)).select(
                PACKAGE, BRAND, MODE, ABI, 35, 100).artifact != null, "Lower minSDK rejected"));
        test("incompatible highest duplicate ignored", () -> {
            JsonObject incompatible = version(999);
            incompatible.addProperty("abi", "x86");
            require(select(manifest(incompatible, incompatible.deepCopy(), version(200)), 100).artifact.versionCode == 200,
                    "Incompatible duplicate caused ambiguity");
        });
    }

    private static void integrity(Path temporary) throws Exception {
        byte[] bytes = new byte[96 * 1024 + 7];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31);
        Path file = temporary.resolve("integrity.apk");
        Files.write(file, bytes);
        String hash = sha256(bytes);
        test("SHA-256 known vector", () -> require(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(
                        sha256("abc".getBytes(StandardCharsets.US_ASCII))), "SHA vector mismatch"));
        test("hex signed bytes", () -> require("00017f80ff".equals(UpdateIntegrity.hex(
                new byte[]{0, 1, 127, (byte) 128, (byte) 255})), "Wrong unsigned hex"));
        test("integrity exact bytes", () -> UpdateIntegrity.verify(file.toFile(), bytes.length, hash, () -> false));
        for (long size : new long[]{0, -1, bytes.length - 1, bytes.length + 1L, UpdateArtifact.MAX_APK_BYTES + 1}) {
            test("integrity invalid length " + size, () -> expect(IOException.class,
                    () -> UpdateIntegrity.verify(file.toFile(), size, hash, () -> false)));
        }
        test("integrity missing", () -> expect(IOException.class,
                () -> UpdateIntegrity.verify(temporary.resolve("missing.apk").toFile(), bytes.length, hash, () -> false)));
        test("integrity directory", () -> expect(IOException.class,
                () -> UpdateIntegrity.verify(temporary.toFile(), bytes.length, hash, () -> false)));
        Path empty = temporary.resolve("empty.apk");
        Files.createFile(empty);
        test("integrity empty file", () -> expect(IOException.class,
                () -> UpdateIntegrity.verify(empty.toFile(), 0, sha256(new byte[0]), () -> false)));
        test("integrity bad hash", () -> expect(IOException.class,
                () -> UpdateIntegrity.verify(file.toFile(), bytes.length, "a".repeat(64), () -> false)));
        test("integrity hash case strict", () -> expect(IOException.class,
                () -> UpdateIntegrity.verify(file.toFile(), bytes.length, hash.toUpperCase(java.util.Locale.ROOT), () -> false)));
        Path changed = temporary.resolve("tampered.apk");
        byte[] tampered = bytes.clone();
        tampered[42] ^= 1;
        Files.write(changed, tampered);
        test("integrity same-size tamper", () -> expect(IOException.class,
                () -> UpdateIntegrity.verify(changed.toFile(), bytes.length, hash, () -> false)));
        test("integrity immediate cancel", () -> {
            IOException error = expect(IOException.class,
                    () -> UpdateIntegrity.verify(file.toFile(), bytes.length, hash, () -> true));
            require(error.getMessage().contains("取消"), "Not cancellation");
            require(Arrays.equals(bytes, Files.readAllBytes(file)), "Cancel changed file");
        });
        test("integrity mid-read cancel", () -> {
            int[] reads = {0};
            IOException error = expect(IOException.class,
                    () -> UpdateIntegrity.verify(file.toFile(), bytes.length, hash, () -> ++reads[0] == 2));
            require(error.getMessage().contains("取消") && reads[0] == 2, "Did not stop during read");
        });
        test("client cancel signal", () -> {
            UpdateClient client = new UpdateClient(true);
            require(!client.stopped(), "Fresh client stopped");
            client.cancel();
            client.cancel();
            require(client.stopped(), "Cancel not sticky/idempotent");
        });
        test("client thread interrupt signal", () -> {
            UpdateClient client = new UpdateClient(true);
            Thread.currentThread().interrupt();
            try { require(client.stopped(), "Interrupted thread not stopped"); }
            finally { Thread.interrupted(); }
            require(!client.stopped(), "Thread interrupt became client cancellation");
        });
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Pass a temporary work directory");
        Path temporary = Files.createDirectories(Path.of(args[0]));
        urls();
        fields();
        manifests();
        integrity(temporary);
        System.out.println("UpdateCoreTest: " + cases + " cases, " + (cases - failures) + " passed, " + failures + " failed");
        if (failures != 0) System.exit(1);
    }
}
