package okhttp3.dnsoverhttps;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.urlhttp.BrotliInterceptor;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.interceptor.HttpLoggingInterceptor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLContext;

import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttp;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.ByteString;
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;

/** Real binaries, loopback HTTP and test-only Android lifecycle shims. */
public final class NetworkCompatTest {
    private static final byte[] TEXT = "hello".getBytes(StandardCharsets.UTF_8);
    // RFC 7932 uncompressed meta-block carrying "hello"; no native encoder needed.
    private static final byte[] BROTLI = ByteString.decodeHex("0b028068656c6c6f03").toByteArray();
    private static final List<OkHttpClient> CLIENTS = new CopyOnWriteArrayList<>();
    private static final List<String> METHODS = new CopyOnWriteArrayList<>();
    private static final Map<String, byte[]> STORED = new ConcurrentHashMap<>();
    private static final AtomicInteger DNS_REQUESTS = new AtomicInteger();
    private static final List<String> DNS_PATHS = new CopyOnWriteArrayList<>();
    private static final List<String> ENCODINGS = new CopyOnWriteArrayList<>();
    private static int checks;
    private static String base;
    private static HttpServer server;

    interface Checked { void run() throws Throwable; }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void fails(Class<? extends Throwable> type, Checked action, String message) throws Throwable {
        try { action.run(); } catch (Throwable e) {
            check(type.isInstance(e), message + ": " + e);
            return;
        }
        throw new AssertionError(message + " did not fail");
    }
    private static OkHttpClient track(OkHttpClient client) { CLIENTS.add(client); return client; }
    private static byte[] bytes(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (exchange.getResponseHeaders().getFirst("Content-Type") == null) exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(status, exchange.getRequestMethod().equals("HEAD") || status == 204 ? -1 : body.length);
        if (!exchange.getRequestMethod().equals("HEAD") && status != 204) exchange.getResponseBody().write(body);
        exchange.close();
    }
    private static byte[] dnsResponse(byte[] query) {
        int type = ((query[query.length - 4] & 255) << 8) | (query[query.length - 3] & 255);
        Buffer result = new Buffer();
        result.writeShort(0).writeShort(0x8180).writeShort(1).writeShort(1).writeShort(0).writeShort(0);
        result.write(query, 12, query.length - 12);
        result.writeShort(0xc00c).writeShort(type).writeShort(1).writeInt(60);
        if (type == DnsRecordCodec.TYPE_A) result.writeShort(4).write(new byte[]{1, 2, 3, 4});
        else result.writeShort(16).write(new byte[]{0x20, 1, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        return result.readByteArray();
    }
    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        METHODS.add(method + " " + path);
        byte[] input = bytes(exchange.getRequestBody());
        if (path.startsWith("/dns")) {
            DNS_REQUESTS.incrementAndGet(); DNS_PATHS.add(path);
            String queryString = exchange.getRequestURI().getRawQuery();
            byte[] query = method.equals("POST") ? input : ByteString.decodeBase64(queryString.substring(4)).toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/dns-message");
            exchange.getResponseHeaders().set("Cache-Control", "max-age=600");
            respond(exchange, 200, dnsResponse(query));
        } else if (path.equals("/redirect")) {
            exchange.getResponseHeaders().set("Location", "/ok"); respond(exchange, 302, TEXT);
        } else if (path.equals("/gzip") || path.equals("/br")) {
            ENCODINGS.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Encoding")));
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.getResponseHeaders().set("Content-Encoding", path.equals("/br") ? "br" : "gzip");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(TEXT); }
            respond(exchange, 200, path.equals("/br") ? BROTLI : out.toByteArray());
        } else if (path.equals("/auth") && !"Basic dXNlcjpwYXNz".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"test\""); respond(exchange, 401, TEXT);
        } else if (method.equals("PROPFIND") || method.equals("PROPPATCH")) {
            String xml = "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response>"
                    + "<d:href>/dav/file.txt</d:href><d:propstat><d:prop><d:displayname>file.txt</d:displayname>"
                    + "<d:getcontentlength>5</d:getcontentlength><d:getcontenttype>text/plain</d:getcontenttype>"
                    + "<d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status>"
                    + "</d:propstat></d:response></d:multistatus>";
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            respond(exchange, 207, xml.getBytes(StandardCharsets.UTF_8));
        } else if (method.equals("PUT")) {
            STORED.put(path, input); respond(exchange, 201, TEXT);
        } else if (Arrays.asList("DELETE", "MKCOL", "MOVE", "COPY", "UNLOCK").contains(method)) {
            if (method.equals("MOVE") || method.equals("COPY")) {
                check(exchange.getRequestHeaders().getFirst("Destination") != null, "WebDAV destination header");
            }
            respond(exchange, 204, new byte[0]);
        } else if (path.equals("/missing")) {
            respond(exchange, 404, TEXT);
        } else if (path.equals("/echo")) {
            String result = method + "|" + exchange.getRequestURI().getRawQuery() + "|"
                    + exchange.getRequestHeaders().getFirst("Content-Type") + "|"
                    + new String(input, StandardCharsets.UTF_8);
            respond(exchange, 200, result.getBytes(StandardCharsets.UTF_8));
        } else {
            respond(exchange, 200, STORED.containsKey(path) ? STORED.get(path) : TEXT);
        }
    }

    private static void testDefaults() throws Throwable {
        OkGoHelper.init();
        OkHttpClient defaults = track(new OkHttpClient());
        OkHttpClient exo = ExoMediaSourceHelper.getInstance(App.getInstance()).client;
        for (OkHttpClient client : Arrays.asList(OkGoHelper.getDefaultClient(), OkGoHelper.getNoRedirectClient(),
                OkGoHelper.dnsOverHttps.client(), exo)) {
            track(client);
            check(client.hostnameVerifier() == defaults.hostnameVerifier(), "system hostname verifier for each app client");
            check(client.x509TrustManager().getClass() == defaults.x509TrustManager().getClass(), "system trust manager for each app client");
            check(client.x509TrustManager().getAcceptedIssuers().length > 0, "system trust anchors, not trust-all");
            check(client.connectionSpecs().equals(defaults.connectionSpecs()), "default TLS versions and ciphers");
            check(!client.hostnameVerifier().verify("invalid.example", SSLContext.getDefault().createSSLEngine().getSession()), "invalid peer hostname rejected");
        }
        check(OkGo.getInstance().getOkHttpClient() == OkGoHelper.getDefaultClient(), "OkGo unsafe default is replaced, not inherited");
        check(OkGoHelper.getDefaultClient().followRedirects(), "normal redirects enabled");
        check(!OkGoHelper.getNoRedirectClient().followRedirects(), "no-redirect client retained");
        check(OkGoHelper.dnsOverHttps.url() == null, "DoH off by default");
        check(com.lzy.okgo.model.HttpHeaders.getUserAgent().equals("okhttp/" + OkHttp.VERSION), "public version API user agent");
        fails(UnsupportedOperationException.class, () -> OkGoHelper.getConnectionSpec().clear(), "connection specs immutable");
        System.out.println("PASS client initialization, DoH switch defaults and system TLS/hostname policy");
    }

    private static void testOkGo() throws Throwable {
        HttpLoggingInterceptor logger = new HttpLoggingInterceptor("NetworkCompat");
        logger.setPrintLevel(HttpLoggingInterceptor.Level.BODY);
        logger.setColorLevel(Level.INFO);
        java.util.logging.Logger.getLogger("NetworkCompat").setFilter(record -> false); // BODY logic still runs.
        OkHttpClient client = track(OkGoHelper.getDefaultClient().newBuilder().addInterceptor(logger).build());
        OkGo.getInstance().setOkHttpClient(client);
        try (Response r = OkGo.<String>get(base + "/echo").params("q", "a b").headers("X-Test", "ok").execute()) {
            check(r.body().string().contains("q=a+b"), "OkGo GET URL parameters");
        }
        try (Response r = OkGo.<String>post(base + "/echo").params("q", "a b").execute()) {
            check(r.body().string().contains("q=a+b"), "OkGo form body");
        }
        try (Response r = OkGo.<String>post(base + "/echo").upJson("{\"a\":1}").execute()) {
            check(r.body().string().contains("{\"a\":1}"), "OkGo JSON body");
        }
        File file = Files.createTempFile("network-upload-", ".txt").toFile();
        try {
            Files.write(file.toPath(), TEXT);
            try (Response r = OkGo.<String>post(base + "/echo").params("upload", file).params("name", "value").execute()) {
                String body = r.body().string();
                check(body.contains("multipart/form-data") && body.contains("hello") && body.contains("value"), "OkGo multipart file and text upload");
            }
            try (Response r = OkGo.<String>put(base + "/dav/upload").upFile(file).execute()) { check(r.code() == 201, "OkGo file PUT"); }
        } finally { Files.deleteIfExists(file.toPath()); }
        try (Response r = OkGo.<String>patch(base + "/echo").upBytes(TEXT).execute()) { check(r.body().string().endsWith("hello"), "OkGo byte PATCH"); }
        for (com.lzy.okgo.request.base.Request<?, ?> request : Arrays.asList(OkGo.head(base + "/ok"),
                OkGo.delete(base + "/ok"), OkGo.options(base + "/ok"), OkGo.trace(base + "/ok"))) {
            try (Response r = request.execute()) { check(r.isSuccessful(), "OkGo " + request.getMethod()); }
        }
        try (Response r = OkGo.get(base + "/redirect").execute()) { check(r.code() == 200, "OkGo redirect"); }
        try (Response r = OkGo.get(base + "/redirect").client(OkGoHelper.getNoRedirectClient()).execute()) { check(r.code() == 302, "OkGo redirect disabled"); }
        try (Response r = OkGo.get(base + "/gzip").execute()) { check(r.body().string().equals("hello"), "OkGo logging and transparent gzip"); }
        CountDownLatch done = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        OkGo.get(base + "/ok").getRawCall().enqueue(new Callback() {
            public void onFailure(Call call, IOException e) { failures.add(e); done.countDown(); }
            public void onResponse(Call call, Response response) {
                try (Response r = response) { if (!r.body().string().equals("hello")) failures.add(new AssertionError("callback body")); }
                catch (Exception e) { failures.add(e); } finally { done.countDown(); }
            }
        });
        check(done.await(5, TimeUnit.SECONDS) && failures.isEmpty(), "OkGo generated asynchronous raw call");
        System.out.println("PASS real OkGo GET/form/JSON/multipart/file/bytes/HEAD/DELETE/OPTIONS/TRACE/redirect/gzip/callback");
    }

    private static void testSardine() throws Throwable {
        OkHttpSardine sardine = new OkHttpSardine(OkGoHelper.getDefaultClient());
        List<DavResource> resources = sardine.list(base + "/dav/");
        check(resources.size() == 1 && resources.get(0).getName().equals("file.txt"), "Sardine PROPFIND XML and StatusLine linkage");
        check(resources.get(0).getContentLength() == 5, "Sardine properties");
        sardine.put(base + "/dav/bytes", TEXT);
        try (InputStream input = sardine.get(base + "/dav/bytes")) { check(Arrays.equals(bytes(input), TEXT), "Sardine PUT/GET streaming"); }
        check(sardine.exists(base + "/dav/bytes"), "Sardine HEAD exists");
        check(!sardine.exists(base + "/missing"), "Sardine HEAD missing");
        sardine.createDirectory(base + "/dav/dir");
        sardine.copy(base + "/dav/bytes", base + "/dav/copy");
        sardine.move(base + "/dav/copy", base + "/dav/move");
        sardine.delete(base + "/dav/move");
        sardine.patch(base + "/dav/bytes", Collections.singletonMap(new javax.xml.namespace.QName("DAV:", "displayname", "d"), "new"));
        sardine.setCredentials("user", "pass");
        try (InputStream input = sardine.get(base + "/auth")) { check(Arrays.equals(bytes(input), TEXT), "Sardine challenge authentication"); }
        fails(IOException.class, () -> sardine.get(base + "/missing"), "Sardine non-success response");
        for (String method : Arrays.asList("MKCOL", "COPY", "MOVE", "DELETE", "PROPPATCH")) {
            check(METHODS.stream().anyMatch(value -> value.startsWith(method + " /dav/")), "Sardine method " + method);
        }
        System.out.println("PASS real Sardine WebDAV XML/GET/PUT/HEAD/MKCOL/COPY/MOVE/DELETE/PROPPATCH/auth/errors");
    }

    private static void testBrotli() throws Throwable {
        OkHttpClient client = track(new OkHttpClient.Builder().addInterceptor(new BrotliInterceptor()).build());
        for (String path : Arrays.asList("/br", "/gzip")) {
            try (Response r = client.newCall(new Request.Builder().url(base + path).build()).execute()) {
                check(r.header("Content-Encoding") == null && r.header("Content-Length") == null, "decoded headers cleared");
                check(r.body().contentLength() == -1, "decoded size unknown");
                check(r.body().string().equals("hello"), "decoded " + path);
            }
        }
        check(ENCODINGS.contains("br,gzip"), "Brotli/gzip negotiated");
        try (Response r = client.newCall(new Request.Builder().url(base + "/br").header("Accept-Encoding", "br").build()).execute()) {
            check(Arrays.equals(r.body().bytes(), BROTLI) && "br".equals(r.header("Content-Encoding")), "explicit encoding ownership retained");
        }
        try (Response r = client.newCall(new Request.Builder().url(base + "/br").header("Range", "bytes=0-3").build()).execute()) {
            check(Arrays.equals(r.body().bytes(), BROTLI), "Range representation not decoded");
            check(ENCODINGS.get(ENCODINGS.size()-1).equals("null"), "Range does not negotiate encoding");
        }
        try (Response r = client.newCall(new Request.Builder().url(base + "/br").head().build()).execute()) {
            check(r.body().bytes().length == 0, "HEAD metadata not treated as compressed body");
        }
        System.out.println("PASS Brotli/gzip bytes, headers, explicit encoding, range and HEAD handling");
    }

    private static DnsOverHttps resolver(HttpUrl endpoint, boolean ipv6, boolean post) {
        DnsOverHttps resolver = new DnsOverHttps.Builder().client(track(new OkHttpClient())).url(endpoint)
                .includeIPv6(ipv6).post(post).build();
        track(resolver.client()); return resolver;
    }
    private static void testDoh() throws Throwable {
        HttpUrl endpoint = HttpUrl.get(base + "/dns");
        DnsOverHttps doh = resolver(endpoint, true, false);
        List<InetAddress> result = doh.lookup("example.com");
        check(result.size() == 2 && result.stream().anyMatch(a -> a.getHostAddress().equals("1.2.3.4")), "DoH A and AAAA");
        check(resolver(endpoint, false, true).lookup("example.com").size() == 1, "DoH POST and IPv4-only switch");
        byte[] expectedA = dnsResponse(DnsRecordCodec.encodeQuery("example.com", DnsRecordCodec.TYPE_A).toByteArray());
        byte[] expectedAAAA = dnsResponse(DnsRecordCodec.encodeQuery("example.com", DnsRecordCodec.TYPE_AAAA).toByteArray());
        ByteArrayOutputStream expected = new ByteArrayOutputStream(); expected.write(expectedA); expected.write(expectedAAAA);
        check(Arrays.equals(resolver(endpoint, false, false).lookupHttpsForwardSync("example.com"), expected.toByteArray()), "forward API retains exact A-plus-AAAA bytes");
        doh.setUrl(null);
        check(doh.lookup("localhost").stream().anyMatch(InetAddress::isLoopbackAddress), "disabled DoH keeps local system DNS");
        fails(IOException.class, () -> doh.lookupHttpsForwardSync("example.com"), "disabled raw forwarding");
        doh.setUrl(HttpUrl.get(base + "/dns-new"));
        check(doh.lookup("example.com").size() == 2 && DNS_PATHS.contains("/dns-new"), "live endpoint switch");
        check(DnsOverHttps.isPrivateHost("localhost"), "single-label private hostname detection");
        check(!DnsOverHttps.isPrivateHost("printer.local"), "retain PSL wildcard behavior for unknown suffixes");
        check(!DnsOverHttps.isPrivateHost("example.co.uk"), "public suffix API preserves public host classification");
        fails(UnknownHostException.class, () -> doh.lookup("localhost"), "private host policy retained while DoH enabled");
        int literalRequests = DNS_REQUESTS.get();
        check(doh.lookup("127.0.0.1").get(0).isLoopbackAddress(), "enabled DoH keeps IPv4 loopback literal");
        check(doh.lookup("::1").get(0).isLoopbackAddress(), "enabled DoH keeps IPv6 loopback literal");
        check(doh.lookup("192.168.1.2").get(0).isSiteLocalAddress(), "enabled DoH keeps LAN address literal");
        check(DNS_REQUESTS.get() == literalRequests, "address literals never sent to DoH");
        DnsOverHttps privateDoh = new DnsOverHttps.Builder().client(track(new OkHttpClient())).url(endpoint)
                .resolvePrivateAddresses(true).resolvePublicAddresses(false).build(); track(privateDoh.client());
        check(privateDoh.lookup("localhost").size() == 2, "opt-in private names");
        fails(UnknownHostException.class, () -> privateDoh.lookup("example.com"), "public host filter");

        InetAddress loopback = InetAddress.getByAddress(new byte[]{127,0,0,1});
        List<InetAddress> pins = new ArrayList<>(Collections.singletonList(loopback));
        AtomicInteger systemCalls = new AtomicInteger();
        DnsOverHttps pinned = new DnsOverHttps.Builder().client(track(new OkHttpClient()))
                .url(endpoint.newBuilder().host("bootstrap.test").build()).bootstrapDnsHosts(pins)
                .systemDns(host -> { systemCalls.incrementAndGet(); return Collections.singletonList(loopback); }).build();
        track(pinned.client()); pins.clear();
        check(pinned.lookup("example.com").size() == 2 && systemCalls.get() == 0, "bootstrap pins copied and used without system lookup");
        pinned.setUrl(endpoint.newBuilder().host("other.test").build());
        check(pinned.lookup("example.com").size() == 2 && systemCalls.get() > 0, "new endpoint never reuses old hostname pins");
        fails(UnknownHostException.class, () -> new BootstrapDns("only.test", Collections.singletonList(loopback)).lookup("other.test"), "bootstrap helper hostname isolation");

        Cache cache = new Cache(new File(System.getProperty("test.cache"), "test-doh"), 1024 * 1024);
        OkHttpClient cached = track(new OkHttpClient.Builder().cache(cache).build());
        DnsOverHttps cacheDoh = new DnsOverHttps.Builder().client(cached).url(endpoint).includeIPv6(false).build(); track(cacheDoh.client());
        int before = DNS_REQUESTS.get();
        cacheDoh.lookup("cache.example.com"); cacheDoh.lookup("cache.example.com");
        check(DNS_REQUESTS.get() - before == 1, "DoH cache miss, write and cache-only hit");

        for (boolean knownLength : Arrays.asList(false, true)) {
            OkHttpClient oversized = track(new OkHttpClient.Builder().addInterceptor(chain -> {
                Buffer buffer = new Buffer().write(new byte[DnsOverHttps.MAX_RESPONSE_SIZE + 1]);
                ResponseBody body = ResponseBody.create(DnsOverHttps.DNS_MESSAGE, knownLength ? buffer.size() : -1, buffer);
                return new Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_2).code(200).message("OK").body(body).build();
            }).build());
            DnsOverHttps bounded = new DnsOverHttps.Builder().client(oversized).url(endpoint).resolvePrivateAddresses(true).includeIPv6(false).build(); track(bounded.client());
            fails(IOException.class, () -> bounded.lookupHttpsForwardSync("example.com"), "bounded " + (knownLength ? "declared" : "chunked") + " DoH response");
            check(bounded.lookup("localhost").stream().anyMatch(InetAddress::isLoopbackAddress), "failed DoH retains system fallback");
        }
        fails(java.io.EOFException.class, () -> DnsRecordCodec.decodeAnswers("example.com", ByteString.of(Arrays.copyOf(expectedA, expectedA.length - 1))), "truncated DNS address rejected");
        fails(IllegalArgumentException.class, () -> DnsRecordCodec.encodeQuery("bad..name", 1), "empty DNS label rejected");
        System.out.println("PASS DoH GET/POST/A/AAAA/switch/forward/cache/bootstrap/private policy/fallback/bounds/codec");
    }

    public static void main(String[] args) throws Throwable {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", NetworkCompatTest::handle); server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            check(OkHttp.VERSION.equals("5.5.1"), "tests must use target OkHttp binary");
            testDefaults(); testOkGo(); testSardine(); testBrotli(); testDoh();
            System.out.println("PASS " + checks + " network assertions; OkHttp " + OkHttp.VERSION);
        } finally {
            server.stop(0);
            for (OkHttpClient client : CLIENTS) {
                client.dispatcher().cancelAll(); client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll();
                if (client.cache() != null) client.cache().close();
            }
        }
    }
}
