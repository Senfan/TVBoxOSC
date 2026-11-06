package com.github.tvbox.osc.util;

import android.os.Environment;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.utils.Path;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.StringUtils;
import com.github.tvbox.osc.util.urlhttp.OkHttpUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.model.HttpHeaders;
import com.orhanobut.hawk.Hawk;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Response;

public class FileUtils {

    public static File open(String str) {
        return new File(getCachePath() + "/qjscache_" + str + ".js");
    }

    public static boolean writeSimple(byte[] data, File dst) {
        try {
            WorkspaceIO.writeAtomic(new java.io.ByteArrayInputStream(data), dst, WorkspaceIO.MAX_FILE_BYTES);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static byte[] readSimple(File src) {
        try (InputStream input = new FileInputStream(src)) {
            return WorkspaceIO.read(input, WorkspaceIO.MAX_FILE_BYTES);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String readFileToString(String path, String charsetName) {
        // 定义返回结果
        StringBuilder jsonString = new StringBuilder();

        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(path), charsetName));// 读取文件
            String thisLine;
            while ((thisLine = in.readLine()) != null) {
                jsonString.append(thisLine);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException el) {
                }
            }
        }
        // 返回拼接好的JSON String
        return jsonString.toString();
    }

    public static void copyFile(File source, File dest) throws IOException {
        try (InputStream input = new FileInputStream(source)) {
            WorkspaceIO.writeAtomic(input, dest, WorkspaceIO.MAX_TOTAL_BYTES);
        }
    }
    public static String getRootPath() {
        try { return WorkspaceFiles.root().getAbsolutePath(); }
        catch (IOException e) { throw new IllegalStateException("私有工作区不可用", e); }
    }
    public static void recursiveDelete(File file) {
        try {
            if (!file.exists())
                return;
            if (file.isDirectory()) {
                for (File f : file.listFiles()) {
                    recursiveDelete(f);
                }
            }
            file.delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //JS  工具方法
    private static final Pattern URL_JOIN = Pattern.compile("^http.*\\.(js|txt|json|m3u)", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    public static String loadModule(String name) {
        String rel = null;
        try {
            File local = WorkspaceFiles.fromLocalUrl(name);
            if (local != null) return WorkspaceIO.text(local);
            if (name.equals("gbk.js")) {
                name = "gbk.js";
            } else if (name.equals("模板.js")) {
                name = "模板.js";
            } else if (name.equals("cat.js")) {
                name = "cat.js";
            }
            LOG.i("echo-loadModule "+name);
            Matcher m = URL_JOIN.matcher(name);
            if (m.find()) {
                if (!Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
                    String cache = getCache(MD5.encode(name));
                    rel= cache;
                    if (StringUtils.isEmpty(cache)) {
                        String netStr = get(name);
                        if (!TextUtils.isEmpty(netStr)) {
                            setCache(604800, MD5.encode(name), netStr);
                        }
                        rel= netStr;
                    }
                } else {
                    rel= get(name);
                }
            } else if (name.startsWith("assets://")) {
                rel= getAsOpen(name.substring(9));
            } else if (isAsFile(name, "js/lib")) {
                rel=getAsOpen("js/lib/" + name);
            } else if (name.startsWith("clan://")) {
                rel = get(WorkspaceFiles.toHttp(name));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
        return rel;
    }


    private static final Map<String, Set<String>> cachedDirFiles = new HashMap<>();
    public static boolean isAsFile(String name,String dir) {
        // 1. 先从缓存里取目录列表
        Set<String> files = cachedDirFiles.get(dir);
        if (files == null) {
            LOG.i("echo-读取AssetsList");
            try {
                String[] list = App.getInstance().getAssets().list(dir);
                files = new HashSet<>(Arrays.asList(list));
            } catch (IOException e) {
                files = Collections.emptySet();
            }
            cachedDirFiles.put(dir, files);
        }
        // 2. 内存查找
        return files.contains(name.trim());
    }

    public static String getAsOpen(String name) {
        try {
            InputStream is = App.getInstance().getAssets().open(name);
            byte[] data = new byte[is.available()];
            is.read(data);
            return new String(data, "UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    public static String getCache(String name) {
        try {
            String code = "";
            File file = open(name);
            if (file.exists()) {
                code = new String(readSimple(file));
            }
            if (TextUtils.isEmpty(code)) {
                return "";
            }
            JsonObject asJsonObject = (new Gson().fromJson(code, JsonObject.class)).getAsJsonObject();
            if (((long) asJsonObject.get("expires").getAsInt()) <= System.currentTimeMillis() / 1000) {
                recursiveDelete(open(name));
            }
            return asJsonObject.get("data").getAsString();
        } catch (Exception e4) {
            return "";
        }
    }

    public static byte[] getCacheByte(String name) {
        try {
            File file = open("B_" + name);
            if (file.exists()) {
                return readSimple(file);
            }
            return null;
        } catch (Exception e4) {
            return null;
        }
    }

    public static void setCache(int time, String name, String data) {
        try {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("expires", (int) (time + (System.currentTimeMillis() / 1000)));
            jSONObject.put("data", data);
            writeSimple(jSONObject.toString().getBytes(), open(name));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void setCacheByte(String name, byte[] data) {
        try {
            writeSimple(byteMerger("//DRPY".getBytes(),Base64.encode(data, Base64.URL_SAFE)), open("B_" + name));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static byte[] byteMerger(byte[] bt1, byte[] bt2){
        byte[] bt3 = new byte[bt1.length+bt2.length];
        System.arraycopy(bt1, 0, bt3, 0, bt1.length);
        System.arraycopy(bt2, 0, bt3, bt1.length, bt2.length);
        return bt3;
    }

    public static String get(String str) {
        return get(str, null);
    }

    public static String get(String str, Map<String, String> headerMap) {
        try {
            HttpHeaders h = new HttpHeaders();
            Response response = null;
            if (headerMap != null) {
                for (String key : headerMap.keySet()) {
                    h.put(key, headerMap.get(key));
                }
                response = OkGo.<String>get(str).headers(h).execute();
            } else {
                response =OkGo.<String>get(str).headers("User-Agent", str.startsWith("https://gitcode.net/") ? UA.random() : "okhttp/3.15").execute();
            }
            try (Response closeable = response) {
                if (response.isSuccessful() && response.body() != null) {
                    return new String(WorkspaceIO.read(response.body().byteStream(), WorkspaceIO.MAX_TEXT_BYTES), StandardCharsets.UTF_8);
                }
                return "";
            }
        } catch (IOException e) {
            return "";
        }
    }


    public static File getCacheDir() {
        return App.getInstance().getCacheDir();
    }
    public static File getExternalCacheDir() {
        return App.getInstance().getExternalCacheDir();
    }
    public static String getExternalCachePath() {
        //部分机器getExternalCacheDir()会返回空
        File externalCacheDir = getExternalCacheDir();
        if (externalCacheDir == null){
            return getCachePath();
        }
        return externalCacheDir.getAbsolutePath();
    }

    public static String getCachePath() {
        return getCacheDir().getAbsolutePath();
    }

    public static void cleanPlayerCache() {
        recursiveDelete(new File(getCachePath() + File.separator + "thunder"));
        recursiveDelete(new File(getExternalCachePath() + File.separator + "ijkcaches"));
        File jpali = new File(getExternalCachePath(), "jpali");
        recursiveDelete(new File(jpali, "Downloads"));
        CacheCleanup.removeEmptyDirectory(jpali);
    }

    public static void clearCacheExceptUpdates(File directory) {
        CacheCleanup.clearExceptUpdates(directory);
    }

    public static String getFileName(String filePath) {
        if (TextUtils.isEmpty(filePath)) return "";
        String fileName = filePath;
        int p = fileName.lastIndexOf(File.separatorChar);
        if (p != -1) {
            fileName = fileName.substring(p + 1);
        }
        return fileName;
    }

    public static String getFileNameWithoutExt(String filePath) {
        if (TextUtils.isEmpty(filePath)) return "";
        String fileName = filePath;
        int p = fileName.lastIndexOf(File.separatorChar);
        if (p != -1) {
            fileName = fileName.substring(p + 1);
        }
        p = fileName.indexOf('.');
        if (p != -1) {
            fileName = fileName.substring(0, p);
        }
        return fileName;
    }

    public static String getFileExt(String fileName) {
        if (TextUtils.isEmpty(fileName)) return "";
        int p = fileName.lastIndexOf('.');
        if (p != -1) {
            return fileName.substring(p)
                .toLowerCase();
        }
        return "";
    }

    public static boolean hasExtension(String path) {
        int lastDotIndex = path.lastIndexOf(".");
        int lastSlashIndex = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
        // 如果路径中有点号，并且点号在最后一个斜杠之后，认为有后缀
        return lastDotIndex > lastSlashIndex && lastDotIndex < path.length() - 1;
    }

    public static String read(String path) {
        try {
            return read(new FileInputStream(getLocal(path)));
        } catch (Exception e) {
            return "";
        }
    }

    public static String read(InputStream is) {
        try (InputStream input = is) {
            return new String(WorkspaceIO.read(input, WorkspaceIO.MAX_TEXT_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }
    public static File getLocal(String path) {
        try {
            File local = WorkspaceFiles.fromLocalUrl(path);
            if (local != null) return local;
            if (new File(path).isAbsolute()) return new File(path); // Explicit legacy/media path only.
            return WorkspaceFiles.resolve(path);
        } catch (IOException e) { throw new IllegalArgumentException("非法本地路径", e); }
    }
    public static void deleteFile(File file) {
        if (!file.exists()) return;
        if (file.isFile()) {
            if (file.canWrite()) file.delete();
            return;
        }
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null || files.length == 0) {
                if (file.canWrite()) file.delete();
                return;
            }
            for(File one : files) {
                deleteFile(one);
            }
        }
        return;
    }

    public static String getFilePath() {
        return App.getInstance().getFilesDir().getAbsolutePath();
    }

    public static boolean isWeekAgo(File file) {
        long oneWeekMillis = 15L * 24 * 60 * 60 * 1000;
        long timeDiff = System.currentTimeMillis() - file.lastModified();
        return timeDiff > oneWeekMillis;
    }
}
