package com.github.tvbox.osc.server;

import static com.github.tvbox.osc.util.RegexUtils.getPattern;
import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import com.github.tvbox.osc.util.WorkspaceFiles;
import com.github.tvbox.osc.util.WorkspaceIO;
import android.util.Base64;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.OkGoHelper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.github.tvbox.osc.util.Proxy;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import fi.iki.elonen.NanoHTTPD;

/**
 * @author pj567
 * @date :2021/1/5
 * @description:
 */
public class RemoteServer extends NanoHTTPD {
    private Context mContext;
    public static int serverPort = 9978;
    private boolean isStarted = false;
    private DataReceiver mDataReceiver;
    private ArrayList < RequestProcess > getRequestList = new ArrayList < > ();
    private ArrayList < RequestProcess > postRequestList = new ArrayList < > ();
    private static final String PATTERN_ETH_STR = "^eth\\d+$";
    private static final Pattern ETH_PATTERN = Pattern.compile(PATTERN_ETH_STR);
    public static String m3u8Content;
    public static String vodName;
    public static String artist;

    public RemoteServer(int port, Context context) {
        super(port);
        mContext = context;
        addGetRequestProcess();
        addPostRequestProcess();
    }

    private void addGetRequestProcess() {
        getRequestList.add(new RawRequestProcess(this.mContext, "/", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/index.html", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/style.css", R.raw.style, "text/css"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/ui.css", R.raw.ui, "text/css"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/jquery.js", R.raw.jquery, "application/x-javascript"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/script.js", R.raw.script, "application/x-javascript"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/favicon.ico", R.drawable.app_icon, "image/x-icon"));
    }

    private void addPostRequestProcess() {
        postRequestList.add(new InputRequestProcess(this));
    }

    @Override
    public void start(int timeout, boolean daemon) throws IOException {
        isStarted = true;
        super.start(timeout, daemon);
        EventBus.getDefault().post(new ServerEvent(ServerEvent.SERVER_SUCCESS));
    }

    @Override
    public void stop() {
        super.stop();
        isStarted = false;
    }

