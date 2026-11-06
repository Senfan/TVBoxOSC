package com.github.catvod.crawler;


import android.util.Log;

import com.github.tvbox.osc.base.App;

import com.github.tvbox.osc.util.DynamicCodeFiles;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;

import com.github.tvbox.osc.util.js.JsSpider;
import com.lzy.okgo.OkGo;

import java.io.File;
import java.io.IOException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class JsLoader {
    private static final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Class<?>> classes = new ConcurrentHashMap<>();
    //当前的Js爬虫key
    private volatile String recentKey = "";

    public static void destroy() {
        for (Spider spider : spiders.values()){
            spider.cancelByTag();
            spider.destroy();
        }
    }
    public void clear() {
        spiders.clear();
        classes.clear();
    }

    public static void stopAll() {
        for (Spider spider : spiders.values()){
            spider.cancelByTag();
        }
    }

    private File codeDir() {
        return new File(App.getInstance().getFilesDir(), "dynamic_code/js");
    }

    private boolean loadClassLoader(String jar, String key, String md5) {
        if (classes.containsKey(key)) return true;
        File prepared = null;
        try {
            prepared = DynamicCodeFiles.prepareForLoading(new File(jar), codeDir(), key, ".jar", md5);
            if (loadPrepared(prepared, key)) return true;
        } catch (Throwable th) {
            th.printStackTrace();
        }
        if (prepared != null) prepared.delete(); // Only this failed attempt's private copy.
        return false;
    }

    private boolean loadPrevious(String key, String md5) {
        try {
            File previous = DynamicCodeFiles.latestReadOnly(codeDir(), key, ".jar");
            if (previous != null) {
                DynamicCodeFiles.verifyMd5(previous, md5);
                Log.i("JSLoader", "echo-回退到私有只读jsapi缓存");
                return loadPrepared(previous, key);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return false;
    }

    private boolean loadPrepared(File jar, String key) {
        boolean success = false;
        Class<?> classInit = null;
        try {
            if (!DynamicCodeFiles.isReadOnlyRegularFile(jar)) throw new IOException("拒绝加载可写jsapi jar");
            File cacheDir = new File(App.getInstance().getCacheDir().getAbsolutePath() + "/catvod_jsapi");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            DexClassLoader classLoader = new DexClassLoader(jar.getAbsolutePath(), cacheDir.getAbsolutePath(), null, App.getInstance().getClassLoader());
            int count = 0;
            do {
                try {
                    classInit = classLoader.loadClass("com.github.catvod.js.Method");
                    if (classInit != null) {
                        Log.i("JSLoader", "echo-自定义jsapi代码加载成功!");
                        success = true;
                        break;
                    }
                    Thread.sleep(200);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                count++;
            } while (count < 5);

            if (success) {
                classes.putIfAbsent(key, classInit);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return success;
    }

    private Class<?> loadJarInternal(String jar, String md5, String key) {
        if (classes.containsKey(key)){
            Log.i("JSLoader", "echo-loadJarInternal cached");
            return classes.get(key);
        }
        File cache = new File(App.getInstance().getFilesDir(), "csp/" + key + ".jar");
        if (cache.exists() && (!md5.isEmpty() || !FileUtils.isWeekAgo(cache))) {
            if (loadClassLoader(cache.getAbsolutePath(), key, md5)) return classes.get(key);
        }
        File candidate = null;
        try {
            Response response = OkGo.<File>get(jar).execute();
            try (ResponseBody body = response.body()) {
                if (!response.isSuccessful() || body == null) throw new IOException("下载jsapi jar失败");
                candidate = DynamicCodeFiles.writeReadOnly(body.byteStream(), body.contentLength(),
                        cache.getParentFile(), key, ".jar", md5);
            }
            if (loadClassLoader(candidate.getAbsolutePath(), key, md5)) {
                DynamicCodeFiles.replacePreservingPrevious(candidate, cache);
                return classes.get(key);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            if (candidate != null && candidate.exists()) candidate.delete();
        }
        if (classes.containsKey(key)) return classes.get(key);
        if (cache.exists() && loadClassLoader(cache.getAbsolutePath(), key, md5)) return classes.get(key);
        return loadPrevious(key, md5) ? classes.get(key) : null;
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        if (spiders.containsKey(key)){
            Log.i("JSLoader", "echo-getSpider cached");
            return spiders.get(key);
        }
        Class<?> classLoader = null;
        if (!jar.isEmpty()) {
            String[] urls = jar.split(";md5;");
            String jarUrl = urls[0];
            String jarKey = MD5.string2MD5(jarUrl);
            String jarMd5 = urls.length > 1 ? urls[1].trim() : "";
            classLoader = loadJarInternal(jarUrl, jarMd5, jarKey);
        }
        recentKey = key;
        try {
            Log.i("JSLoader", "echo-getSpider load");
            Spider sp = new JsSpider(key, api, classLoader);
            sp.init(App.getInstance(), ext);
            spiders.put(key, sp);
            return sp;
        } catch (Throwable th) {
            LOG.i("echo-getSpider-error "+th.getMessage());
        }
        return new SpiderNull();
    }

    public Object[] proxyInvoke(Map<String, String> params) {
        try {
            Spider proxyFun = spiders.get(recentKey);
            if (proxyFun != null) {
                return proxyFun.proxyLocal(params);
            }
        } catch (Throwable th) {
        }
        return null;
    }
}
