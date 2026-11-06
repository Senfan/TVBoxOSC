#!/usr/bin/env python3
"""Compile only dynamic-code sources with JDK 17 and isolated Android/network/ART doubles."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
JDK = Path(os.environ.get("JAVA_HOME", "/Users/bytedance/Library/Java/JavaVirtualMachines/corretto-17-tvbox.jdk/Contents/Home"))
STUBS = {
    "android/content/Context.java": '''package android.content;
import java.io.File;
public class Context {
 public static final int MODE_PRIVATE=0; public final File root;
 public Context(File root) { this.root=root; root.mkdirs(); }
 public File getFilesDir(){ return directory("files"); }
 public File getCacheDir(){ return directory("cache"); }
 public File getDir(String name,int mode){ return directory("app_"+name); }
 private File directory(String name){ File f=new File(root,name); f.mkdirs(); return f; }
 public ClassLoader getClassLoader(){ return getClass().getClassLoader(); }
}''',
    "android/app/Activity.java": '''package android.app;
public class Activity extends android.content.Context { public Activity(java.io.File f){super(f);} }''',
    "android/util/Log.java": '''package android.util;
public class Log { public static int i(String a,String b){return 0;} public static int e(String a,String b){return 0;} }''',
    "android/widget/Toast.java": '''package android.widget;
public class Toast { public static final int LENGTH_LONG=1;
 public static Toast makeText(android.content.Context c,String s,int t){return new Toast();} public void show(){} }''',
    "com/github/tvbox/osc/base/App.java": '''package com.github.tvbox.osc.base;
public class App extends android.content.Context {
 private static App instance; public App(java.io.File f){super(f);instance=this;} public static App getInstance(){return instance;}
}''',
    "com/github/tvbox/osc/api/ApiConfig.java": '''package com.github.tvbox.osc.api;
public class ApiConfig { private static final ApiConfig INSTANCE=new ApiConfig();
 public static String spider=""; public static ApiConfig get(){return INSTANCE;} public String getSpider(){return spider;} }''',
    "com/github/tvbox/osc/util/MD5.java": '''package com.github.tvbox.osc.util;
public class MD5 { public static String string2MD5(String s){ try {
 byte[] b=java.security.MessageDigest.getInstance("MD5").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
 StringBuilder out=new StringBuilder();for(byte v:b)out.append(String.format("%02x",v&255));return out.toString();
 }catch(Exception e){throw new AssertionError(e);} } }''',
    "com/github/tvbox/osc/util/FileUtils.java": '''package com.github.tvbox.osc.util;
public class FileUtils { public static boolean isWeekAgo(java.io.File f){return System.currentTimeMillis()-f.lastModified()>7L*24*60*60*1000;} }''',
    "com/github/tvbox/osc/util/LOG.java": '''package com.github.tvbox.osc.util;
public class LOG { public static void i(String s){} public static void e(String s){} }''',
    "com/github/catvod/crawler/Spider.java": '''package com.github.catvod.crawler;
public class Spider { public void init(android.content.Context c,String e){} public String homeContent(boolean f){return "";}
 public void destroy(){} public void cancelByTag(){} public Object[] proxyLocal(java.util.Map<String,String> p){return null;} }''',
    "com/github/catvod/crawler/SpiderNull.java": '''package com.github.catvod.crawler; public class SpiderNull extends Spider {}''',
    "com/github/tvbox/osc/util/js/JsSpider.java": '''package com.github.tvbox.osc.util.js;
public class JsSpider extends com.github.catvod.crawler.Spider {public JsSpider(String k,String a,Class<?> c){} }''',
    "org/json/JSONObject.java": '''package org.json; public class JSONObject {}''',
    "okhttp3/ResponseBody.java": '''package okhttp3;
public class ResponseBody implements java.io.Closeable {
 private final java.io.InputStream stream; private final long length; public boolean closed;
 public ResponseBody(java.io.InputStream stream,long length){this.stream=stream;this.length=length;}
 public java.io.InputStream byteStream(){return stream;} public long contentLength(){return length;}
 public void close()throws java.io.IOException {closed=true;stream.close();}
}''',
    "okhttp3/Response.java": '''package okhttp3;
public class Response { private final ResponseBody body; private final boolean success;
 public Response(ResponseBody b,boolean s){body=b;success=s;} public ResponseBody body(){return body;}
 public boolean isSuccessful(){return success;} }''',
    "com/lzy/okgo/OkGo.java": '''package com.lzy.okgo;
public class OkGo<T> {public static volatile okhttp3.Response next; public static volatile boolean fail;
 public static final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
 public static <T> OkGo<T> get(String url){return new OkGo<>();}
 public okhttp3.Response execute()throws java.io.IOException {calls.incrementAndGet();if(fail)throw new java.io.IOException("expected offline fixture");return next;}
}''',
    "dalvik/system/DexClassLoader.java": '''package dalvik.system;
public class DexClassLoader extends ClassLoader {
 public static final java.util.List<java.io.File> loaded=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
 public DexClassLoader(String dex,String opt,String lib,ClassLoader parent){super(parent);try {
 java.io.File f=new java.io.File(dex);
 if(!com.github.tvbox.osc.util.DynamicCodeFiles.isReadOnlyRegularFile(f))throw new AssertionError("writable ART input: "+f);
 if(!f.getParent().contains("dynamic_code"))throw new AssertionError("legacy ART input: "+f);
 String s=new String(java.nio.file.Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8);
 if(s.startsWith("bad"))throw new IllegalArgumentException("expected rejected plugin fixture");loaded.add(f);
 }catch(java.io.IOException e){throw new IllegalStateException(e);} }
 public Class<?> loadClass(String name)throws ClassNotFoundException {
 if(name.equals("com.github.catvod.spider.Init"))return fixture.Init.class;
 if(name.equals("com.github.catvod.spider.Proxy"))return fixture.Proxy.class;
 if(name.equals("com.github.catvod.js.Method"))return fixture.JsMethod.class;
 return super.loadClass(name);
} }''',
    "fixture/Init.java": '''package fixture; public class Init {public static void init(android.content.Context c){} }''',
    "fixture/Proxy.java": '''package fixture; public class Proxy {public static Object[] proxy(java.util.Map p){return new Object[]{"ok"};} }''',
    "fixture/JsMethod.java": '''package fixture; public class JsMethod {}''',
    "org/xwalk/core/XWalkEnvironment.java": '''package org.xwalk.core;
public class XWalkEnvironment {public static String getRuntimeAbi(){return "arm64-v8a";} }''',
    "org/xwalk/core/XWalkInitializer.java": '''package org.xwalk.core;
public class XWalkInitializer {public static int created;
 public interface XWalkInitListener {void onXWalkInitStarted();void onXWalkInitCancelled();void onXWalkInitFailed();void onXWalkInitCompleted();}
 public XWalkInitializer(XWalkInitListener l,android.content.Context c){created++;}
 public boolean isXWalkReady(){return true;} public void initAsync(){} }''',
    "org/xwalk/core/XWalkDecompressor.java": '''package org.xwalk.core;
public class XWalkDecompressor { public static boolean corruptDex,fail;public static java.io.File lastStage;
 public static boolean extractResource(String apk,String target)throws Exception {
 if(!com.github.tvbox.osc.util.DynamicCodeFiles.isReadOnlyRegularFile(new java.io.File(apk)))throw new AssertionError("writable XWalk APK input");
 lastStage=new java.io.File(target);if(!lastStage.getName().startsWith("xwalk-stage-"))throw new AssertionError("extraction is not isolated");
 if(fail)return false;
 try(java.util.zip.ZipFile z=new java.util.zip.ZipFile(apk)) {
 for(String name:new String[]{"classes.dex","icudtl.dat","libxwalkcore.so","xwalk.pak","xwalk_100_percent.pak"}) {
 String source=name.equals("libxwalkcore.so")?"lib/arm64-v8a/"+name:name.equals("classes.dex")?name:"assets/"+name;
 try(java.io.InputStream in=z.getInputStream(z.getEntry(source))){java.nio.file.Files.copy(in,new java.io.File(target,name).toPath());}
 }
 }
 if(corruptDex){java.io.File f=new java.io.File(target,"classes.dex");byte[] b=java.nio.file.Files.readAllBytes(f.toPath());b[b.length-1]^=1;java.nio.file.Files.write(f.toPath(),b);}
 return true;
} }''',
}


def run():
    if subprocess.check_output([str(JDK / "bin/java"), "-version"], stderr=subprocess.STDOUT).decode().find('version "17') < 0:
        raise SystemExit("Set JAVA_HOME to an existing JDK 17; no app/Gradle build is run.")
    with tempfile.TemporaryDirectory(prefix="tvbox-dynamic-code-tests-") as temporary:
        temporary = Path(temporary)
        sources = []
        for name, text in STUBS.items():
            path = temporary / "stubs" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            sources.append(str(path))
        for name in ["com/github/tvbox/osc/util/DynamicCodeFiles.java", "com/github/tvbox/osc/util/XWalkUtils.java",
                     "com/github/catvod/crawler/JarLoader.java", "com/github/catvod/crawler/JsLoader.java"]:
            sources.append(str(ROOT / "app/src/main/java" / name))
        sources.append(str(ROOT / "tests/dynamic-code-tests/DynamicCodeTest.java"))
        classes = temporary / "classes"
        classes.mkdir()
        subprocess.run([str(JDK / "bin/javac"), "--release", "8", "-encoding", "UTF-8", "-d", str(classes), *sources], check=True, cwd=ROOT)
        result = subprocess.run([str(JDK / "bin/java"), "-cp", str(classes), "com.github.tvbox.osc.util.DynamicCodeTest", str(temporary / "data")],
                                cwd=ROOT, text=True, capture_output=True)
        print(result.stdout, end="")
        if result.returncode:
            print(result.stderr, end="")
            raise SystemExit(result.returncode)
        print("Expected failure-path diagnostics captured; Android, HTTP and ART are test doubles, not device validation.")
        helper = (ROOT / "app/src/main/java/com/github/tvbox/osc/util/DynamicCodeFiles.java").read_text(encoding="utf-8")
        markers = ["File.createTempFile(", "new FileOutputStream(staging", "staging.setReadOnly()", "output.write(", "verifyMd5(staging", "staging.renameTo(published)"]
        offsets = [helper.index(marker) for marker in markers]
        assert offsets == sorted(offsets), "FD-first read-only publication order changed"
        print("PASS source guard: private stage -> writable FD -> read-only path -> write/verify -> rename")


if __name__ == "__main__":
    run()
