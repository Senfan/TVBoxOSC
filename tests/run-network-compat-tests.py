#!/usr/bin/env python3
"""Standalone JDK 17 network migration regression (no Gradle or device).

First run: python3 -I tools/check-network-compat.py --okgo-aar /.../okgo-3.0.4.aar
Then: python3 -I tests/run-network-compat-tests.py --paths /.../paths.json --java-home /.../jdk
The tests run real pinned OkGo/Sardine/OkHttp/Okio binaries. Small Android and
app-lifecycle shims are test-only; neither networking nor these libraries are
mocked. All traffic goes to in-process loopback fixtures, not user sources.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
    "androidx/annotation/NonNull.java": "package androidx.annotation; public @interface NonNull {}",
    "org/jetbrains/annotations/NotNull.java": "package org.jetbrains.annotations; public @interface NotNull {}",
    "android/content/Context.java": "package android.content; public class Context {}",
    "android/app/Application.java": "package android.app; public class Application extends android.content.Context {}",
    "android/os/Looper.java": "package android.os; public class Looper { public static Looper getMainLooper() { return new Looper(); } }",
    "android/os/Handler.java": "package android.os; public class Handler { public Handler(Looper l) {} public boolean post(Runnable r) { r.run(); return true; } }",
    "android/os/SystemClock.java": "package android.os; public class SystemClock { public static long elapsedRealtime() { return System.nanoTime()/1000000; } }",
    "android/os/Build.java": "package android.os; public class Build { public static String MODEL=\"JVM\", ID=\"test\"; public static class VERSION { public static int SDK_INT=34; public static String RELEASE=\"14\", CODENAME=\"REL\"; } }",
    "android/util/Log.java": "package android.util; public class Log { public static int w(String t,String m){return 0;} public static int d(String t,String m){return 0;} public static int e(String t,String m){return 0;} public static int i(String t,String m){return 0;} public static int v(String t,String m){return 0;} }",
    "android/text/TextUtils.java": """package android.text; public class TextUtils {
      public static boolean isEmpty(CharSequence s) { return s==null || s.length()==0; }
      public static String[] split(String s,String p) { return s.isEmpty()?new String[0]:s.split(p); }
      public static String join(CharSequence delimiter, Iterable<?> values) {
        StringBuilder out=new StringBuilder(); for(Object value:values) {
          if(out.length()>0) out.append(delimiter); out.append(value); } return out.toString(); }
    }""",
    "com/github/tvbox/osc/base/App.java": """package com.github.tvbox.osc.base;
      public class App extends android.app.Application {
        private static final App INSTANCE=new App(); public static App getInstance(){return INSTANCE;}
        public java.io.File getCacheDir(){return new java.io.File(System.getProperty("test.cache"));}
      }""",
    "com/orhanobut/hawk/Hawk.java": "package com.orhanobut.hawk; public class Hawk { public static <T> T get(String key,T fallback){return fallback;} }",
    "com/github/tvbox/osc/util/HawkConfig.java": "package com.github.tvbox.osc.util; public class HawkConfig { public static final String DEBUG_OPEN=\"debug\", DOH_URL=\"doh\"; }",
    "xyz/doikki/videoplayer/exo/ExoMediaSourceHelper.java": """package xyz.doikki.videoplayer.exo;
      public class ExoMediaSourceHelper {
        private static final ExoMediaSourceHelper INSTANCE=new ExoMediaSourceHelper();
        public okhttp3.OkHttpClient client;
        public static ExoMediaSourceHelper getInstance(android.content.Context context){return INSTANCE;}
        public void setOkClient(okhttp3.OkHttpClient value){client=value;}
      }""",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--paths", required=True, type=Path)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--kotlin-jar", type=Path, help="Override stdlib to match the resolved app dependency")
    args = parser.parse_args()
    if not args.java_home:
        parser.error("--java-home or JAVA_HOME is required")
    paths = json.loads(args.paths.read_text())
    if args.kotlin_jar:
        paths["kotlin"] = str(args.kotlin_jar)
    for required in ("okhttp", "okio", "kotlin", "okgo", "sardine", "simplexml", "xpp3", "json"):
        if required not in paths or not Path(paths[required]).is_file():
            parser.error(f"Missing {required}: run tools/check-network-compat.py first")
    with tempfile.TemporaryDirectory(prefix="tvbox-network-tests-") as temp:
        temp = Path(temp)
        sources = temp / "sources"
        classes = temp / "classes"
        classes.mkdir()
        stubs = []
        for name, source in STUBS.items():
            file = sources / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(source)
            stubs.append(str(file))
        app = ROOT / "app/src/main/java"
        production = list((app / "okhttp3/dnsoverhttps").glob("*.java")) + [
            app / "com/github/tvbox/osc/util/OkGoHelper.java",
            app / "com/github/tvbox/osc/util/urlhttp/BrotliInterceptor.java",
            app / "com/github/tvbox/osc/util/urlhttp/internal/BrotliSource.java",
        ]
        tests = list((ROOT / "tests/network-compat").glob("*.java"))
        cp = os.pathsep.join(list(paths.values()) + [str(ROOT / "app/libs/dec-0.1.2.jar")])
        subprocess.run([str(args.java_home / "bin/javac"), "--release", "8", "-encoding", "UTF-8",
                        "-cp", cp, "-d", str(classes)] + stubs + list(map(str, production + tests)), check=True)
        subprocess.run([str(args.java_home / "bin/java"), "-ea", "-Djava.net.preferIPv4Stack=true",
                        "-Dtest.cache=" + str(temp / "cache"), "-cp", str(classes) + os.pathsep + cp,
                        "okhttp3.dnsoverhttps.NetworkCompatTest"], check=True, timeout=90)


if __name__ == "__main__":
    main()
