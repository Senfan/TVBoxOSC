#!/usr/bin/env python3
"""JDK17-only workspace tests. Run with python3 -I; no Gradle/device/network required."""
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/com/github/tvbox/osc"
JDK = Path(os.environ.get("JAVA_HOME", "/Users/bytedance/Library/Java/JavaVirtualMachines/corretto-17-tvbox.jdk/Contents/Home"))
STUBS = {
    "com/github/tvbox/osc/base/App.java": '''package com.github.tvbox.osc.base;
public class App { private static App instance; private final java.io.File root;
 public App(java.io.File f){root=f;f.mkdirs();instance=this;} public static App getInstance(){return instance;}
 public java.io.File getFilesDir(){return root;} }''',
    "com/github/tvbox/osc/server/ControlManager.java": '''package com.github.tvbox.osc.server;
public class ControlManager { public static ControlManager get(){return new ControlManager();}
 public String getAddress(boolean local){return "http://127.0.0.1:9978/";} }''',
    "com/github/tvbox/osc/util/MD5.java": '''package com.github.tvbox.osc.util;
public class MD5 { public static String string2MD5(String text){try {
 byte[] bytes=java.security.MessageDigest.getInstance("MD5").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
 StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();
 }catch(Exception e){throw new AssertionError(e);} } }''',
    "com/github/tvbox/osc/util/LOG.java": '''package com.github.tvbox.osc.util; public class LOG {public static void e(String s){} }''',
    "com/github/tvbox/osc/util/FileUtils.java": '''package com.github.tvbox.osc.util; public class FileUtils {public static boolean isWeekAgo(java.io.File f){return true;} }''',
    "com/github/tvbox/osc/util/LoadConfigCallback.java": '''package com.github.tvbox.osc.util; public interface LoadConfigCallback {void success();void error(String message);}''',
    "com/github/catvod/crawler/JarLoader.java": '''package com.github.catvod.crawler;
public class JarLoader {public boolean loadCandidate(String path,String key,String md5){return load(path);}
public boolean load(String path){try {
 return new String(java.nio.file.Files.readAllBytes(new java.io.File(path).toPath()),java.nio.charset.StandardCharsets.UTF_8).startsWith("good");
 }catch(Exception e){return false;} } }''',
    "okhttp3/ResponseBody.java": '''package okhttp3;
public class ResponseBody implements java.io.Closeable {
 private final byte[] data; public boolean closed; public ResponseBody(String s){data=s.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
 public java.io.InputStream byteStream(){return new java.io.ByteArrayInputStream(data);} public long contentLength(){return data.length;}
 public void close(){closed=true;} }''',
    "okhttp3/Response.java": '''package okhttp3;
public class Response {private final ResponseBody body;private final boolean success;
 public Response(ResponseBody b,boolean s){body=b;success=s;}public ResponseBody body(){return body;}public boolean isSuccessful(){return success;} }''',
    "com/lzy/okgo/model/Response.java": '''package com.lzy.okgo.model;
public class Response<T> {private final T body;public Response(T b){body=b;}public T body(){return body;} }''',
    "com/lzy/okgo/callback/AbsCallback.java": '''package com.lzy.okgo.callback;
public abstract class AbsCallback<T> {public abstract T convertResponse(okhttp3.Response r)throws Throwable;
 public abstract void onSuccess(com.lzy.okgo.model.Response<T> r);public abstract void onError(com.lzy.okgo.model.Response<T> r); }''',
    "com/lzy/okgo/OkGo.java": '''package com.lzy.okgo;
public class OkGo<T> {public static boolean fail;public static okhttp3.Response next;
 public static <T> OkGo<T> get(String url){return new OkGo<>();}public OkGo<T> headers(String a,String b){return this;}
 public void execute(com.lzy.okgo.callback.AbsCallback<T> callback){try {
 if(fail)throw new java.io.IOException("offline");T body=callback.convertResponse(next);
 callback.onSuccess(new com.lzy.okgo.model.Response<T>(body));
 }catch(Throwable e){callback.onError(new com.lzy.okgo.model.Response<T>(null));}} }''',
}


