
package com.github.catvod.crawler;

import android.content.Context;
import android.util.Log;


import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.util.DynamicCodeFiles;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.MD5;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class JarLoader {
    private final ConcurrentHashMap<String, DexClassLoader> classLoaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> proxyMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private volatile String recentJarKey = "";

    /**
     * 不要在主线程调用我
     *
     * @param cache
     */
    public boolean load(String cache) {
        recentJarKey = "main";
        String md5 = "";
        // ApiConfig owns main-JAR acquisition (including img+); its public load API only passes a path.
        // Apply the configured checksum here as well, without changing that source protocol.
        String spider = ApiConfig.get().getSpider();
        if (spider != null) {
            String[] urls = spider.split(";md5;");
            if (urls.length > 1) md5 = urls[1].trim();
        }
        // ApiConfig's cache filename is already the source URL key; avoid a shared MD5 digest here.
        String prefix = "main-" + new File(cache).getName();
        if (loadClassLoader(cache, "main", prefix, md5)) return true;
        return loadPrevious("main", prefix, md5);
    }

    /** Validate a new main candidate without treating an older loaded/snapshotted JAR as success. */
    public synchronized boolean loadCandidate(String path, String stableCacheName, String expectedMd5) {
        if (!classLoaders.isEmpty() || stableCacheName == null || stableCacheName.isEmpty()
                || !new File(stableCacheName).getName().equals(stableCacheName)) return false;
        recentJarKey = "main";
        return loadClassLoader(path, "main", "main-" + stableCacheName, expectedMd5);
    }

    public void clear() {
        spiders.clear();
        proxyMethods.clear();
        classLoaders.clear();
    }

    private File codeDir() {
        return new File(App.getInstance().getFilesDir(), "dynamic_code/jar");
    }

    private boolean loadClassLoader(String jar, String key, String prefix, String md5) {
        if (classLoaders.containsKey(key)) return true;
        File prepared = null;
        try {
            prepared = DynamicCodeFiles.prepareForLoading(new File(jar), codeDir(), prefix, ".jar", md5);
            if (loadPrepared(prepared, key)) return true;
        } catch (Throwable th) {
            th.printStackTrace();
        }
        if (prepared != null) prepared.delete(); // Only this failed attempt's private copy.
        return false;
    }

    private boolean loadPrevious(String key, String prefix, String md5) {
        try {
            File previous = DynamicCodeFiles.latestReadOnly(codeDir(), prefix, ".jar");
            if (previous != null) {
                DynamicCodeFiles.verifyMd5(previous, md5);
                Log.i("JarLoader", "echo-回退到私有只读jar缓存: " + key);
                return loadPrepared(previous, key);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return false;
    }

    private boolean loadPrepared(File jar, String key) {
        if (classLoaders.containsKey(key)){
            Log.i("JarLoader", "echo-loadClassLoader jar缓存: " + key);
            return true;
        }
        boolean success = false;
        try {
            if (!DynamicCodeFiles.isReadOnlyRegularFile(jar)) throw new IOException("拒绝加载可写jar");
            File cacheDir = new File(App.getInstance().getCacheDir().getAbsolutePath() + "/catvod_csp");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            final DexClassLoader classLoader = new DexClassLoader(jar.getAbsolutePath(), cacheDir.getAbsolutePath(), null, App.getInstance().getClassLoader());
            Method loadedProxy = null;
            int count = 0;
            do {
                try {
                    final Class<?> classInit = classLoader.loadClass("com.github.catvod.spider.Init");
                    if (classInit != null) {
                        final Method initMethod = classInit.getMethod("init", Context.class);
                        // 在子线程中调用 init 方法，避免网络请求在主线程中执行
                        Thread initThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    initMethod.invoke(null, App.getInstance());
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                        initThread.start();
                        initThread.join();
                        Log.i("JarLoader", "echo-自定义爬虫代码加载成功!");
                        success = true;
                        try {
                            Class<?> proxy = classLoader.loadClass("com.github.catvod.spider.Proxy");
                            loadedProxy = proxy.getMethod("proxy", Map.class);
                        } catch (Throwable th) {
                            // 可以记录错误日志
                            th.printStackTrace();
                        }
                        break;
                    }
                    Thread.sleep(200);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                count++;
            } while (count < 2);

            if (success && classLoaders.putIfAbsent(key, classLoader) == null && loadedProxy != null) {
                proxyMethods.put(key, loadedProxy);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return success;
    }

    private DexClassLoader loadJarInternal(String jar, String md5, String key) {
        if (classLoaders.containsKey(key)){
            Log.i("JarLoader", "echo-loadJarInternal jar缓存: " + key);
            return classLoaders.get(key);
        }
        File cache = new File(App.getInstance().getFilesDir(), "csp/" + key + ".jar");
        String prefix = "sub-" + key;
        if (cache.exists() && (!md5.isEmpty() || !FileUtils.isWeekAgo(cache))) {
            if (loadClassLoader(cache.getAbsolutePath(), key, prefix, md5)) return classLoaders.get(key);
        }
        File candidate = null;
        try {
            Response response = OkGo.<File>get(jar).execute();
            try (ResponseBody body = response.body()) {
                if (!response.isSuccessful() || body == null) throw new IOException("下载jar失败");
                candidate = DynamicCodeFiles.writeReadOnly(body.byteStream(), body.contentLength(),
                        cache.getParentFile(), key, ".jar", md5);
            }
            if (loadClassLoader(candidate.getAbsolutePath(), key, prefix, md5)) {
                DynamicCodeFiles.replacePreservingPrevious(candidate, cache);
                return classLoaders.get(key);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            if (candidate != null && candidate.exists()) candidate.delete();
        }
        // Failed refreshes never truncate the old cache; declared MD5 still applies to fallback.
        if (classLoaders.containsKey(key)) return classLoaders.get(key);
        if (cache.exists() && loadClassLoader(cache.getAbsolutePath(), key, prefix, md5)) return classLoaders.get(key);
        return loadPrevious(key, prefix, md5) ? classLoaders.get(key) : null;
    }

    public Spider getSpider(String key, String cls, String ext, String jar) {
        if (spiders.containsKey(key)) {
            Log.i("JarLoader", "echo-getSpider spider缓存: " + key);
            return spiders.get(key);
        }
        String clsKey = cls.replace("csp_", "");
        String jarUrl = "";
        String jarMd5 = "";
        String jarKey;
        if (jar.isEmpty()) {
            jarKey = "main";
        } else {
            String[] urls = jar.split(";md5;");
            jarUrl = urls[0];
            jarKey = MD5.string2MD5(jarUrl);
            jarMd5 = urls.length > 1 ? urls[1].trim() : "";
        }
        recentJarKey = jarKey;
        assert jarKey != null;
        DexClassLoader classLoader = jarKey.equals("main")? classLoaders.get("main"):loadJarInternal(jarUrl, jarMd5, jarKey);
        if (classLoader == null) return new SpiderNull();
        try {
            Log.i("JarLoader", "echo-getSpider 加载spider: " + key);
            Spider sp = (Spider) classLoader.loadClass("com.github.catvod.spider." + clsKey).newInstance();
            sp.init(App.getInstance(), ext);
            if (!jar.isEmpty()) {
                sp.homeContent(false); // 增加此行 应该可以解决部分写的有问题源的历史记录问题 但会增加这个源的首次加载时间 不需要可以已删掉
            }
            spiders.put(key, sp);
            return sp;
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return new SpiderNull();
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Json" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            assert classLoader != null;
            Class<?> jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Mix" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            assert classLoader != null;
            Class<?> jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, name, flag, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public Object[] proxyInvoke(Map<String,String> params) {
        try {
            Method proxyFun = proxyMethods.get(recentJarKey);
            if (proxyFun != null) {
                return (Object[]) proxyFun.invoke(null, params);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }
}
