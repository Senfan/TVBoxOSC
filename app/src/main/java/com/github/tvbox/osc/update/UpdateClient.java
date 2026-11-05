package com.github.tvbox.osc.update;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class UpdateClient implements UpdateIntegrity.StopSignal {
    public interface ProgressListener { void onProgress(long bytes); }
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private final boolean debug;
    private final OkHttpClient client;
    private final OkHttpClient downloadClient;
    private volatile Call active;
    private volatile boolean cancelled;

    public UpdateClient(boolean debug) {
        this(debug, new OkHttpClient());
    }

    UpdateClient(boolean debug, OkHttpClient transport) {
        this.debug = debug;
        client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES).build();
        // Continuous progress on a slow TV connection must not hit the manifest deadline.
        downloadClient = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build();
    }

    public void cancel() {
        cancelled = true;
        Call call = active;
        if (call != null) call.cancel();
    }

    @Override public boolean stopped() { return cancelled || Thread.currentThread().isInterrupted(); }

    private Response open(String address, boolean apk) throws IOException {
        HttpUrl url = HttpUrl.parse(UpdateUrlPolicy.validate(address, debug));
        if (url == null) throw new IOException("更新地址无效");
        Set<String> visited = new HashSet<>();
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (stopped()) throw new IOException("更新已取消");
            if (!visited.add(url.toString())) throw new IOException("更新地址重定向循环");
            UpdateUrlPolicy.validate(url.toString(), debug);
            active = (apk ? downloadClient : client).newCall(
                    new Request.Builder().url(url).header("Accept-Encoding", "identity").build());
            if (stopped()) { active.cancel(); throw new IOException("更新已取消"); }
            Response response = active.execute();
            int code = response.code();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = response.header("Location");
                HttpUrl next = location == null ? null : url.resolve(location);
                response.close();
                if (next == null || (url.isHttps() && !next.isHttps())) {
                    throw new IOException("更新地址存在不安全重定向");
                }
                url = next;
            } else {
                if (!response.isSuccessful() || code == 206 || response.body() == null) {
                    response.close();
                    throw new IOException("更新服务器返回 HTTP " + code);
                }
                return response;
            }
        }
        throw new IOException("更新地址重定向次数过多");
    }

    public UpdateManifest check(String address) throws IOException {
        try (Response response = open(address, false); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ResponseBody body = response.body();
            if (body.contentLength() > MAX_MANIFEST_BYTES) throw new IOException("更新清单过大");
            try (InputStream input = body.byteStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (stopped()) throw new IOException("更新已取消");
                    if (output.size() + count > MAX_MANIFEST_BYTES) throw new IOException("更新清单过大");
                    output.write(buffer, 0, count);
                }
            }
            return UpdateManifest.parse(output.toString("UTF-8"), debug);
        }
    }

    public File download(UpdateArtifact artifact, File directory, ProgressListener progress) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新缓存目录");
        if (directory.getUsableSpace() < artifact.sizeBytes + 1024 * 1024) throw new IOException("存储空间不足");
        File part = File.createTempFile("download-", ".part", directory);
        boolean complete = false;
        try (Response response = open(artifact.apkUrl, true)) {
            ResponseBody body = response.body();
            if (body.contentLength() != -1 && body.contentLength() != artifact.sizeBytes) {
                throw new IOException("APK 下载长度与清单不符");
            }
            MessageDigest digest = UpdateIntegrity.digest();
            long total = 0;
            long lastProgress = 0;
            try (InputStream input = body.byteStream(); FileOutputStream output = new FileOutputStream(part)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (stopped()) throw new IOException("更新已取消");
                    total += count;
                    if (total > artifact.sizeBytes || total > UpdateArtifact.MAX_APK_BYTES) {
                        throw new IOException("APK 下载超出大小限制");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    long now = System.nanoTime();
                    if (now - lastProgress >= 100000000L) {
                        progress.onProgress(total);
                        lastProgress = now;
                    }
                }
                output.getFD().sync();
            }
            if (stopped()) throw new IOException("更新已取消");
            if (total != artifact.sizeBytes || !UpdateIntegrity.hex(digest.digest()).equals(artifact.sha256)) {
                throw new IOException("APK 大小或 SHA-256 校验失败");
            }
            progress.onProgress(total);
            complete = true;
            return part;
        } finally {
            if (!complete) part.delete();
        }
    }
}