def main():
    version = subprocess.check_output([str(JDK / "bin/java"), "-version"], stderr=subprocess.STDOUT).decode()
    assert 'version "17' in version, "Use an existing JDK17"
    with tempfile.TemporaryDirectory(prefix="tvbox-workspace-tests-") as temp:
        temp = Path(temp)
        sources = []
        for name, text in STUBS.items():
            target = temp / "sources" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
            sources.append(str(target))
        api = (JAVA / "api/ApiConfig.java").read_text()
        # Compile the production callback methods verbatim, not a hand-written behavioral model.
        methods = api[api.index("    public void loadJar("):api.index("    private void parseJson(")]
        harness = temp / "sources/com/github/tvbox/osc/util/MainJarHarness.java"
        harness.write_text('''package com.github.tvbox.osc.util;
import java.io.*;import java.nio.charset.StandardCharsets;
import com.github.tvbox.osc.base.App;import com.github.catvod.crawler.JarLoader;
import com.lzy.okgo.OkGo;import com.lzy.okgo.callback.AbsCallback;import com.lzy.okgo.model.Response;
public class MainJarHarness {private volatile JarLoader jarLoader=new JarLoader();
private final String userAgent="fixture",requestAccept="fixture";private static String jarCache="false";
private static byte[] getImgJar(String body){return java.util.Base64.getDecoder().decode(body);}
''' + methods + "}\n", encoding="utf-8")
        sources.append(str(harness))
        for name in ["WorkspaceIO", "WorkspaceFiles", "DynamicCodeFiles"]:
            sources.append(str(JAVA / f"util/{name}.java"))
        sources += [str(path) for path in (ROOT / "tests/workspace-tests").glob("*.java")]
        classes = temp / "classes"
        classes.mkdir()
        subprocess.run([str(JDK / "bin/javac"), "--release", "8", "-encoding", "UTF-8", "-d", str(classes), *sources], check=True, cwd=ROOT)
        for name in ["WorkspaceTest", "MainJarTest"]:
            subprocess.run([str(JDK / "bin/java"), "-cp", str(classes), f"com.github.tvbox.osc.util.{name}", str(temp / name)], check=True, cwd=ROOT)
    server = (JAVA / "server/RemoteServer.java").read_text()
    assert server.index('get("content-length")') < server.index("session.parseBody(files)"), "bound HTTP before body parsing"
    assert "Environment.getExternalStorageDirectory" not in server
    backup = (JAVA / "ui/dialog/BackupDialog.java").read_text()
    listing = backup[backup.index("    List<String> allBackup()"):backup.index("    void restore(")]
    assert "delete" not in listing and "result.size() > 10" not in backup, "listing must not prune backups"
    assert '"private-backups"' in (JAVA / "util/WorkspaceFiles.java").read_text()
    saf = (JAVA / "util/WorkspaceImport.java").read_text()
    assert "openInputStream(uri)" in saf and "RESULT_OK" in saf and "_data" not in saf.replace("no _data paths", "")
    assert "startPermissionActivity" not in saf
    assert "cache.delete()" not in api and "new FileOutputStream(cache)" not in api
    assert '\"key\".equals(entry.getKey()) || \"name\".equals(entry.getKey())' in api, 'configuration identities are not resource paths'
    for layout in ["dialog_api.xml", "dialog_backup.xml"]:
        ET.parse(ROOT / "app/src/main/res/layout" / layout)
    subprocess.run(["node", "--check", str(ROOT / "app/src/main/res/raw/script.js")], check=True, cwd=ROOT)
    print("PASS source guards: body pre-limit, same private root, no backup pruning, SAF stream/cancel, non-truncating JAR; XML/JS syntax")
    print("Android SAF grants/lifecycle, real NanoHTTPD sockets, ART and device UI are not exercised by these JVM/static tests.")


if __name__ == "__main__":
    main()
