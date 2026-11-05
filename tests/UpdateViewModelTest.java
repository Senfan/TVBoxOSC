package com.github.tvbox.osc.update;

import android.app.Application;
import android.os.Looper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tvbox.osc.update.UpdateState.Phase;

/** Actual VM, client, parser, state and integrity code; narrow JVM Android substitutes only.
 * Does not emulate Activity callbacks, real SharedPreferences or PackageManager acceptance. */
public final class UpdateViewModelTest {
    private static int cases;
    private static int failures;
    private static Path temporary;

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void phase(UpdateViewModel vm, Phase expected) {
        UpdateState state = vm.getState().getValue();
        require(state != null && state.phase == expected,
                "Expected " + expected + ", got " + (state == null ? "null" : state.phase + ": " + state.detail));
    }

    private static void await(UpdateViewModel vm, Phase expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (vm.getState().getValue().phase != expected && System.nanoTime() < deadline) {
            if (vm.getState().getValue().phase == Phase.ERROR) break;
            Looper.runOne(100);
        }
        phase(vm, expected);
    }

    private static void candidate(UpdateArtifact actual, UpdateArtifact expected) {
        require(actual != null && actual.toJson().equals(expected.toJson()), "Candidate was lost or replaced");
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory(temporary, "vm-");
        final HttpServer server;
        final String address;
        final AtomicInteger requests = new AtomicInteger();
        final List<UpdateViewModel> models = new ArrayList<>();
        volatile UpdateArtifact selected;
        volatile boolean offline;
        volatile java.util.concurrent.CountDownLatch apkRequested;
        volatile java.util.concurrent.CountDownLatch releaseApk;

        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            address = base + "/manifest";
            selected = artifact(base, 3);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                byte[] body;
                int status = offline ? 503 : 200;
                if (offline) body = "offline".getBytes(StandardCharsets.UTF_8);
                else if (exchange.getRequestURI().getPath().equals("/manifest")) {
                    JsonObject manifest = new JsonObject();
                    manifest.addProperty("schemaVersion", 1);
                    JsonArray artifacts = new JsonArray();
                    artifacts.add(selected.toJson());
                    manifest.add("artifacts", artifacts);
                    body = manifest.toString().getBytes(StandardCharsets.UTF_8);
                } else if (exchange.getRequestURI().getPath().startsWith("/apk/")) {
                    if (apkRequested != null) apkRequested.countDown();
                    if (releaseApk != null) gate(releaseApk);
                    body = bytes(Integer.parseInt(exchange.getRequestURI().getPath().substring(5)));
                } else {
                    status = 404;
                    body = new byte[0];
                }
                exchange.sendResponseHeaders(status, body.length);
                try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
                finally { exchange.close(); }
            });
            server.start();
            new UpdatePreferences(application(2)).setAddress(address);
        }

        Application application(long version) { return new Application(root.toFile(), version); }

        UpdateViewModel model(long version) {
            UpdateViewModel vm = new UpdateViewModel(application(version));
            models.add(vm);
            return vm;
        }

        UpdateViewModel downloaded() throws InterruptedException {
            UpdateViewModel vm = model(2);
            vm.check();
            await(vm, Phase.AVAILABLE);
            vm.download();
            await(vm, Phase.READY);
            candidate(pending(), selected);
            require(vm.getApkFile() != null && vm.getApkFile().isFile(), "READY needs a validated file");
            return vm;
        }

        UpdateArtifact pending() { return new UpdatePreferences(application(2)).pending(true); }

        void dispose(UpdateViewModel vm) throws Exception {
            vm.onCleared();
            Field field = UpdateViewModel.class.getDeclaredField("executor");
            field.setAccessible(true);
            require(((ExecutorService) field.get(vm)).awaitTermination(5, TimeUnit.SECONDS), "VM worker leaked");
            models.remove(vm);
        }

        @Override public void close() throws Exception {
            try {
                for (UpdateViewModel vm : new ArrayList<>(models)) dispose(vm);
            } finally { server.stop(0); }
        }
    }

    private static byte[] bytes(int version) {
        return ("JVM-only APK integrity fixture version " + version).getBytes(StandardCharsets.UTF_8);
    }

    private static UpdateArtifact artifact(String base, int version) {
        byte[] data = bytes(version);
        JsonObject value = new JsonObject();
        value.addProperty("applicationId", "com.github.tvbox.osc.tk");
        value.addProperty("brand", "generic");
        value.addProperty("mode", "normal");
        value.addProperty("abi", "arm64-v8a");
        value.addProperty("versionName", version + ".0-debug");
        value.addProperty("versionCode", version);
        value.addProperty("minSdk", 21);
        value.addProperty("apkUrl", base + "/apk/" + version);
        value.addProperty("sha256", UpdateIntegrity.hex(UpdateIntegrity.digest().digest(data)));
        value.addProperty("sizeBytes", data.length);
        return UpdateArtifact.parse(value, true);
    }

    private static void test(String name, Checked operation) {
        cases++;
        try {
            operation.run();
            System.out.println("PASS UpdateViewModelTest: " + name);
        } catch (Throwable error) {
            failures++;
            System.err.println("FAIL UpdateViewModelTest: " + name);
            error.printStackTrace(System.err);
        }
    }

    private interface Checked { void run() throws Exception; }

    private static void gate(java.util.concurrent.CountDownLatch latch) {
        try { require(latch.await(5, TimeUnit.SECONDS), "Validation/network test gate timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static void workerFinished(UpdateViewModel vm) throws Exception {
        Field field = UpdateViewModel.class.getDeclaredField("executor"); field.setAccessible(true);
        ((ExecutorService) field.get(vm)).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    private static void cleanup(Application application) throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        UpdateCleanup.request(application, done::countDown);
        require(done.await(2, TimeUnit.SECONDS), "Cleanup queued behind VM network/validation");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Pass a temporary test directory");
        temporary = Files.createDirectories(Path.of(args[0]));
        Looper.getMainLooper(); // Bind deterministic main-thread callback delivery before VM construction.

        test("restored successful install survives repeated installer results", () -> {
            try (Fixture fixture = new Fixture()) {
                UpdateViewModel previous = fixture.downloaded();
                Path apk = previous.getApkFile().toPath();
                previous.installHandedOff();
                phase(previous, Phase.HANDOFF);
                fixture.dispose(previous);
                UpdateViewModel restored = fixture.model(3);
                phase(restored, Phase.UPDATED);
                java.util.concurrent.CountDownLatch cleaned = new java.util.concurrent.CountDownLatch(1);
                UpdateCleanup.request(fixture.application(3), cleaned::countDown);
                require(cleaned.await(5, TimeUnit.SECONDS), "Cleanup did not finish");
                require(fixture.pending() == null && !Files.exists(apk), "Success did not consume pending APK");
                restored.installationReturned();
                restored.installationReturned();
                phase(restored, Phase.UPDATED);
                require(restored.getState().getValue().artifact == null && restored.getApkFile() == null,
                        "Successful recovery exposed a stale candidate");
            }
        });

        test("installer result with no pending candidate remains IDLE", () -> {
            try (Fixture fixture = new Fixture()) {
                UpdateViewModel vm = fixture.model(2);
                vm.installationReturned();
                phase(vm, Phase.IDLE);
                require(vm.getState().getValue().artifact == null && vm.getApkFile() == null, "Phantom candidate");
            }
        });

        test("installer result without configured source remains UNCONFIGURED", () -> {
            try (Fixture fixture = new Fixture()) {
                new UpdatePreferences(fixture.application(2)).setAddress("");
                UpdateViewModel vm = fixture.model(2);
                vm.installationReturned();
                phase(vm, Phase.UNCONFIGURED);
                require(vm.getState().getValue().artifact == null, "Phantom candidate");
            }
        });

        test("cache reclamation followed by installer return does not create AVAILABLE(null)", () -> {
            try (Fixture fixture = new Fixture()) {
                UpdateViewModel previous = fixture.downloaded();
                Path apk = previous.getApkFile().toPath();
                previous.installHandedOff();
                fixture.dispose(previous);
                Files.delete(apk);
                UpdateViewModel restored = fixture.model(2);
                phase(restored, Phase.IDLE);
                restored.installationReturned();
                phase(restored, Phase.IDLE);
                require(fixture.pending() == null && restored.getState().getValue().artifact == null,
                        "Missing cache restored a phantom candidate");
            }
        });

        test("cancelled installation retains candidate but invalidates install trust", () -> {
            try (Fixture fixture = new Fixture()) {
                UpdateViewModel vm = fixture.downloaded();
                vm.installHandedOff();
                vm.installationReturned();
                phase(vm, Phase.AVAILABLE);
                candidate(vm.getState().getValue().artifact, fixture.selected);
                candidate(fixture.pending(), fixture.selected);
                require(vm.getApkFile() == null, "Installer return retained a trusted file flag");
            }
        });

        test("same-candidate recheck survives reconstruction and offline cached validation", () -> {
            try (Fixture fixture = new Fixture()) {
                UpdateViewModel previous = fixture.downloaded();
                previous.check();
                await(previous, Phase.AVAILABLE);
                candidate(fixture.pending(), fixture.selected);
                fixture.dispose(previous);
                fixture.offline = true;
                int before = fixture.requests.get();
                UpdateViewModel restored = fixture.model(2);
                phase(restored, Phase.AVAILABLE);
                candidate(restored.getState().getValue().artifact, fixture.selected);
                require(restored.getApkFile() == null, "Reconstruction restored install trust");
                restored.download();
                await(restored, Phase.READY);
                require(fixture.requests.get() == before, "Cached recovery needed network access");
                require(restored.<Application>getApplication().validations == 1, "Cache was not revalidated");
            }
        });

        test("new manifest candidate does not discard completed download before replacement", () -> {
            try (Fixture fixture = new Fixture()) {
                UpdateViewModel previous = fixture.downloaded();
                UpdateArtifact completed = fixture.selected;
                fixture.selected = artifact(fixture.address.substring(0, fixture.address.lastIndexOf('/')), 4);
                previous.check();
                await(previous, Phase.AVAILABLE);
                candidate(previous.getState().getValue().artifact, fixture.selected);
                candidate(fixture.pending(), completed);
                fixture.dispose(previous);
                fixture.offline = true;
                UpdateViewModel restored = fixture.model(2);
                phase(restored, Phase.AVAILABLE);
                candidate(restored.getState().getValue().artifact, completed);
            }
        });

        test("failed recheck preserves current and durable candidate for offline retry", () -> {
            try (Fixture fixture = new Fixture()) {
                UpdateViewModel vm = fixture.downloaded();
                fixture.offline = true;
                vm.check();
                candidate(vm.getState().getValue().artifact, fixture.selected);
                await(vm, Phase.ERROR);
                candidate(vm.getState().getValue().artifact, fixture.selected);
                candidate(fixture.pending(), fixture.selected);
                require(vm.getApkFile() == null, "Failed check retained install trust");
                int before = fixture.requests.get();
                vm.download();
                await(vm, Phase.READY);
                require(fixture.requests.get() == before, "Offline retry redownloaded a completed candidate");
            }
        });

        test("new READY publication automatically reclaims replaced pending APK", () -> {
            try (Fixture fixture = new Fixture()) {
                UpdateViewModel vm = fixture.downloaded(); Path old = vm.getApkFile().toPath();
                fixture.selected = artifact(fixture.address.substring(0, fixture.address.lastIndexOf('/')), 4);
                vm.check(); await(vm, Phase.AVAILABLE); vm.download(); await(vm, Phase.READY);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (Files.exists(old) && System.nanoTime() < deadline) Thread.sleep(10);
                require(!Files.exists(old), "Final VM lease release failed to reclaim replaced pending");
                candidate(fixture.pending(), fixture.selected);
                require(vm.getApkFile().isFile(), "Automatic cleanup removed new READY pending");
            }
        });

        test("cleanup completes during active slow download and protects its part", () -> {
            try (Fixture fixture = new Fixture()) {
                UpdateViewModel vm = fixture.model(2); vm.check(); await(vm, Phase.AVAILABLE);
                fixture.apkRequested = new java.util.concurrent.CountDownLatch(1);
                fixture.releaseApk = new java.util.concurrent.CountDownLatch(1);
                vm.download();
                try {
                    require(fixture.apkRequested.await(2, TimeUnit.SECONDS), "Download did not begin");
                    cleanup(vm.getApplication());
                    try (java.util.stream.Stream<Path> files = Files.list(UpdateCleanup.directory(vm.getApplication()).toPath())) {
                        require(files.anyMatch(file -> file.getFileName().toString().endsWith(".part")), "Active download part deleted");
                    }
                } finally { fixture.releaseApk.countDown(); }
                await(vm, Phase.READY);
            }
        });

        for (boolean cachedDownload : new boolean[] {false, true}) {
            test((cachedDownload ? "cached download" : "install validation")
                    + " cancellation holds lease through worker and dropped main callback", () -> {
                try (Fixture fixture = new Fixture()) {
                    UpdateViewModel vm = fixture.downloaded(); Path file = vm.getApkFile().toPath();
                    workerFinished(vm); Looper.drain();
                    java.util.concurrent.CountDownLatch validating = new java.util.concurrent.CountDownLatch(1);
                    java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
                    vm.<Application>getApplication().beforeValidation = () -> { validating.countDown(); gate(resume); };
                    if (cachedDownload) vm.download(); else vm.validateForInstall();
                    try {
                        require(validating.await(2, TimeUnit.SECONDS), "Cached validator did not begin");
                        new UpdatePreferences(vm.getApplication()).clearPending();
                        vm.cancel(); cleanup(vm.getApplication());
                        require(Files.exists(file), "Cancellation released a still-running validation lease");
                    } finally { resume.countDown(); }
                    workerFinished(vm);
                    cleanup(vm.getApplication()); require(Files.exists(file), "Worker finished but posted callback lease released early");
                    Looper.drain(); cleanup(vm.getApplication());
                    require(!Files.exists(file), "Generation-dropped callback leaked lease");
                    phase(vm, Phase.CANCELLED);
                }
            });
        }

        for (boolean cancelBeforePublish : new boolean[] {false, true}) {
            test("rename-to-pending main callback gap " + (cancelBeforePublish ? "cancelled" : "published"), () -> {
                try (Fixture fixture = new Fixture()) {
                    UpdateViewModel vm = fixture.model(2); vm.check(); await(vm, Phase.AVAILABLE);
                    java.util.concurrent.CountDownLatch validating = new java.util.concurrent.CountDownLatch(1);
                    java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
                    vm.<Application>getApplication().beforeValidation = () -> { validating.countDown(); gate(resume); };
                    vm.download();
                    try { require(validating.await(2, TimeUnit.SECONDS), "Part validator did not begin"); }
                    finally { resume.countDown(); }
                    workerFinished(vm); // No main callbacks: destination exists, pending publication still queued.
                    Path file = UpdateCleanup.directory(vm.getApplication()).toPath().resolve(fixture.selected.sha256 + ".apk");
                    require(Files.exists(file) && fixture.pending() == null, "No rename-to-publication gap reached");
                    if (cancelBeforePublish) vm.cancel();
                    cleanup(vm.getApplication()); require(Files.exists(file), "Cleanup removed renamed file before callback");
                    Looper.drain(); cleanup(vm.getApplication());
                    if (cancelBeforePublish) {
                        require(!Files.exists(file) && fixture.pending() == null, "Cancelled callback leaked renamed owned orphan");
                        phase(vm, Phase.CANCELLED);
                    } else {
                        require(Files.exists(file), "Published pending file was deleted");
                        candidate(fixture.pending(), fixture.selected); phase(vm, Phase.READY);
                    }
                }
            });
        }

        System.out.println("UpdateViewModelTest: " + cases + " cases, " + (cases - failures)
                + " passed, " + failures + " failed");
        if (failures != 0) System.exit(1);
    }
}
