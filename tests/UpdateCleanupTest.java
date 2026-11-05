package com.github.tvbox.osc.update;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DiskPreferences;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Looper;
import com.google.gson.JsonObject;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real coordinator/preferences/receiver; Android persistence and package inspection boundaries only are stubbed. */
public final class UpdateCleanupTest {
    private static Path temporary;
    private static int cases, failures;
    private static final String ADDRESS = "https://example.com/manifest.json";
    private interface Checked { void run() throws Exception; }
    private static void require(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
    private static void test(String name, Checked operation) {
        cases++;
        try { operation.run(); System.out.println("PASS UpdateCleanupTest: " + name); }
        catch (Throwable error) { failures++; System.err.println("FAIL UpdateCleanupTest: " + name); error.printStackTrace(); }
        finally { UpdateCleanup.delete = File::delete; }
    }
    private static Application app(long version) throws Exception {
        Application app = new Application(Files.createTempDirectory(temporary, "cleanup-").toFile(), version);
        new UpdatePreferences(app).setAddress(ADDRESS);
        return app;
    }
    private static DiskPreferences store(Application app) {
        return (DiskPreferences) app.getSharedPreferences("update_pending", Context.MODE_PRIVATE);
    }
    private static UpdateArtifact artifact(int version) {
        JsonObject value = new JsonObject();
        value.addProperty("applicationId", "com.github.tvbox.osc.tk");
        value.addProperty("brand", "generic"); value.addProperty("mode", "normal"); value.addProperty("abi", "arm64-v8a");
        value.addProperty("versionName", version + ".0"); value.addProperty("versionCode", version); value.addProperty("minSdk", 21);
        value.addProperty("apkUrl", "https://example.com/" + version + ".apk");
        value.addProperty("sizeBytes", data(version).length);
        value.addProperty("sha256", UpdateIntegrity.hex(UpdateIntegrity.digest().digest(data(version))));
        return UpdateArtifact.parse(value, true);
    }
    private static byte[] data(int version) { return ("APK fixture " + version).getBytes(StandardCharsets.UTF_8); }
    private static Path apk(Application app, int version) throws Exception {
        Path directory = Files.createDirectories(UpdateCleanup.directory(app).toPath());
        Path file = directory.resolve(artifact(version).sha256 + ".apk");
        Files.write(file, data(version));
        return file;
    }
    private static void legacy(Application app, Path file, int version, String pkg, String brand, String mode, String abi) {
        PackageInfo info = new PackageInfo(); info.packageName = pkg; info.versionCode = version;
        info.applicationInfo = new ApplicationInfo(); info.applicationInfo.metaData = new Bundle();
        info.applicationInfo.metaData.putString("tvbox.update.brand", brand);
        info.applicationInfo.metaData.putString("tvbox.update.mode", mode);
        info.applicationInfo.metaData.putString("tvbox.update.abi", abi);
        app.getPackageManager().archives.put(file.toAbsolutePath().toString(), info);
    }
    private static void legacy(Application app, Path file, int version) {
        legacy(app, file, version, app.getPackageName(), "generic", "normal", "arm64-v8a");
    }
    private static void request(Application app) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        UpdateCleanup.request(app, done::countDown);
        require(done.await(5, TimeUnit.SECONDS), "Async cleanup failed to complete");
    }
    public static void main(String[] args) throws Exception {
        temporary = Files.createDirectories(Path.of(args[0]));
        Looper.getMainLooper();
        test("success without VM removes APK and leaves actual-version receipt", () -> {
            Application app = app(3); Path file = apk(app, 3);
            new UpdatePreferences(app).savePending(artifact(3), ADDRESS);
            request(app);
            require(!Files.exists(file) && !UpdateCleanup.directory(app).exists(), "Installed file/directory remained");
            require(new UpdatePreferences(app).rawPending(true) == null, "Pending remained");
            UpdateViewModel vm = new UpdateViewModel(app);
            require(vm.getState().getValue().phase == UpdateState.Phase.UPDATED, "Completion lost before VM creation");
            vm.installationReturned(); vm.installationReturned();
            require(vm.getState().getValue().phase == UpdateState.Phase.UPDATED, "Repeated results lost success");
            vm.onCleared(); request(app);
        });
        test("raw pending remains protected when Hawk source changes independently", () -> {
            Application app = app(2); Path file = apk(app, 3);
            new UpdatePreferences(app).savePending(artifact(3), ADDRESS);
            Hawk.put("tvbox_update_manifest_url", "https://example.com/other");
            require(new UpdatePreferences(app).pending(true) == null, "Candidate ignored address filtering");
            UpdateCleanup.clean(app);
            require(Files.exists(file), "Source-independent pending protection lost");
        });
        test("installer survives address change and preference memory recreation", () -> {
            Application app = app(2); Path file = apk(app, 3);
            UpdatePreferences prefs = new UpdatePreferences(app); prefs.savePending(artifact(3), ADDRESS);
            String token = UpdateCleanup.holdForInstall(app, artifact(3));
            prefs.setAddress("https://example.com/other");
            Context.forgetMemory();
            Application restarted = new Application(app.getCacheDir().getParentFile(), 2);
            request(restarted); require(Files.exists(file), "Restart/address removed installer file");
            UpdateCleanup.releaseInstall(restarted, null); UpdateCleanup.releaseInstall(restarted, "wrong-token");
            request(restarted); require(Files.exists(file), "Nonmatching token released installer");
            UpdateCleanup.releaseInstall(restarted, token); request(restarted);
            require(!Files.exists(file), "Matching token failed to release owned orphan");
        });
        test("multiple installer tokens release independently", () -> {
            Application app = app(2); Path first = apk(app, 3), second = apk(app, 4);
            String a = UpdateCleanup.holdForInstall(app, artifact(3));
            String b = UpdateCleanup.holdForInstall(app, artifact(4));
            UpdateCleanup.releaseInstall(app, a); request(app);
            require(!Files.exists(first) && Files.exists(second), "Release affected another hold");
            UpdateCleanup.releaseInstall(app, b); request(app); require(!Files.exists(second), "Second hold remained");
        });
        test("failed deletion keeps success and retries without pending", () -> {
            Application app = app(3); Path file = apk(app, 3);
            new UpdatePreferences(app).savePending(artifact(3), ADDRESS);
            UpdateCleanup.delete = target -> false; request(app);
            require(Files.exists(file) && new UpdatePreferences(app).rawPending(true) == null, "Delete failure changed reconciliation");
            UpdateViewModel vm = new UpdateViewModel(app);
            require(vm.getState().getValue().phase == UpdateState.Phase.UPDATED, "Deletion failure became installation failure");
            vm.onCleared(); request(app);
            UpdateCleanup.delete = File::delete; request(app); require(!Files.exists(file), "Ownership retry lost file");
        });
        test("installed hold reconciliation never clears newer pending", () -> {
            Application app = app(3); Path old = apk(app, 3), newer = apk(app, 4);
            UpdateCleanup.holdForInstall(app, artifact(3));
            new UpdatePreferences(app).savePending(artifact(4), ADDRESS); request(app);
            require(!Files.exists(old) && Files.exists(newer), "Success damaged newer candidate");
            require(new UpdatePreferences(app).rawPending(true).versionCode == 4, "Newer pending cleared");
        });
        test("owned stale high-version orphan is reclaimed without APK parsing", () -> {
            Application app = app(2); Path file = apk(app, 5);
            UpdatePreferences prefs = new UpdatePreferences(app); prefs.savePending(artifact(5), ADDRESS); prefs.clearPending();
            UpdateCleanup.clean(app); require(!Files.exists(file), "Owned orphan remained");
        });
        test("unowned legacy actual compatible installed APK is reclaimed", () -> {
            Application app = app(3); Path file = apk(app, 2); legacy(app, file, 2);
            UpdateCleanup.clean(app); require(!Files.exists(file), "Safe legacy APK remained");
        });
        test("higher unreadable and each incompatible legacy identity are preserved", () -> {
            Application app = app(3);
            Path high = apk(app, 4); legacy(app, high, 4);
            Path unknown = apk(app, 5);
            String[] keys = {"other.pkg", "otherBrand", "otherMode", "otherAbi"};
            Path[] incompatible = new Path[4];
            for (int i = 0; i < 4; i++) {
                incompatible[i] = apk(app, 6 + i);
                legacy(app, incompatible[i], 2, i == 0 ? keys[i] : app.getPackageName(),
                        i == 1 ? keys[i] : "generic", i == 2 ? keys[i] : "normal", i == 3 ? keys[i] : "arm64-v8a");
            }
            UpdateCleanup.clean(app); require(Files.exists(high) && Files.exists(unknown), "Higher/unreadable legacy removed");
            for (Path file : incompatible) require(Files.exists(file), "Incompatible legacy removed");
        });
        test("malformed pending is preserved and conservatively protects cache", () -> {
            Application app = app(3); Path file = apk(app, 2); legacy(app, file, 2);
            Path part = file.getParent().resolve("download-orphan.part"); Files.writeString(part, "part");
            store(app).edit().putString("artifact", "{broken").commit();
            require(new UpdatePreferences(app).pending(true) == null, "Malformed candidate parsed");
            request(app); require(Files.exists(file) && Files.exists(part), "Malformed metadata was treated as no protection");
            require(store(app).getString("artifact", "").equals("{broken"), "Parsing destructively cleared malformed record");
        });
        test("malformed installer and wrong preference types fail closed", () -> {
            Application app = app(3); Path file = apk(app, 2); legacy(app, file, 2);
            store(app).edit().putString("install.bad", "bad").commit();
            UpdateCleanup.clean(app); require(Files.exists(file), "Malformed installer ignored");
            store(app).edit().remove("install.bad").commit(); store(app).putWrongType("artifact");
            UpdateCleanup.clean(app); require(Files.exists(file), "Wrong preference type ignored");
        });
        test("failed hold persistence throws and never enables cleanup deletion", () -> {
            Application app = app(2); Path file = apk(app, 3); store(app).failCommits = true;
            boolean threw = false;
            try { UpdateCleanup.holdForInstall(app, artifact(3)); } catch (IllegalStateException expected) { threw = true; }
            require(threw, "Failed installer hold did not throw");
            request(app); require(Files.exists(file), "Failed persistence permitted deletion");
            store(app).failCommits = false;
        });
        test("failed installer release never escapes and retains exact hold for retry", () -> {
            Application app = app(2); Path file = apk(app, 3);
            String token = UpdateCleanup.holdForInstall(app, artifact(3)); store(app).failCommits = true;
            UpdateCleanup.releaseInstall(app, token); request(app);
            require(Files.exists(file) && new UpdatePreferences(app).records().holds.size() == 1,
                    "Failed release removed in-memory installer protection");
            store(app).failCommits = false;
            new UpdatePreferences(app).setAddress("https://example.com/other"); request(app);
            require(Files.exists(file), "Later successful unrelated commit persisted failed release");
            UpdateCleanup.releaseInstall(app, token); request(app); require(!Files.exists(file), "Release retry failed");
        });
        test("failed success persistence preserves UPDATED and APK until retry", () -> {
            Application app = app(3); Path file = apk(app, 3);
            new UpdatePreferences(app).savePending(artifact(3), ADDRESS); store(app).failCommits = true;
            UpdateViewModel vm = new UpdateViewModel(app);
            require(vm.getState().getValue().phase == UpdateState.Phase.UPDATED, "Failed success commit hid installed version");
            request(app); require(Files.exists(file), "Failed receipt persistence allowed deletion");
            vm.onCleared(); store(app).failCommits = false;
            new UpdatePreferences(app).clearPending(); request(app); require(!Files.exists(file), "Failure-safe cleanup never recovered");
        });
        test("failed candidate clear is conservative even after memory publishes removal", () -> {
            Application app = app(2); Path file = apk(app, 3);
            UpdatePreferences prefs = new UpdatePreferences(app); prefs.savePending(artifact(3), ADDRESS);
            store(app).failCommits = true;
            try { prefs.clearPending(); } catch (IllegalStateException expected) { }
            request(app); require(Files.exists(file), "Commit failure unprotected in-memory orphan");
            store(app).failCommits = false; prefs.clearPending(); request(app);
            require(!Files.exists(file), "Successful retry remained stuck");
        });
        test("preference read failure finishes async cleanup without deletion", () -> {
            Application app = app(3); Path file = apk(app, 2); legacy(app, file, 2);
            store(app).failReads = true; request(app); require(Files.exists(file), "Read failure removed cache");
            store(app).failReads = false;
        });
        test("orphan generated parts and empty updates directory are removed", () -> {
            Application app = app(2); Path directory = Files.createDirectories(UpdateCleanup.directory(app).toPath());
            Files.writeString(directory.resolve("download-1234.part"), "part"); request(app);
            require(!Files.exists(directory), "Orphan part/empty directory remained");
            request(app);
        });
        test("empty nested directories are reclaimed but unknown nonempty siblings remain", () -> {
            Application app = app(2); Path root = Files.createDirectories(UpdateCleanup.directory(app).toPath());
            Path empty = Files.createDirectories(root.resolve("empty/a/b"));
            Path keep = Files.createDirectories(root.resolve("keep/a")); Files.writeString(keep.resolve("unknown"), "keep");
            request(app);
            require(!Files.exists(empty) && !Files.exists(root.resolve("empty")), "Empty nested directories remained");
            require(Files.readString(keep.resolve("unknown")).equals("keep"), "Unknown nonempty directory changed");
        });
        test("empty pruning honors depth limit and active or uncertain protections", () -> {
            Application app = app(2); Path root = Files.createDirectories(UpdateCleanup.directory(app).toPath());
            Path deep = root; for (int i = 0; i < 9; i++) deep = Files.createDirectory(deep.resolve("d"));
            Path empty = Files.createDirectory(root.resolve("empty"));
            UpdateCleanup.Lease lease = UpdateCleanup.acquire(root.toFile()); request(app);
            require(Files.exists(empty), "Active lease allowed directory pruning"); lease.close();
            store(app).edit().putString("install.broken", "bad").commit(); request(app);
            require(Files.exists(empty), "Malformed protection allowed directory pruning");
            store(app).edit().remove("install.broken").commit(); request(app);
            require(!Files.exists(empty) && Files.exists(deep), "Depth bound/empty pruning incorrect");
        });
        test("final contextual lease release automatically reclaims old owned APK", () -> {
            Application app = app(2); Path old = apk(app, 3), newer = apk(app, 4);
            UpdatePreferences prefs = new UpdatePreferences(app); prefs.savePending(artifact(3), ADDRESS);
            UpdateCleanup.Lease first = UpdateCleanup.acquire(app, old.getParent().toFile());
            UpdateCleanup.Lease second = UpdateCleanup.acquire(app, old.getParent().toFile());
            prefs.savePending(artifact(4), ADDRESS); request(app);
            require(Files.exists(old), "Active lease failed to protect previous candidate"); first.close();
            require(Files.exists(old), "Nonfinal lease release triggered deletion"); second.close();
            awaitDeleted(old);
            require(Files.exists(newer) && prefs.rawPending(true).versionCode == 4, "Automatic reclaim damaged new pending");
        });
        test("unknown files nested directories and external resources are unchanged", () -> {
            Application app = app(2); Path directory = Files.createDirectories(UpdateCleanup.directory(app).toPath());
            Path unknown = directory.resolve("notes.part"); Files.writeString(unknown, "keep");
            Path nested = Files.createDirectory(directory.resolve("unknown"));
            Path hidden = nested.resolve("download-123.part"); Files.writeString(hidden, "nested");
            Path external = app.getCacheDir().getParentFile().toPath().resolve("source.db"); Files.writeString(external, "history");
            request(app); require(Files.readString(unknown).equals("keep") && Files.readString(hidden).equals("nested")
                    && Files.readString(external).equals("history"), "Unknown resources changed");
        });
        test("symlink files and symlink updates directory never follow targets", () -> {
            Application app = app(2); Path directory = Files.createDirectories(UpdateCleanup.directory(app).toPath());
            Path external = Files.createTempFile(temporary, "outside-", ".part"); Files.writeString(external, "outside");
            Path link = directory.resolve("download-link.part"); Files.createSymbolicLink(link, external);
            Path externalDirectory = Files.createTempDirectory(temporary, "nested-link-target-");
            Path externalPart = externalDirectory.resolve("download-external.part"); Files.writeString(externalPart, "external");
            Path directoryLink = directory.resolve("unknown-link"); Files.createSymbolicLink(directoryLink, externalDirectory);
            request(app); require(Files.isSymbolicLink(link) && Files.readString(external).equals("outside"), "File symlink followed");
            require(Files.isSymbolicLink(directoryLink) && Files.exists(externalPart), "Empty-only traversal followed directory symlink");
            Application other = app(2); Path target = Files.createTempDirectory(temporary, "outside-dir-");
            Path targetPart = target.resolve("download-123.part"); Files.writeString(targetPart, "outside directory");
            Files.createSymbolicLink(UpdateCleanup.directory(other).toPath(), target);
            request(other); require(Files.isSymbolicLink(UpdateCleanup.directory(other).toPath()) && Files.exists(targetPart), "Directory symlink followed");
        });
        test("lease acquired before queued worker protects directory and rename publication gap", () -> {
            Application app = app(2); Path directory = Files.createDirectories(UpdateCleanup.directory(app).toPath());
            UpdateCleanup.Lease lease = UpdateCleanup.acquire(directory.toFile());
            Path part = directory.resolve("download-active.part"); Files.write(part, data(3)); request(app);
            require(Files.exists(part), "Active part deleted");
            Path file = directory.resolve(artifact(3).sha256 + ".apk"); Files.move(part, file); request(app);
            require(Files.exists(file), "Rename-before-pending gap deleted");
            new UpdatePreferences(app).savePending(artifact(3), ADDRESS); lease.close(); request(app);
            require(Files.exists(file), "Durable publication protection lost after lease");
        });
        test("APK parsing is outside lock and rechecks newly acquired lease", () -> {
            Application app = app(3); Path file = apk(app, 2); legacy(app, file, 2);
            CountDownLatch parsing = new CountDownLatch(1), resume = new CountDownLatch(1), done = new CountDownLatch(1);
            app.getPackageManager().onParse = () -> { parsing.countDown(); await(resume); };
            UpdateCleanup.request(app, done::countDown); require(parsing.await(2, TimeUnit.SECONDS), "Legacy parser not entered");
            UpdateCleanup.Lease lease = UpdateCleanup.acquire(file.getParent().toFile());
            resume.countDown(); require(done.await(5, TimeUnit.SECONDS), "Cleanup blocked by parser");
            require(Files.exists(file), "Lease registered during parser was not rechecked");
            lease.close(); app.getPackageManager().onParse = null; request(app); require(!Files.exists(file), "Released legacy file not collected");
        });
        test("APK parsing rechecks newly published pending protection", () -> {
            Application app = app(3); Path file = apk(app, 2); legacy(app, file, 2);
            CountDownLatch parsing = new CountDownLatch(1), resume = new CountDownLatch(1), done = new CountDownLatch(1);
            app.getPackageManager().onParse = () -> { parsing.countDown(); await(resume); };
            UpdateCleanup.request(app, done::countDown); require(parsing.await(2, TimeUnit.SECONDS), "Parser not entered");
            new UpdatePreferences(app).savePending(artifact(2), ADDRESS); resume.countDown();
            require(done.await(5, TimeUnit.SECONDS), "Cleanup did not finish"); require(Files.exists(file), "New pending deleted after parser");
        });
        test("bounded file scan and repeated requests complete exactly once", () -> {
            Application app = app(2); Path directory = Files.createDirectories(UpdateCleanup.directory(app).toPath());
            for (int i = 0; i < 300; i++) Files.writeString(directory.resolve("download-" + i + ".part"), "part");
            UpdateCleanup.clean(app);
            try (java.util.stream.Stream<Path> files = Files.list(directory)) { require(files.count() >= 44, "Unbounded scan removed over budget"); }
            AtomicInteger count = new AtomicInteger(); CountDownLatch done = new CountDownLatch(40);
            for (int i = 0; i < 40; i++) UpdateCleanup.request(app, () -> { count.incrementAndGet(); done.countDown(); });
            require(done.await(5, TimeUnit.SECONDS) && count.get() == 40, "Duplicate requests lost or duplicated completion");
        });
        test("request from final pass schedules another batch after current completions", () -> {
            Application app = app(2); Path newer = apk(app, 4);
            UpdatePreferences prefs = new UpdatePreferences(app); prefs.savePending(artifact(4), ADDRESS);
            Path root = newer.getParent(), first = root.resolve("download-first.part"), second = root.resolve("download-second.part");
            Files.writeString(first, "part");
            Path orphan = root.resolve(artifact(3).sha256 + ".apk");
            AtomicInteger finishes = new AtomicInteger(), secondPass = new AtomicInteger(), delayedCompletion = new AtomicInteger();
            UpdateCleanup.delete = file -> {
                boolean removed = file.delete();
                try {
                    if (file.toPath().equals(first)) {
                        Files.writeString(second, "part");
                        UpdateCleanup.request(app, finishes::incrementAndGet);
                    } else if (file.toPath().equals(second)) {
                        secondPass.incrementAndGet(); prefs.own(artifact(3)); Files.write(orphan, data(3));
                        UpdateCleanup.request(app, finishes::incrementAndGet);
                    } else if (file.toPath().equals(orphan)) {
                        if (finishes.get() != 3) delayedCompletion.incrementAndGet();
                    }
                } catch (Exception error) { throw new AssertionError(error); }
                return removed;
            };
            UpdateCleanup.request(app, finishes::incrementAndGet);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((secondPass.get() == 0 || Files.exists(orphan) || finishes.get() != 3) && System.nanoTime() < deadline) Thread.sleep(10);
            require(secondPass.get() == 1 && finishes.get() == 3 && !Files.exists(orphan) && delayedCompletion.get() == 0,
                    "Final-pass request was lost or delayed current completion batch");
            require(Files.exists(newer), "Follow-up pass deleted pending candidate");
        });
        test("receiver ignores other actions and each duplicate broadcast finishes once while leased", () -> {
            Application app = app(2); UpdateCleanupReceiver receiver = new UpdateCleanupReceiver();
            int before = BroadcastReceiver.RESULTS.size(); receiver.onReceive(app, null); receiver.onReceive(app, new Intent("other"));
            require(BroadcastReceiver.RESULTS.size() == before, "Wrong action began async work");
            UpdateCleanup.Lease lease = UpdateCleanup.acquire(UpdateCleanup.directory(app));
            for (int i = 0; i < 20; i++) receiver.onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
            request(app);
            for (int i = before; i < BroadcastReceiver.RESULTS.size(); i++) require(BroadcastReceiver.RESULTS.get(i).finishes.get() == 1, "Receiver finish count incorrect");
            lease.close();
        });
        test("receiver time budget expiry preserves file and finishes once", () -> {
            Application app = app(3); Path file = apk(app, 2); legacy(app, file, 2);
            app.getPackageManager().onParse = () -> {
                try { Thread.sleep(3200); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            };
            int index = BroadcastReceiver.RESULTS.size();
            new UpdateCleanupReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (BroadcastReceiver.RESULTS.get(index).finishes.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            require(BroadcastReceiver.RESULTS.get(index).finishes.get() == 1 && Files.exists(file), "Budget expiry deleted file or missed finish");
        });
        test("receiver synchronous request exception and async delete exception finish", () -> {
            Application app = app(2); UpdateCleanupReceiver receiver = new UpdateCleanupReceiver();
            Application broken = new Application(app.getCacheDir().getParentFile(), 2) {
                @Override public Context getApplicationContext() { throw new IllegalStateException("Injected request failure"); }
            };
            int before = BroadcastReceiver.RESULTS.size(); receiver.onReceive(broken, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
            require(BroadcastReceiver.RESULTS.get(before).finishes.get() == 1, "Synchronous exception failed to finish");
            Path directory = Files.createDirectories(UpdateCleanup.directory(app).toPath()); Files.writeString(directory.resolve("download-1.part"), "part");
            UpdateCleanup.delete = file -> { throw new IllegalStateException("Injected delete failure"); };
            receiver.onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED)); request(app);
            require(BroadcastReceiver.RESULTS.get(before + 1).finishes.get() == 1, "Async exception failed to finish");
        });
        System.out.println("UpdateCleanupTest: " + cases + " cases, " + (cases - failures) + " passed, " + failures + " failed");
        if (failures != 0) System.exit(1);
    }
    private static void awaitDeleted(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(10);
        require(!Files.exists(file), "Final lease release did not automatically request cleanup");
    }
    private static void await(CountDownLatch latch) {
        try { require(latch.await(4, TimeUnit.SECONDS), "Test parser gate timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
