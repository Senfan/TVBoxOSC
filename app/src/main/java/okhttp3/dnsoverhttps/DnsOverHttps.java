/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.dnsoverhttps;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

import okhttp3.CacheControl;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.ByteString;

/**
 * App-compatible DNS over HTTPS adapter using only public OkHttp/Okio APIs.
 * A null URL disables DoH; failed DoH lookups retain the system-DNS fallback.
 * Unlike the official immutable resolver, this adapter keeps the app's live
 * setUrl switch and the legacy A-plus-AAAA raw forwarding interface.
 * HTTP Call/Callback/Request names are qualified because OkHttp 5.5's Dns
 * interface now has nested types with those same names.
 */
public class DnsOverHttps implements Dns {
    public static final MediaType DNS_MESSAGE = MediaType.get("application/dns-message");
    public static final int MAX_RESPONSE_SIZE = 64 * 1024;
    private static final Logger LOGGER = Logger.getLogger(DnsOverHttps.class.getName());
    private final OkHttpClient client;
    private volatile HttpUrl url;
    private final boolean includeIPv6;
    private final boolean post;
    private final boolean resolvePrivateAddresses;
    private final boolean resolvePublicAddresses;

    DnsOverHttps(Builder builder) {
        if (builder.client == null) {
            throw new NullPointerException("client not set");
        }
        this.url = builder.url;
        this.includeIPv6 = builder.includeIPv6;
        this.post = builder.post;
        this.resolvePrivateAddresses = builder.resolvePrivateAddresses;
        this.resolvePublicAddresses = builder.resolvePublicAddresses;
        this.client = builder.client.newBuilder().dns(buildBootstrapClient(builder)).build();
    }

    public void setUrl(@Nullable HttpUrl newUrl) {
        this.url = newUrl;
    }

    private static Dns buildBootstrapClient(Builder builder) {
        List<InetAddress> hosts = builder.bootstrapDnsHosts;
        if (hosts == null) {
            return builder.systemDns;
        }
        if (builder.url == null) {
            throw new IllegalArgumentException("bootstrapDnsHosts requires a URL");
        }
        final String bootstrapHost = builder.url.host();
        final BootstrapDns bootstrapDns = new BootstrapDns(bootstrapHost, new ArrayList<>(hosts));
        final Dns systemDns = builder.systemDns;
        // Pins belong to the original hostname only. A settings change must not
        // send the new provider's HTTPS request to the old provider's address.
        return hostname -> bootstrapHost.equals(hostname)
                ? bootstrapDns.lookup(hostname) : systemDns.lookup(hostname);
    }

    public HttpUrl url() {
        return url;
    }

    public boolean post() {
        return post;
    }

    public boolean includeIPv6() {
        return includeIPv6;
    }

    public OkHttpClient client() {
        return client;
    }

    public boolean resolvePrivateAddresses() {
        return resolvePrivateAddresses;
    }

    public boolean resolvePublicAddresses() {
        return resolvePublicAddresses;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        // Snapshot once so a concurrent settings change cannot split A/AAAA
        // requests between providers or turn a request URL into null.
        HttpUrl endpoint = url;
        if (endpoint == null || isAddressLiteral(hostname)) {
            // Address literals (including loopback/LAN/IPv6) are not DNS names.
            // Do not let the PSL policy reject them or send them to a DoH server.
            return Dns.SYSTEM.lookup(hostname);
        }
        if (!resolvePrivateAddresses || !resolvePublicAddresses) {
            boolean privateHost = isPrivateHost(hostname);
            if (privateHost && !resolvePrivateAddresses) {
                throw new UnknownHostException("private hosts not resolved");
            }
            if (!privateHost && !resolvePublicAddresses) {
                throw new UnknownHostException("public hosts not resolved");
            }
        }
        return lookupHttps(hostname, endpoint);
    }

    public byte[] lookupHttpsForwardSync(String hostname) throws Throwable {
        HttpUrl endpoint = url;
        if (endpoint == null) {
            throw new IOException("DNS over HTTPS is disabled");
        }
        // Preserve the existing local proxy's concatenated A and AAAA wire data,
        // independently of includeIPv6 (which applies only to lookup()).
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(executeRequestSync(hostname, DnsRecordCodec.TYPE_A, endpoint));
        output.write(executeRequestSync(hostname, DnsRecordCodec.TYPE_AAAA, endpoint));
        return output.toByteArray();
    }

    private List<InetAddress> lookupHttps(String hostname, HttpUrl endpoint) throws UnknownHostException {
        List<okhttp3.Call> networkRequests = new ArrayList<>(2);
        List<InetAddress> results = new ArrayList<>(5);
        buildRequest(hostname, networkRequests, results, DnsRecordCodec.TYPE_A, endpoint);
        if (includeIPv6) {
            buildRequest(hostname, networkRequests, results, DnsRecordCodec.TYPE_AAAA, endpoint);
        }
        executeRequests(hostname, networkRequests, results);
        if (!results.isEmpty()) {
            return results;
        }
        return Dns.SYSTEM.lookup(hostname);
    }

    private void buildRequest(String hostname, List<okhttp3.Call> networkRequests, List<InetAddress> results,
                              int type, HttpUrl endpoint) {
        okhttp3.Request request = buildRequest(hostname, type, endpoint);
        Response response = getCacheOnlyResponse(request);
        if (response != null) {
            processResponse(response, hostname, results);
        } else {
            networkRequests.add(client.newCall(request));
        }
    }