    private Response getProxy(Object[] rs){
        try {
            if (rs[0] instanceof NanoHTTPD.Response) return (NanoHTTPD.Response) rs[0];
            int code = (int) rs[0];
            String mime = (String) rs[1];
            InputStream stream = rs[2] != null ? (InputStream) rs[2] : null;
            Response response = NanoHTTPD.newChunkedResponse(
                    Response.Status.lookup(code),
                    mime,
                    stream
            );
            // 添加头部信息
            if (rs.length >= 4 && rs[3] instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> mapHeader = (Map<String, String>) rs[3];
                if(!mapHeader.isEmpty()){
                    for (String key : mapHeader.keySet()) {
                        response.addHeader(key, mapHeader.get(key));
                    }
                }
            }
            return response;
        } catch (Throwable th) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "500");
        }
    }
    @Override
    public Response serve(IHTTPSession session) {
        EventBus.getDefault().post(new ServerEvent(ServerEvent.SERVER_CONNECTION));
        if (!session.getUri().isEmpty()) {
            String fileName = session.getUri();
            if (session.getMethod() == Method.GET) {
                for (RequestProcess process: getRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.getParms(), null);
                    }
                }
                if (fileName.equals("/media")) {
                    JSONObject jsonObject = new JSONObject();
                    try {
                        jsonObject.put("title", vodName);
                        jsonObject.put("artist", artist);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, jsonObject.toString());
                }
                if (fileName.equals("/proxy")) {
                    Map < String, String > params = session.getParms();
                    params.putAll(session.getHeaders());
                    params.put("request-headers", new Gson().toJson(session.getHeaders()));
                    if (params.containsKey("do")) {
                        Object[] rs = ApiConfig.get().proxyLocal(params);
                        return getProxy(rs);
                    }
                    if (params.containsKey("go")) {
                        Object[] rs = Proxy.proxy(params);
                        return getProxy(rs);
                    }
                } else if (fileName.startsWith("/file/")) {
                    try {
                        String path = fileName.substring(6);
                        File localFile = WorkspaceFiles.resolve(path);
                        if (localFile.isFile()) {
                            long length = localFile.length();
                            if (length > WorkspaceIO.MAX_FILE_BYTES) return createPlainTextResponse(Response.Status.BAD_REQUEST, "文件超过下载上限");
                            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/octet-stream", new FileInputStream(localFile), length);
                        }
                        if (localFile.isDirectory()) return createPlainTextResponse(Response.Status.OK,
                                fileList(WorkspaceFiles.root().getPath(), path));
                        return createPlainTextResponse(Response.Status.NOT_FOUND, "工作区文件不存在，请导入配置目录或ZIP");
                    } catch (IOException | IllegalArgumentException e) {
                        return createPlainTextResponse(Response.Status.BAD_REQUEST, "非法或不可读的工作区路径");
                    }
                } else if (fileName.equals("/dns-query")) {
                    String name = session.getParms().get("name");
                    byte[] rs = null;
                    try {
                        rs = OkGoHelper.dnsOverHttps.lookupHttpsForwardSync(name);
                    } catch (Throwable th) {
                        rs = new byte[0];
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/dns-message", new ByteArrayInputStream(rs), rs.length);
                } else if (fileName.equals("/m3u8")) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, m3u8Content);
                } else if (fileName.startsWith("/dash/")) {
                    String dashData = App.getInstance().getDashData();
                    try {
                        String data = new String(Base64.decode(dashData, Base64.DEFAULT | Base64.NO_WRAP), "UTF-8");
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/dash+xml", data);
                    } catch (Throwable th) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, dashData);
                    }
                }
            } else if (session.getMethod() == Method.POST) {
                // NanoHTTPD parses/spools the body before dispatch. Bound it BEFORE parseBody.
                // Its parser is Content-Length based; ambiguous/chunked requests are refused.
                try {
                    String lengthHeader = session.getHeaders().get("content-length");
                    if (lengthHeader == null || !lengthHeader.matches("[0-9]+")
                            || session.getHeaders().containsKey("transfer-encoding")) {
                        return createPlainTextResponse(Response.Status.BAD_REQUEST, "需要明确Content-Length");
                    }
                    long length = Long.parseLong(lengthHeader);
                    long limit = fileName.equals("/upload") ? WorkspaceIO.MAX_TOTAL_BYTES + 2 * 1024 * 1024 : 64 * 1024;
                    if (length > limit) return createPlainTextResponse(Response.Status.BAD_REQUEST, "请求超过大小上限");
                } catch (NumberFormatException e) {
                    return createPlainTextResponse(Response.Status.BAD_REQUEST, "非法Content-Length");
                }
                Map < String, String > files = new HashMap < String, String > ();
                try {
                    if (session.getHeaders().containsKey("content-type")) {
                        String hd = session.getHeaders().get("content-type");
                        if (hd != null) {
                            // cuke: 修正中文乱码问题
                            if (hd.toLowerCase().contains("multipart/form-data") && !hd.toLowerCase().contains("charset=")) {
                                Matcher matcher = getPattern("[ |\t]*(boundary[ |\t]*=[ |\t]*['|\"]?[^\"^'^;^,]*['|\"]?)", Pattern.CASE_INSENSITIVE).matcher(hd);
                                String boundary = matcher.find() ? matcher.group(1) : null;
                                if (boundary != null) {
                                    session.getHeaders().put("content-type", "multipart/form-data; charset=utf-8; " + boundary);
                                }
                            }
                        }
                    }
                    session.parseBody(files);
                } catch (IOException IOExc) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + IOExc.getMessage());
                } catch (NanoHTTPD.ResponseException rex) {
                    return createPlainTextResponse(rex.getStatus(), rex.getMessage());
                }
                for (RequestProcess process: postRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.getParms(), files);
                    }
                }
                try {
                    Map < String, String > params = session.getParms();
                    if (fileName.equals("/upload")) {
                        importUpload(params, files);
                        return createPlainTextResponse(Response.Status.OK, "OK");
                    } else if (fileName.equals("/newFolder")) {
                        File parent = WorkspaceFiles.resolve(params.get("path"));
                        if (!parent.isDirectory()) throw new IOException("父目录不存在");
                        File folder = WorkspaceIO.resolve(parent, WorkspaceIO.name(params.get("name")));
                        if (folder.exists() || !folder.mkdir()) throw new IOException("目录已存在或创建失败");
                        return createPlainTextResponse(Response.Status.OK, "OK");
                    } else if (fileName.equals("/delFolder") || fileName.equals("/delFile")) {
                        File selected = WorkspaceFiles.resolve(params.get("path"));
                        if (selected.equals(WorkspaceFiles.root())) throw new IOException("不能删除工作区根");
                        if (fileName.equals("/delFile") && !selected.isFile()) throw new IOException("不是文件");
                        if (fileName.equals("/delFolder") && !selected.isDirectory()) throw new IOException("不是目录");
                        WorkspaceIO.deleteTree(selected);
                        return createPlainTextResponse(Response.Status.OK, "OK");
                    }
                } catch (IOException | IllegalArgumentException e) {
                    return createPlainTextResponse(Response.Status.BAD_REQUEST, "操作失败：路径、大小或文件内容不符合要求；原配置未替换");
                }

            }
        }
        //default page: index.html
        return getRequestList.get(0).doResponse(session, "", null, null);
    }

    public void setDataReceiver(DataReceiver receiver) {
        mDataReceiver = receiver;
    }

    public DataReceiver getDataReceiver() {
        return mDataReceiver;
    }

    public boolean isStarting() {
        return isStarted;
    }

    public String getServerAddress() {
        String ipAddress = getLocalIPAddress(mContext);
        return "http://" + ipAddress + ":" + RemoteServer.serverPort + "/";
    }

    public String getLoadAddress() {
        return "http://127.0.0.1:" + RemoteServer.serverPort + "/";
    }

    public static Response createPlainTextResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text);
    }

    public static Response createJSONResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, "application/json", text);
    }

    @SuppressLint("DefaultLocale")
    public static String getLocalIPAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        if (ipAddress == 0) {
            try {
                Enumeration < NetworkInterface > enumerationNi = NetworkInterface.getNetworkInterfaces();
                while (enumerationNi.hasMoreElements()) {
                    NetworkInterface networkInterface = enumerationNi.nextElement();
                    String interfaceName = networkInterface.getDisplayName();
                    if (ETH_PATTERN.matcher(interfaceName).matches() || interfaceName.equals("wlan0")) {
                        Enumeration < InetAddress > enumIpAddr = networkInterface.getInetAddresses();
                        while (enumIpAddr.hasMoreElements()) {
                            InetAddress inetAddress = enumIpAddr.nextElement();
                            if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                                return inetAddress.getHostAddress();
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                e.printStackTrace();
            }
        } else {
            return String.format("%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        }
        return "0.0.0.0";
    }

    String fileTime(long time, String fmt) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        Date date = calendar.getTime();
        SimpleDateFormat sdf = new SimpleDateFormat(fmt);
        return sdf.format(date);
    }

    private void importUpload(Map<String, String> params, Map<String, String> files) throws IOException {
        File destination = WorkspaceFiles.resolve(params.get("path"));
        if (!destination.isDirectory()) throw new IOException("目标目录不存在");
        File stage = WorkspaceFiles.newStage();
        try {
            WorkspaceIO.Budget budget = new WorkspaceIO.Budget();
            int count = 0;
            for (String key : files.keySet()) {
                if (!key.startsWith("files-")) continue;
                String name = WorkspaceIO.name(params.get(key));
                File temporary = new File(files.get(key));
                if (!temporary.isFile() || temporary.length() > WorkspaceIO.MAX_FILE_BYTES) throw new IOException("上传文件无效或过大");
                budget.entry();
                count++;
                try (InputStream input = new FileInputStream(temporary)) {
                    if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                        File unzipRoot = files.size() == 1 ? stage : WorkspaceIO.resolve(stage, name + "-contents");
                        WorkspaceIO.directory(unzipRoot);
                        WorkspaceIO.unzip(input, unzipRoot, budget);
                    } else {
                        File target = WorkspaceIO.resolve(stage, name);
                        if (target.exists()) throw new IOException("重复上传文件名");
                        try (FileOutputStream output = new FileOutputStream(target)) {
                            budget.add(WorkspaceIO.copy(input, output, budget.remaining()));
                            output.getFD().sync();
                        }
                    }
                }
            }
            if (count == 0) throw new IOException("没有上传文件");
            WorkspaceIO.publish(stage, destination, WorkspaceFiles.root());
        } finally {
            // This transaction's staging only; never a source upload/configuration/backup.
            if (stage.exists()) WorkspaceIO.deleteTree(stage);
        }
    }

    String fileList(String root, String path) throws IOException {
        File base = WorkspaceFiles.root();
        File directory = WorkspaceIO.resolve(base, path);
        File[] list = directory.listFiles();
        if (list == null) throw new IOException("目录不可读");
        JsonObject info = new JsonObject();
        info.addProperty("remote", getServerAddress().replace("http://", "clan://"));
        info.addProperty("del", directory.equals(base) ? 0 : 1);
        info.addProperty("parent", directory.equals(base) ? "." : WorkspaceIO.relative(base, directory.getParentFile()));
        Arrays.sort(list, (left, right) -> {
            if (left.isDirectory() != right.isDirectory()) return left.isDirectory() ? -1 : 1;
            return left.getName().compareTo(right.getName());
        });
        JsonArray result = new JsonArray();
        for (File file : list) {
            if (file.getName().startsWith(".")) continue;
            String relative;
            try {
                relative = WorkspaceIO.relative(base, file);
                if (!file.getAbsoluteFile().equals(WorkspaceIO.resolve(base, relative))) continue;
            } catch (IOException e) { continue; }
            JsonObject item = new JsonObject();
            item.addProperty("name", file.getName());
            item.addProperty("path", relative);
            item.addProperty("time", fileTime(file.lastModified(), "yyyy/MM/dd aHH:mm:ss"));
            item.addProperty("dir", file.isDirectory() ? 1 : 0);
            result.add(item);
        }
        info.add("files", result);
        return info.toString();
    }
}
