package com.github.tvbox.osc.util;

import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import com.github.tvbox.osc.base.App;
import com.lzy.okgo.OkGo;

public class MainJarTest {
    private static final String URL = "https://fixture.invalid/main.jar";
    static class Callback implements LoadConfigCallback {
        int success, error;
        public void success() { success++; }
        public void error(String message) { error++; }
        void assertSuccess() { if (success != 1 || error != 0) throw new AssertionError("callback success="+success+" error="+error); }
    }
    public static void main(String[] args) throws Exception {
        new App(new File(args[0]));
        File cache = new File(App.getInstance().getFilesDir(), "csp/" + MD5.string2MD5(URL) + ".jar");
        cache.getParentFile().mkdirs();
        Files.write(cache.toPath(), "good-old".getBytes(StandardCharsets.UTF_8));
        for (int mode = 0; mode < 3; mode++) {
            OkGo.fail = mode == 0;
            OkGo.next = new okhttp3.Response(new okhttp3.ResponseBody(mode == 1 ? "bad-plugin" : "good-new"), true);
            String url = mode == 2 ? URL + ";md5;" + MD5.string2MD5("good-old") : URL;
            Callback callback = new Callback();
            new MainJarHarness().loadJar(false, url, callback);
            callback.assertSuccess();
            if (!new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8).equals("good-old")) throw new AssertionError("failed refresh changed old plugin");
        }
        OkGo.fail = false;
        OkGo.next = new okhttp3.Response(new okhttp3.ResponseBody("good-new"), true);
        Callback mismatch = new Callback();
        new MainJarHarness().loadJar(false, URL + ";md5;" + MD5.string2MD5("neither-version"), mismatch);
        if (mismatch.error != 1 || mismatch.success != 0) throw new AssertionError("checksum must not fall back to incompatible cache");
        if (!new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8).equals("good-old")) throw new AssertionError("checksum failure changed old bytes");
        OkGo.next = new okhttp3.Response(new okhttp3.ResponseBody("good-new"), true);
        Callback fresh = new Callback();
        new MainJarHarness().loadJar(false, URL, fresh);
        fresh.assertSuccess();
        if (!new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8).equals("good-new") || cache.canWrite()) throw new AssertionError("new cache not read-only");
        boolean previous = false;
        for (File file : cache.getParentFile().listFiles()) {
            if (file.getName().contains(".previous-")) previous |= new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).equals("good-old");
            if (file.getName().startsWith("main-download-")) throw new AssertionError("download stage leaked");
        }
        if (!previous) throw new AssertionError("previous plugin not retained");
        System.out.println("PASS MainJarTest: offline, rejected plugin, declared checksum/cache, read-only refresh, single success callback, preserved previous");
    }
}