    private byte[] executeRequestSync(String hostname, int type, HttpUrl endpoint) throws IOException {
        okhttp3.Request request = buildRequest(hostname, type, endpoint);
        Response response = getCacheOnlyResponse(request);
        if (response == null) {
            response = client.newCall(request).execute();
        }
        try (Response closeable = response) {
            return readResponseBytes(closeable).toByteArray();
        }
    }

    private void executeRequests(final String hostname, List<okhttp3.Call> requests,
                                 final List<InetAddress> responses) throws UnknownHostException {
        final CountDownLatch latch = new CountDownLatch(requests.size());
        for (okhttp3.Call call : requests) {
            call.enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, IOException e) {
                    latch.countDown();
                }

                @Override
                public void onResponse(okhttp3.Call call, Response response) {
                    try {
                        processResponse(response, hostname, responses);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            for (okhttp3.Call call : requests) {
                call.cancel();
            }
            Thread.currentThread().interrupt();
            UnknownHostException failure = new UnknownHostException("DNS lookup interrupted: " + hostname);
            failure.initCause(e);
            throw failure;
        }
    }

    private void processResponse(Response response, String hostname, List<InetAddress> results) {
        try (Response closeable = response) {
            List<InetAddress> addresses = DnsRecordCodec.decodeAnswers(hostname, readResponseBytes(closeable));
            synchronized (results) {
                results.addAll(addresses);
            }
        } catch (Exception ignored) {
            // Keep the app's system fallback when no DoH answers are usable.
        }
    }

    private @Nullable Response getCacheOnlyResponse(okhttp3.Request request) {
        if (!post && client.cache() != null) {
            try {
                okhttp3.Request cacheRequest = request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build();
                Response cacheResponse = client.newCall(cacheRequest).execute();
                if (cacheResponse.code() != 504) {
                    return cacheResponse;
                }
                cacheResponse.close();
            } catch (IOException ignored) {
                // Fall back to the network and repopulate the cache.
            }
        }
        return null;
    }

    private ByteString readResponseBytes(Response response) throws IOException {
        if (response.cacheResponse() == null && response.protocol() != Protocol.HTTP_2) {
            LOGGER.fine("DNS over HTTPS response protocol: " + response.protocol());
        }
        if (!response.isSuccessful()) {
            throw new IOException("response: " + response.code() + " " + response.message());
        }
        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException("empty DNS response body");
        }
        // Check both declared and chunked/unknown lengths without buffering an
        // unbounded body from a misbehaving DNS endpoint.
        if (body.contentLength() > MAX_RESPONSE_SIZE || body.source().request(MAX_RESPONSE_SIZE + 1L)) {
            throw new IOException("response size exceeds limit (" + MAX_RESPONSE_SIZE + " bytes)");
        }
        return body.source().readByteString();
    }

    private okhttp3.Request buildRequest(String hostname, int type, HttpUrl endpoint) {
        okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder().header("Accept", DNS_MESSAGE.toString());
        ByteString query = DnsRecordCodec.encodeQuery(hostname, type);
        if (post) {
            requestBuilder.url(endpoint).post(RequestBody.create(DNS_MESSAGE, query));
        } else {
            String encoded = query.base64Url().replace("=", "");
            requestBuilder.url(endpoint.newBuilder().addQueryParameter("dns", encoded).build());
        }
        return requestBuilder.build();
    }

    private static boolean isAddressLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+");
    }

    static boolean isPrivateHost(String host) {
        // Public API with the same public-suffix semantics, including IDNs.
        return new HttpUrl.Builder().scheme("https").host(host).build().topPrivateDomain() == null;
    }

    public static final class Builder {
        @Nullable OkHttpClient client = null;
        @Nullable HttpUrl url = null;
        boolean includeIPv6 = true;
        boolean post = false;
        Dns systemDns = Dns.SYSTEM;
        @Nullable List<InetAddress> bootstrapDnsHosts = null;
        boolean resolvePrivateAddresses = false;
        boolean resolvePublicAddresses = true;

        public Builder() {
        }

        public DnsOverHttps build() {
            return new DnsOverHttps(this);
        }

        public Builder client(OkHttpClient client) {
            this.client = client;
            return this;
        }

        public Builder url(@Nullable HttpUrl url) {
            this.url = url;
            return this;
        }

        public Builder includeIPv6(boolean includeIPv6) {
            this.includeIPv6 = includeIPv6;
            return this;
        }

        public Builder post(boolean post) {
            this.post = post;
            return this;
        }

        public Builder resolvePrivateAddresses(boolean resolvePrivateAddresses) {
            this.resolvePrivateAddresses = resolvePrivateAddresses;
            return this;
        }

        public Builder resolvePublicAddresses(boolean resolvePublicAddresses) {
            this.resolvePublicAddresses = resolvePublicAddresses;
            return this;
        }

        public Builder bootstrapDnsHosts(@Nullable List<InetAddress> bootstrapDnsHosts) {
            this.bootstrapDnsHosts = bootstrapDnsHosts;
            return this;
        }

        public Builder bootstrapDnsHosts(InetAddress... bootstrapDnsHosts) {
            return bootstrapDnsHosts(Arrays.asList(bootstrapDnsHosts));
        }

        public Builder systemDns(Dns systemDns) {
            this.systemDns = systemDns;
            return this;
        }
    }
}
