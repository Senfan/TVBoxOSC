package com.github.tvbox.osc.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/** Real UpdateClient + process-local servers. No mocked transport or permissive TLS trust manager. */
public final class UpdateNetworkTest {
    private static final int MANIFEST_LIMIT = 256 * 1024;
    private static final byte[] MANIFEST = "{\"schemaVersion\":1,\"artifacts\":[]}".getBytes(StandardCharsets.UTF_8);
    private static int cases;
    private static int failures;
    private final Path temporary;
    private final byte[] apk = new byte[128 * 1024 + 17];
    private final byte[] largeManifest = new byte[MANIFEST_LIMIT + 1];
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "update-test-server");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> encodings = new ConcurrentHashMap<>();
    private final CountDownLatch downloadStarted = new CountDownLatch(1);
    private final CountDownLatch downloadRelease = new CountDownLatch(1);
    private final CountDownLatch manifestStarted = new CountDownLatch(1);
    private final CountDownLatch manifestRelease = new CountDownLatch(1);
    private HttpServer http;
    private HttpsServer https;
    private String httpBase;
    private String httpsBase;
    private OkHttpClient trusted;

    private UpdateNetworkTest(Path temporary) {
        this.temporary = temporary;
        for (int i = 0; i < apk.length; i++) apk[i] = (byte) (i * 17 + 11);
        Arrays.fill(largeManifest, (byte) ' ');
        System.arraycopy(MANIFEST, 0, largeManifest, 0, MANIFEST.length);
    }

    private static void require(boolean condition, String message) {
        UpdateCoreTest.require(condition, message);
    }

    private static void test(String name, UpdateCoreTest.Checked operation) {
        cases++;
        try { operation.run(); }
        catch (Throwable error) {
            failures++;
            System.err.println("FAIL UpdateNetworkTest: " + name);
            error.printStackTrace(System.err);
        }
    }

    private static OkHttpClient transport(UpdateClient updater, String name) throws Exception {
        Field field = UpdateClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return (OkHttpClient) field.get(updater);
    }

    private static void timeoutPolicies() {
        test("manifest transport retains five-minute deadline and inactivity bounds", () -> {
            OkHttpClient client = transport(new UpdateClient(false), "client");
            require(client.callTimeoutMillis() == TimeUnit.MINUTES.toMillis(5), "Manifest deadline changed");
            require(client.connectTimeoutMillis() == 20000 && client.readTimeoutMillis() == 30000,
                    "Manifest connect/read bounds changed");
        });
        test("APK transport has no total deadline but retains inactivity bounds", () -> {
            OkHttpClient client = transport(new UpdateClient(false), "downloadClient");
            require(client.callTimeoutMillis() == 0, "Healthy slow APK downloads still have a total deadline");
            require(client.connectTimeoutMillis() == 20000 && client.readTimeoutMillis() == 30000,
                    "APK inactivity bounds were removed");
        });
        test("separate timeout policies preserve secure transport and do not mutate input", () -> {
            OkHttpClient input = new OkHttpClient.Builder().followRedirects(true).followSslRedirects(true)
                    .connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
                    .callTimeout(3, TimeUnit.SECONDS).build();
            UpdateClient updater = new UpdateClient(false, input);
            OkHttpClient manifest = transport(updater, "client");
            OkHttpClient apk = transport(updater, "downloadClient");
            require(manifest != apk && manifest != input && apk != input, "Timeout policies share a mutable client");
            for (OkHttpClient client : new OkHttpClient[]{manifest, apk}) {
                require(!client.followRedirects() && !client.followSslRedirects(), "Automatic redirect policy weakened");
                require(client.sslSocketFactory() == input.sslSocketFactory()
                        && client.hostnameVerifier() == input.hostnameVerifier()
                        && client.certificatePinner().equals(input.certificatePinner()), "TLS policy changed");
                require(client.connectionPool() == input.connectionPool() && client.dispatcher() == input.dispatcher(),
                        "Derived transport stopped sharing the secure pool/dispatcher");
            }
            require(input.callTimeoutMillis() == 3000 && input.connectTimeoutMillis() == 1000
                    && input.readTimeoutMillis() == 2000 && input.followRedirects() && input.followSslRedirects(),
                    "Injected transport was mutated");
        });
    }

    private int count(String path) {
        AtomicInteger value = hits.get(path);
        return value == null ? 0 : value.get();
    }

    private static IOException io(String message, UpdateCoreTest.Checked operation) throws Exception {
        IOException error = UpdateCoreTest.expect(IOException.class, operation);
        require(error.getMessage() != null && error.getMessage().contains(message),
                "Expected message containing '" + message + "', got: " + error);
        return error;
    }

    private void start(Path keyStorePath) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        http = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        http.setExecutor(workers);
        http.createContext("/", this::handle);
        http.start();
        httpBase = "http://127.0.0.1:" + http.getAddress().getPort();

        // The script generates this test-only certificate in its trap-cleaned temporary directory.
        KeyStore store = KeyStore.getInstance("PKCS12");
        char[] password = "update-test-only".toCharArray();
        try (InputStream input = Files.newInputStream(keyStorePath)) { store.load(input, password); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("test-only", store.getCertificate("update-test"));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        X509TrustManager trustManager = (X509TrustManager) trust.getTrustManagers()[0];
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trust.getTrustManagers(), null);
        // Deliberately enable both redirect settings on the input client: UpdateClient must copy
        // it and turn them off. Trust only this ephemeral certificate; keep hostname verification.
        trusted = new OkHttpClient.Builder().sslSocketFactory(clientContext.getSocketFactory(), trustManager)
                .followRedirects(true).followSslRedirects(true)
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS).build();
        https = HttpsServer.create(new InetSocketAddress(loopback, 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        https.setExecutor(workers);
        https.createContext("/", this::handle);
        https.start();
        httpsBase = "https://127.0.0.1:" + https.getAddress().getPort();
        require(http.getAddress().getAddress().equals(loopback) && https.getAddress().getAddress().equals(loopback),
                "Servers must bind exclusively to 127.0.0.1");
    }

    private static void body(HttpExchange exchange, int status, byte[] bytes, boolean chunked) throws IOException {
        exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void redirect(HttpExchange exchange, int status, String location) throws IOException {
        if (location != null) exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(status, -1);
    }

    private void blocked(HttpExchange exchange, boolean download) throws Exception {
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(download ? Arrays.copyOf(apk, 8192) : MANIFEST);
            output.flush();
            (download ? downloadStarted : manifestStarted).countDown();
            require((download ? downloadRelease : manifestRelease).await(10, TimeUnit.SECONDS),
                    "Blocked-test server was not released");
            if (download) output.write(apk, 8192, apk.length - 8192);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        String encoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        encodings.put(path, encoding == null ? "<absent>" : encoding);
        try {
            if (path.equals("/ok") || path.equals("/downgrade-target")) body(exchange, 200, MANIFEST, false);
            else if (path.equals("/created")) body(exchange, 201, MANIFEST, false);
            else if (path.startsWith("/status/")) body(exchange, Integer.parseInt(path.substring(8)), MANIFEST, false);
            else if (path.startsWith("/redirect/")) redirect(exchange, Integer.parseInt(path.substring(10)), "/ok");
            else if (path.equals("/missing-location")) redirect(exchange, 302, null);
            else if (path.equals("/bad-location")) redirect(exchange, 302, "http://[");
            else if (path.equals("/fragment")) redirect(exchange, 302, "/ok#fragment");
            else if (path.equals("/userinfo")) redirect(exchange, 302,
                    "http://user:secret@127.0.0.1:" + http.getAddress().getPort() + "/ok");
            else if (path.equals("/nonlocal")) redirect(exchange, 302, "http://192.0.2.1/a");
            else if (path.equals("/loop")) redirect(exchange, 302, "/loop");
            else if (path.equals("/loop-a")) redirect(exchange, 307, "/loop-b");
            else if (path.equals("/loop-b")) redirect(exchange, 308, "/loop-a");
            else if (path.startsWith("/chain/")) {
                String[] pieces = path.split("/");
                int limit = Integer.parseInt(pieces[2]);
                int index = Integer.parseInt(pieces[3]);
                if (index == limit) body(exchange, 200, MANIFEST, false);
                else redirect(exchange, 302, "/chain/" + limit + "/" + (index + 1));
            } else if (path.equals("/downgrade")) redirect(exchange, 302, httpBase + "/downgrade-target");
            else if (path.equals("/manifest/boundary")) body(exchange, 200, Arrays.copyOf(largeManifest, MANIFEST_LIMIT), false);
            else if (path.equals("/manifest/boundary-chunked")) body(exchange, 200, Arrays.copyOf(largeManifest, MANIFEST_LIMIT), true);
            else if (path.equals("/manifest/large")) body(exchange, 200, largeManifest, false);
            else if (path.equals("/manifest/large-chunked")) body(exchange, 200, largeManifest, true);
            else if (path.equals("/manifest/invalid")) body(exchange, 200, "not-json".getBytes(StandardCharsets.UTF_8), false);
            else if (path.equals("/apk")) body(exchange, 200, apk, false);
            else if (path.equals("/apk-chunked")) body(exchange, 200, apk, true);
            else if (path.equals("/apk-short-chunked")) body(exchange, 200, Arrays.copyOf(apk, apk.length - 1), true);
            else if (path.equals("/apk-long-chunked")) body(exchange, 200, Arrays.copyOf(apk, apk.length + 1), true);
            else if (path.equals("/apk-redirect")) redirect(exchange, 302, "/apk");
            else if (path.equals("/blocked-apk")) blocked(exchange, true);
            else if (path.equals("/blocked-manifest")) blocked(exchange, false);
            else body(exchange, 404, MANIFEST, false);
        } catch (IOException expectedDisconnect) {
            // Size-limit, hash-error, cancellation and TLS tests deliberately close connections early.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            throw new IOException("Embedded test server failed", error);
        } finally { exchange.close(); }
    }

    private UpdateArtifact artifact(String address, long size, String hash) {
        return UpdateArtifact.parse(UpdateCoreTest.artifactJson(address, size, hash), true);
    }

    private UpdateArtifact artifact(String path) throws Exception {
        return artifact(httpBase + path, apk.length, UpdateCoreTest.sha256(apk));
    }

    private Path downloadDirectory() throws IOException {
        Path directory = Files.createDirectory(temporary.resolve("download-case-" + cases));
        Files.writeString(directory.resolve("keep.txt"), "existing cache entry", StandardCharsets.UTF_8);
        return directory;
    }

    private static void cleaned(Path directory) throws IOException {
        require("existing cache entry".equals(Files.readString(directory.resolve("keep.txt"))), "Existing cache entry changed");
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            require(entries.count() == 1, "Failed download left a part or other file in " + directory);
        }
    }

    private void success(String path, UpdateClient client) throws Exception {
        Path directory = downloadDirectory();
        List<Long> progress = new ArrayList<>();
        UpdateArtifact artifact = artifact(path);
        Path result = client.download(artifact, directory.toFile(), progress::add).toPath();
        try {
            require(result.getParent().equals(directory) && result.getFileName().toString().endsWith(".part"), "Wrong returned file");
            require(Arrays.equals(apk, Files.readAllBytes(result)), "Downloaded bytes differ");
            UpdateIntegrity.verify(result.toFile(), artifact.sizeBytes, artifact.sha256, () -> false);
            require(!progress.isEmpty() && progress.get(progress.size() - 1) == apk.length, "No final progress");
            long previous = 0;
            for (long value : progress) {
                require(value >= previous && value > 0 && value <= apk.length, "Invalid/nonmonotonic progress");
                previous = value;
            }
        } finally { Files.deleteIfExists(result); }
        cleaned(directory);
    }

    private void failure(UpdateArtifact artifact, String message) throws Exception {
        Path directory = downloadDirectory();
        io(message, () -> new UpdateClient(true).download(artifact, directory.toFile(), ignored -> {}));
        cleaned(directory);
    }

    private void checks() {
        test("HTTP 200 manifest and identity encoding", () -> {
            UpdateManifest.Selection result = new UpdateClient(true).check(httpBase + "/ok")
                    .select("unused", "unused", "unused", "unused", 21, 0);
            require(!result.compatible && result.artifact == null, "Manifest not parsed");
            require("identity".equals(encodings.get("/ok")), "Transport did not request identity encoding");
        });
        test("HTTP 201 manifest", () -> new UpdateClient(true).check(httpBase + "/created"));
        for (int status : new int[]{206, 400, 401, 403, 404, 410, 429, 500, 503}) {
            test("manifest HTTP " + status, () -> io("HTTP " + status,
                    () -> new UpdateClient(true).check(httpBase + "/status/" + status)));
        }
        for (int status : new int[]{301, 302, 303, 307, 308}) {
            test("relative redirect HTTP " + status,
                    () -> new UpdateClient(true).check(httpBase + "/redirect/" + status));
        }
        for (String path : new String[]{"/missing-location", "/bad-location"}) {
            test("unsafe redirect " + path, () -> io("不安全重定向",
                    () -> new UpdateClient(true).check(httpBase + path)));
        }
        for (String path : new String[]{"/fragment", "/userinfo", "/nonlocal"}) {
            test("redirect URL policy " + path, () -> {
                int before = count("/ok");
                UpdateCoreTest.expect(IllegalArgumentException.class, () -> new UpdateClient(true).check(httpBase + path));
                require(count("/ok") == before, "Forbidden redirect followed");
            });
        }
        test("one-node redirect loop", () -> {
            int before = count("/loop");
            io("重定向循环", () -> new UpdateClient(true).check(httpBase + "/loop"));
            require(count("/loop") == before + 1, "Loop request repeated");
        });
        test("two-node redirect loop", () -> {
            int a = count("/loop-a"), b = count("/loop-b");
            io("重定向循环", () -> new UpdateClient(true).check(httpBase + "/loop-a"));
            require(count("/loop-a") == a + 1 && count("/loop-b") == b + 1, "Loop was not bounded");
        });
        test("five redirects allowed", () -> {
            new UpdateClient(true).check(httpBase + "/chain/5/0");
            for (int i = 0; i <= 5; i++) require(count("/chain/5/" + i) == 1, "Missing chain request " + i);
        });
        test("six redirects rejected before seventh request", () -> {
            io("重定向次数过多", () -> new UpdateClient(true).check(httpBase + "/chain/6/0"));
            for (int i = 0; i <= 5; i++) require(count("/chain/6/" + i) == 1, "Unexpected chain count " + i);
            require(count("/chain/6/6") == 0, "Followed redirect past maximum");
        });
        for (String path : new String[]{"/manifest/boundary", "/manifest/boundary-chunked"}) {
            test("manifest exactly 256KiB " + path, () -> new UpdateClient(true).check(httpBase + path));
        }
        for (String path : new String[]{"/manifest/large", "/manifest/large-chunked"}) {
            test("manifest over 256KiB " + path, () -> io("清单过大",
                    () -> new UpdateClient(true).check(httpBase + path)));
        }
        test("malformed network manifest", () -> UpdateCoreTest.expect(RuntimeException.class,
                () -> new UpdateClient(true).check(httpBase + "/manifest/invalid")));
        test("release denies HTTP before request", () -> {
            int before = count("/ok");
            UpdateCoreTest.expect(IllegalArgumentException.class, () -> new UpdateClient(false).check(httpBase + "/ok"));
            require(count("/ok") == before, "Release HTTP request reached server");
        });
        for (boolean debug : new boolean[]{false, true}) {
            test("default client rejects untrusted HTTPS debug=" + debug, () -> {
                int before = count("/ok");
                IOException error = UpdateCoreTest.expect(IOException.class,
                        () -> new UpdateClient(debug).check(httpsBase + "/ok"));
                Throwable cause = error;
                while (cause != null && !(cause instanceof SSLException)) cause = cause.getCause();
                require(cause instanceof SSLException, "Failure was not certificate/TLS validation: " + error);
                require(count("/ok") == before, "Untrusted TLS delivered an HTTP request");
            });
        }
        test("test-only trusted HTTPS succeeds", () -> new UpdateClient(false, trusted).check(httpsBase + "/ok"));
        test("injected client HTTPS relative redirect", () -> new UpdateClient(false, trusted).check(httpsBase + "/redirect/307"));
        test("injected client automatic redirects stay disabled", () -> {
            int before = count("/loop");
            io("重定向循环", () -> new UpdateClient(false, trusted).check(httpsBase + "/loop"));
            require(count("/loop") == before + 1, "Input client's automatic redirects bypassed manual loop checks");
            require(trusted.followRedirects() && trusted.followSslRedirects(), "Input client redirect settings were changed");
        });
        for (boolean debug : new boolean[]{false, true}) {
            test("HTTPS downgrade denied even debug=" + debug, () -> {
                int before = count("/downgrade-target");
                io("不安全重定向", () -> new UpdateClient(debug, trusted).check(httpsBase + "/downgrade"));
                require(count("/downgrade-target") == before, "HTTPS downgrade was followed");
                require(trusted.followRedirects() && trusted.followSslRedirects(), "Injected client was mutated instead of copied");
            });
        }
    }

    private void downloads() {
        test("known Content-Length download", () -> success("/apk", new UpdateClient(true)));
        test("unknown Content-Length download", () -> success("/apk-chunked", new UpdateClient(true)));
        test("redirected APK download", () -> success("/apk-redirect", new UpdateClient(true)));
        test("download identity encoding", () -> require("identity".equals(encodings.get("/apk")), "APK requested compression"));
        for (int status : new int[]{206, 404, 429, 500, 503}) {
            test("download HTTP " + status + " cleans part", () -> failure(artifact("/status/" + status), "HTTP " + status));
        }
        test("known bad hash cleans part", () -> failure(artifact(httpBase + "/apk", apk.length, "0".repeat(64)), "SHA-256"));
        test("unknown bad hash cleans part", () -> failure(artifact(httpBase + "/apk-chunked", apk.length, "0".repeat(64)), "SHA-256"));
        for (long expectedSize : new long[]{apk.length - 1L, apk.length + 1L}) {
            test("known length mismatch " + expectedSize + " cleans part", () -> failure(
                    artifact(httpBase + "/apk", expectedSize, UpdateCoreTest.sha256(apk)), "下载长度与清单不符"));
        }
        test("unknown oversized download cleans part", () -> failure(artifact("/apk-long-chunked"), "超出大小限制"));
        test("unknown truncated download cleans part", () -> failure(artifact("/apk-short-chunked"), "SHA-256"));
        test("known truncated transport cleans part", this::truncatedTransport);
        test("redirect loop cleans download part", () -> failure(artifact("/loop"), "重定向循环"));
        test("redirect limit cleans download part", () -> failure(artifact("/chain/7/0"), "重定向次数过多"));
        test("HTTPS downgrade cleans download part", () -> {
            Path directory = downloadDirectory();
            int before = count("/downgrade-target");
            io("不安全重定向", () -> new UpdateClient(true, trusted).download(
                    artifact(httpsBase + "/downgrade", apk.length, UpdateCoreTest.sha256(apk)), directory.toFile(), ignored -> {}));
            cleaned(directory);
            require(count("/downgrade-target") == before, "APK downgrade followed");
        });
        test("default client untrusted HTTPS cleans part", () -> {
            Path directory = downloadDirectory();
            UpdateCoreTest.expect(SSLException.class, () -> new UpdateClient(true).download(
                    artifact(httpsBase + "/apk", apk.length, UpdateCoreTest.sha256(apk)), directory.toFile(), ignored -> {}));
            cleaned(directory);
        });
        test("pre-cancel check sends no request", () -> {
            UpdateClient client = new UpdateClient(true);
            client.cancel();
            int before = count("/ok");
            io("取消", () -> client.check(httpBase + "/ok"));
            require(count("/ok") == before, "Cancelled check reached server");
        });
        test("pre-cancel download cleans part without request", () -> {
            UpdateClient client = new UpdateClient(true);
            client.cancel();
            Path directory = downloadDirectory();
            int before = count("/apk");
            io("取消", () -> client.download(artifact("/apk"), directory.toFile(), ignored -> {}));
            cleaned(directory);
            require(count("/apk") == before, "Cancelled download reached server");
        });
        test("cancel from progress cleans part", () -> {
            UpdateClient client = new UpdateClient(true);
            Path directory = downloadDirectory();
            int[] callbacks = {0};
            UpdateCoreTest.expect(IOException.class, () -> client.download(artifact("/apk-chunked"), directory.toFile(), bytes -> {
                callbacks[0]++;
                client.cancel();
            }));
            require(callbacks[0] == 1 && client.stopped(), "Cancellation did not stop progress");
            cleaned(directory);
        });
        test("interrupt download cleans part", () -> {
            Path directory = downloadDirectory();
            int before = count("/apk");
            Thread.currentThread().interrupt();
            try { io("取消", () -> new UpdateClient(true).download(artifact("/apk"), directory.toFile(), ignored -> {})); }
            finally { Thread.interrupted(); }
            cleaned(directory);
            require(count("/apk") == before, "Interrupted download reached server");
        });
        test("progress callback exception cleans part", () -> {
            Path directory = downloadDirectory();
            UpdateCoreTest.expect(IllegalStateException.class, () -> new UpdateClient(true).download(
                    artifact("/apk"), directory.toFile(), bytes -> { throw new IllegalStateException("test callback"); }));
            cleaned(directory);
        });
        test("non-directory download target left intact", () -> {
            Path file = temporary.resolve("not-a-directory");
            Files.writeString(file, "keep");
            io("无法创建", () -> new UpdateClient(true).download(artifact("/apk"), file.toFile(), ignored -> {}));
            require("keep".equals(Files.readString(file)), "Existing file changed");
        });
        test("cancel interrupts blocked download and cleans part", () -> {
            UpdateClient client = new UpdateClient(true);
            Path directory = downloadDirectory();
            CountDownLatch received = new CountDownLatch(1);
            Future<?> operation = workers.submit(() -> {
                try {
                    client.download(artifact("/blocked-apk"), directory.toFile(), bytes -> received.countDown());
                    throw new AssertionError("Blocked cancelled download succeeded");
                } catch (IOException expected) { /* Active Call.cancel may report Socket closed instead of localized text. */ }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            try {
                require(downloadStarted.await(3, TimeUnit.SECONDS) && received.await(3, TimeUnit.SECONDS), "Download never started");
                client.cancel();
                operation.get(3, TimeUnit.SECONDS); // Server remains blocked: cancellation must unblock client I/O.
                require(client.stopped(), "Cancel signal missing");
                cleaned(directory);
            } finally { client.cancel(); downloadRelease.countDown(); operation.cancel(true); }
        });
        test("cancel interrupts blocked manifest check", () -> {
            UpdateClient client = new UpdateClient(true);
            Future<?> operation = workers.submit(() -> {
                try {
                    client.check(httpBase + "/blocked-manifest");
                    throw new AssertionError("Blocked cancelled manifest succeeded");
                } catch (IOException expected) { /* Transport cancellation is sufficient. */ }
            });
            try {
                require(manifestStarted.await(3, TimeUnit.SECONDS), "Manifest request never started");
                client.cancel();
                operation.get(3, TimeUnit.SECONDS);
                require(client.stopped(), "Cancel signal missing");
            } finally { client.cancel(); manifestRelease.countDown(); operation.cancel(true); }
        });
    }

    private void truncatedTransport() throws Exception {
        // A scoped raw server provides an exact Content-Length followed by TCP EOF. HttpServer's
        // fixed-length stream rejects short writes before exposing this deterministic wire fixture.
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            server.setSoTimeout(5000);
            Future<?> responder = workers.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    InputStream input = socket.getInputStream();
                    int matched = 0;
                    byte[] terminator = {'\r', '\n', '\r', '\n'};
                    while (matched < terminator.length) {
                        int value = input.read();
                        if (value < 0) throw new IOException("No request headers");
                        matched = value == terminator[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
                    }
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + apk.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.write(apk, 0, 8192);
                    output.flush();
                    socket.shutdownOutput();
                } catch (IOException error) { throw new RuntimeException(error); }
            });
            Path directory = downloadDirectory();
            try {
                UpdateCoreTest.expect(IOException.class, () -> new UpdateClient(true).download(
                        artifact("http://127.0.0.1:" + server.getLocalPort() + "/truncated", apk.length, UpdateCoreTest.sha256(apk)),
                        directory.toFile(), ignored -> {}));
                responder.get(5, TimeUnit.SECONDS);
                cleaned(directory);
            } finally { responder.cancel(true); }
        }
    }

    private void close() {
        downloadRelease.countDown();
        manifestRelease.countDown();
        if (https != null) https.stop(0);
        if (http != null) http.stop(0);
        if (trusted != null) {
            trusted.connectionPool().evictAll();
            trusted.dispatcher().executorService().shutdownNow();
        }
        workers.shutdownNow();
        try { require(workers.awaitTermination(5, TimeUnit.SECONDS), "Embedded server workers did not terminate"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--timeout-policies")) {
            timeoutPolicies();
            System.out.println("UpdateNetworkTest timeout policies: " + cases + " cases, " + (cases - failures)
                    + " passed, " + failures + " failed");
            if (failures != 0) System.exit(1);
            return;
        }
        require(args.length == 2, "Pass temporary work directory and ephemeral PKCS12 certificate");
        UpdateNetworkTest suite = new UpdateNetworkTest(Files.createDirectories(Path.of(args[0])));
        try {
            suite.start(Path.of(args[1]));
            timeoutPolicies();
            suite.checks();
            suite.downloads();
        } finally { suite.close(); }
        System.out.println("UpdateNetworkTest: " + cases + " cases, " + (cases - failures) + " passed, " + failures + " failed");
        if (failures != 0) System.exit(1);
    }
}
