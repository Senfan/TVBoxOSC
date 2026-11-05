package com.github.tvbox.osc.update;

import java.net.URI;
import java.net.URISyntaxException;

public final class UpdateUrlPolicy {
    private UpdateUrlPolicy() {}

    public static String validate(String value, boolean debug) {
        String address = value == null ? "" : value.trim();
        try {
            URI uri = new URI(address);
            String host = uri.getHost();
            if (host == null || host.isEmpty() || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getPort() < -1 || uri.getPort() > 65535
                    || uri.getPort() == 0 || address.length() > 4096) {
                throw new URISyntaxException(address, "Invalid update URL");
            }
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean local = "10.0.2.2".equals(host) || "127.0.0.1".equals(host)
                    || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host);
            if (!https && !(debug && local && "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new URISyntaxException(address, "HTTPS required");
            }
            return address;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("请输入完整 HTTPS 更新地址；调试版仅允许本机测试 HTTP 地址");
        }
    }
}
