package com.github.tvbox.osc.util;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.server.ControlManager;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/** The only root shared by clan URLs, the LAN file server, JS and SAF configuration imports. */
public final class WorkspaceFiles {
    private WorkspaceFiles() { }

    public static File root() throws IOException {
        return WorkspaceIO.directory(new File(App.getInstance().getFilesDir(), "config-workspace"));
    }

    // Deliberately not below root(): backup Hawk material must never be served over LAN.
    public static File backups() throws IOException {
        return WorkspaceIO.directory(new File(App.getInstance().getFilesDir(), "private-backups"));
    }

    public static File newStage() throws IOException {
        return WorkspaceIO.newStage(new File(App.getInstance().getFilesDir(), "workspace-staging"));
    }

    public static File resolve(String path) throws IOException { return WorkspaceIO.resolve(root(), path); }

    public static String clan(File file) throws IOException {
        return "clan://localhost/" + encodePath(WorkspaceIO.relative(root(), file));
    }

    public static String encodePath(String path) throws IOException {
        try { return new URI(null, null, path, null).toASCIIString(); }
        catch (URISyntaxException e) { throw new IOException("非法文件地址", e); }
    }

    /** Absolute app-private paths and historic file:///TVBox/... aliases map to the same root. */
    public static String toHttp(String value) throws IOException {
        if (value.startsWith("clan://localhost/")) {
            String path = decodePath(value.substring("clan://localhost/".length()));
            resolve(path);
            return ControlManager.get().getAddress(true) + "file/" + encodePath(path);
        }
        if (value.startsWith("file:/")) {
            String path;
            try {
                URI fileUri = new URI(value);
                path = fileUri.getPath();
                // Historic file://TVBox/config.json used TVBox as a folder, not a host.
                if (fileUri.getRawAuthority() != null && !fileUri.getRawAuthority().isEmpty()) {
                    path = fileUri.getAuthority() + path;
                }
                if (path == null) throw new IOException("非法文件地址");
            } catch (URISyntaxException e) { throw new IOException("非法文件地址", e); }
            String privatePrefix = root().getPath() + "/";
            if (path.startsWith(privatePrefix)) path = path.substring(privatePrefix.length());
            else while (path.startsWith("/")) path = path.substring(1);
            resolve(path);
            return ControlManager.get().getAddress(true) + "file/" + encodePath(path);
        }
        if (value.startsWith("clan://")) {
            try {
                URI uri = new URI(value);
                if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawPath() == null) {
                    throw new IOException("非法clan地址");
                }
                return "http://" + uri.getRawAuthority() + "/file" + uri.getRawPath();
            } catch (URISyntaxException e) { throw new IOException("非法clan地址", e); }
        }
        return value;
    }

    private static String decodePath(String path) throws IOException {
        try { return new URI("file:///" + path).getPath().substring(1); }
        catch (URISyntaxException e) { throw new IOException("非法文件地址", e); }
    }

    /** Returns null for unrelated HTTP services; never rewrites arbitrary loopback URLs. */
    public static File fromLocalUrl(String value) throws IOException {
        String address = toHttp(value);
        try {
            URI uri = new URI(address);
            URI local = new URI(ControlManager.get().getAddress(true));
            boolean host = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
            if (!"http".equals(uri.getScheme()) || !host || uri.getPort() != local.getPort()
                    || uri.getRawUserInfo() != null || uri.getPath() == null || !uri.getPath().startsWith("/file/")) return null;
            return resolve(uri.getPath().substring(6));
        } catch (URISyntaxException e) { throw new IOException("非法文件地址", e); }
    }

    /** Resolve a configuration string value, not a global textual replacement of './'. */
    public static String reference(String base, String value) throws IOException {
        String prefix = value.startsWith("img+") ? "img+" : "";
        String ref = prefix.isEmpty() ? value : value.substring(4);
        int suffixAt = ref.indexOf(";md5;");
        String suffix = suffixAt < 0 ? "" : ref.substring(suffixAt);
        if (suffixAt >= 0) ref = ref.substring(0, suffixAt);
        if (ref.startsWith("clan://") && !ref.startsWith("clan://localhost/")) {
            try {
                URI resource = new URI(ref);
                URI source = new URI(toHttp(base.split(";pk;", 2)[0]));
                // Within a clan/file configuration, clan://TVBox/plugin.jar historically
                // names a path from that server's /file/ root, not a host named TVBox.
                // Top-level addresses still use toHttp(); an explicit port is a full address.
                if (resource.getPort() == -1 && source.getRawPath() != null
                        && source.getRawPath().startsWith("/file/")
                        && ("http".equals(source.getScheme()) || "https".equals(source.getScheme()))) {
                    String path = decodePath(ref.substring("clan://".length()));
                    resolve(path);
                    return prefix + source.resolve("/file/" + encodePath(path)).toASCIIString() + suffix;
                }
            } catch (URISyntaxException e) { throw new IOException("非法clan资源地址", e); }
        }
        if (ref.startsWith("file:/") || ref.startsWith("clan://")) {
            return prefix + toHttp(ref) + suffix;
        }
        boolean relative = ref.startsWith("./") || ref.startsWith("../")
                || (!ref.contains(":") && !ref.startsWith("/")
                && ref.matches("(?i)[^\\s{}\\[\\]]+\\.(json|js|jar|png|jpg|jpeg|webp|gif|m3u|txt)(\\?.*)?"));
        if (!relative) return value;
        try {
            String url = toHttp(base.split(";pk;", 2)[0]);
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
            URI relativeUri;
            try { relativeUri = new URI(ref); }
            catch (URISyntaxException invalidLiteral) { relativeUri = new URI(null, null, ref, null); }
            String resolved = new URI(url).resolve(relativeUri).normalize().toASCIIString();
            File localBase = fromLocalUrl(url);
            if (localBase != null && fromLocalUrl(resolved) == null) throw new IOException("相对路径超出工作区");
            return prefix + resolved + suffix;
        } catch (URISyntaxException e) { throw new IOException("非法相对资源地址", e); }
    }
}
