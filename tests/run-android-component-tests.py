#!/usr/bin/env python3
"""JDK17 contract tests with Android doubles plus component wiring regressions; no device/Gradle."""
from pathlib import Path
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java'
JDK = Path(os.environ.get('JAVA_HOME', '/Users/bytedance/Library/Java/JavaVirtualMachines/corretto-17-tvbox.jdk/Contents/Home'))

STUBS = {
'android/Manifest.java': 'package android; public class Manifest { public static class permission { public static final String POST_NOTIFICATIONS="notify"; }}',
'android/os/Build.java': 'package android.os; public class Build { public static class VERSION { public static int SDK_INT=34; } public static class VERSION_CODES { public static final int N=24,O=26,Q=29,R=30; }}',
'android/os/IBinder.java': 'package android.os; public interface IBinder {}',
'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }',
'android/os/Handler.java': 'package android.os; public class Handler { public Handler(Looper l){} public void post(Runnable r){r.run();} }',
'android/os/Environment.java': 'package android.os; import java.io.File; public class Environment { public static final String MEDIA_MOUNTED="mounted",MEDIA_MOUNTED_READ_ONLY="mounted_ro"; public static File external; public static File getExternalStorageDirectory(){return external;} }',
'android/os/Process.java': 'package android.os; public class Process { public static int myPid(){return 1;} public static int myUid(){return 2;} }',
'android/os/storage/StorageVolume.java': '''package android.os.storage; import java.io.File; public class StorageVolume {
 public static File root; public static String state=android.os.Environment.MEDIA_MOUNTED;
 public String getState(){return state;} public File getDirectory(){return root;} }''',
'android/os/storage/StorageManager.java': '''package android.os.storage; import java.io.File; public class StorageManager {
 public StorageVolume getStorageVolume(File f){try {File root=StorageVolume.root==null?null:StorageVolume.root.getCanonicalFile();File x=f.getCanonicalFile();return root!=null&&x.getPath().startsWith(root.getPath()+File.separator)?new StorageVolume():null;}catch(Exception e){return null;}} }''',
'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager { public static final int PERMISSION_GRANTED=0; }',
'android/content/pm/ServiceInfo.java': 'package android.content.pm; public class ServiceInfo { public static final int FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK=2; }',
'android/database/Cursor.java': 'package android.database; public interface Cursor extends AutoCloseable { boolean moveToFirst(); String getString(int n); void close(); }',
'android/provider/OpenableColumns.java': 'package android.provider; public class OpenableColumns { public static final String DISPLAY_NAME="name"; }',
'android/content/ContentResolver.java': 'package android.content; import android.database.Cursor; import android.net.Uri; import java.io.*; public class ContentResolver { public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;} public InputStream openInputStream(Uri u) throws IOException {return null;} }',
'android/content/Context.java': '''package android.content;
import java.io.File; public class Context {
 public static File root; public static int stopped,uriPermission=android.content.pm.PackageManager.PERMISSION_GRANTED;
 public String getPackageName(){return "test.app";} public Context getApplicationContext(){return this;}
 public File getFilesDir(){return new File(root,"files");} public File getCacheDir(){return new File(root,"cache");}
 public ContentResolver getContentResolver(){return new ContentResolver();}
 public int checkUriPermission(android.net.Uri u,int pid,int uid,int flags){return uriPermission;}
 public boolean stopService(Intent i){stopped++;return true;}
 public <T> T getSystemService(Class<T> type){try{return type.newInstance();}catch(Exception e){throw new RuntimeException(e);}}
}''',
'android/content/ActivityNotFoundException.java': 'package android.content; public class ActivityNotFoundException extends RuntimeException {}',
'android/net/Uri.java': '''package android.net; public class Uri {
 final String raw; Uri(String raw){this.raw=raw;} public static Uri parse(String s){return new Uri(s);}
 public String getScheme(){int i=raw.indexOf(":");return i<0?null:raw.substring(0,i);}
 public String getPath(){return getScheme()==null?raw:java.net.URI.create(raw).getPath();}
 public String getAuthority(){return getScheme()==null?null:java.net.URI.create(raw).getAuthority();}
 public String toString(){return raw;}
}''',
'android/content/ClipData.java': '''package android.content; import android.net.Uri; import java.util.*;
public class ClipData { public final List<Item> items=new ArrayList<>(); public static class Item {public final Uri uri;public Item(Uri u){uri=u;}}
 public static ClipData newRawUri(String n,Uri u){ClipData c=new ClipData();c.addItem(new Item(u));return c;} public void addItem(Item i){items.add(i);} }''',
'android/content/Intent.java': '''package android.content; import java.util.*; import android.net.Uri;
public class Intent {
 public static final int FLAG_ACTIVITY_CLEAR_TOP=1,FLAG_ACTIVITY_SINGLE_TOP=2,FLAG_GRANT_READ_URI_PERMISSION=4;
 public static final String ACTION_VIEW="view",CATEGORY_DEFAULT="default";
 public String action,pkg; public int flags; public ClipData clip; public Map<String,Object> extras=new HashMap<>();
 public Intent(){} public Intent(Context c,Class<?> t){} public Intent(String a){action=a;}
 public Intent putExtra(String k,String v){extras.put(k,v);return this;} public Intent putExtra(String k,int v){extras.put(k,v);return this;}
 public String getStringExtra(String k){return (String)extras.get(k);} public Intent setAction(String a){action=a;return this;}
 public Intent setPackage(String p){pkg=p;return this;} public Intent addFlags(int f){flags|=f;return this;}
 public Intent addCategory(String c){return this;} public Intent setDataAndType(Uri u,String t){return this;}
 public Intent setClipData(ClipData c){clip=c;return this;} public ClipData getClipData(){return clip;}
 public static Intent createChooser(Intent i,String t){return i;}
}''',
'android/app/Activity.java': 'package android.app; import android.content.*; public class Activity extends Context { public boolean isFinishing(){return false;} public boolean isDestroyed(){return false;} public void startActivity(Intent i){} }',
'android/app/Notification.java': 'package android.app; public class Notification {}',
'android/app/NotificationChannel.java': 'package android.app; public class NotificationChannel { public NotificationChannel(String i,String n,int p){} public void setSound(Object a,Object b){} }',
'android/app/NotificationManager.java': 'package android.app; public class NotificationManager { public static final int IMPORTANCE_LOW=2; public void createNotificationChannel(NotificationChannel c){} }',
'android/app/PendingIntent.java': '''package android.app; import android.content.*; import java.util.*;
public class PendingIntent { public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;
 public static List<Intent> broadcasts=new ArrayList<>(); public static List<Integer> broadcastFlags=new ArrayList<>();
 public static PendingIntent getBroadcast(Context c,int r,Intent i,int f){broadcasts.add(i);broadcastFlags.add(f);return new PendingIntent();}
 public static PendingIntent getActivity(Context c,int r,Intent i,int f){return new PendingIntent();} }''',
'android/app/Service.java': '''package android.app; import android.content.*; import android.os.*;
public class Service extends Context { public static final int START_NOT_STICKY=2; public static RuntimeException promotionFailure;
 public int promoted,stops; public void onCreate(){} public void onDestroy(){} public int onStartCommand(Intent i,int f,int id){return 0;}
 public IBinder onBind(Intent i){return null;} public void startForeground(int n,Notification o){if(promotionFailure!=null)throw promotionFailure;promoted++;}
 public void startForeground(int n,Notification o,int t){if(t!=2)throw new AssertionError("wrong FGS type");startForeground(n,o);}
 public void stopForeground(boolean remove){} public void stopSelf(){stops++;}
}''',
'android/util/Log.java': 'package android.util; public class Log {public static int e(String t,String m,Throwable x){return 0;} public static int w(String t,String m,Throwable x){return 0;} public static int w(String t,String m){return 0;}}',
'android/widget/Toast.java': 'package android.widget; import android.content.Context; public class Toast { public static final int LENGTH_SHORT=0; public static Toast makeText(Context c,String s,int i){return new Toast();} public void show(){} }',
'android/widget/RemoteViews.java': 'package android.widget; import android.app.PendingIntent; public class RemoteViews {public RemoteViews(String p,int l){} public void setTextViewText(int i,String t){} public void setImageViewResource(int i,int r){} public void setOnClickPendingIntent(int i,PendingIntent p){} }',
'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
'androidx/core/content/ContextCompat.java': '''package androidx.core.content; import android.content.*;
public class ContextCompat {public static int permission=0,starts; public static RuntimeException startFailure;
 public static void startForegroundService(Context c,Intent i){if(startFailure!=null)throw startFailure;starts++;}
 public static int checkSelfPermission(Context c,String p){return permission;} }''',
'androidx/core/content/FileProvider.java': 'package androidx.core.content; import android.content.Context; import android.net.Uri; import java.io.File; public class FileProvider {public static Uri getUriForFile(Context c,String a,File f){return Uri.parse("content://"+a+"/shared_media/"+f.getName());}}',
'androidx/core/app/NotificationCompat.java': '''package androidx.core.app; import android.app.*; import android.content.Context; import android.widget.RemoteViews;
public class NotificationCompat {public static class Builder {public Builder(Context c,String s){} public Builder setSmallIcon(int i){return this;}
 public Builder setCustomContentView(RemoteViews v){return this;} public Builder setContentIntent(PendingIntent p){return this;}
 public Builder setOnlyAlertOnce(boolean b){return this;} public Builder setOngoing(boolean b){return this;} public Notification build(){return new Notification();}}}''',
'androidx/core/app/NotificationManagerCompat.java': '''package androidx.core.app; import android.content.Context; import android.app.Notification;
public class NotificationManagerCompat {public static int notifications;public static boolean enabled=true;public static SecurityException failure;
 public static NotificationManagerCompat from(Context c){return new NotificationManagerCompat();} public boolean areNotificationsEnabled(){return enabled;}
 public void notify(int id,Notification n){if(failure!=null)throw failure;notifications++;}}''',
'org/greenrobot/eventbus/EventBus.java': 'package org.greenrobot.eventbus; public class EventBus {public static EventBus getDefault(){return new EventBus();}public void register(Object o){}public void unregister(Object o){}}',
'org/greenrobot/eventbus/Subscribe.java': 'package org.greenrobot.eventbus; public @interface Subscribe {ThreadMode threadMode();}',
'org/greenrobot/eventbus/ThreadMode.java': 'package org.greenrobot.eventbus; public enum ThreadMode {MAIN}',
'com/github/tvbox/osc/R.java': 'package com.github.tvbox.osc; public class R {public static class layout {public static final int notification_player=1;} public static class id {public static final int tv_title=1,tv_subtitle=2,iv_play_pause=3,iv_previous=4,iv_next=5;}public static class drawable {public static final int ic_notify_pause=1,ic_notify_play=2,app_icon=3;}}',
'com/github/tvbox/osc/base/App.java': 'package com.github.tvbox.osc.base; public class App extends android.content.Context {private static final App app=new App();public static App getInstance(){return app;}}',
'com/github/tvbox/osc/event/RefreshEvent.java': 'package com.github.tvbox.osc.event; public class RefreshEvent {public static final int TYPE_REFRESH_NOTIFY=17;public int type=17;public Object obj;}',
'com/github/tvbox/osc/player/MyVideoView.java': 'package com.github.tvbox.osc.player; public class MyVideoView {public boolean playing=true;public Object media=new Object();public boolean isPlaying(){return playing;}public Object getMediaPlayer(){return media;} }',
'com/github/tvbox/osc/ui/activity/DetailActivity.java': '''package com.github.tvbox.osc.ui.activity;
import com.github.tvbox.osc.player.MyVideoView; public class DetailActivity extends android.app.Activity {
 public static final int BROADCAST_ACTION_PREV=0,BROADCAST_ACTION_PLAYPAUSE=1,BROADCAST_ACTION_NEXT=2;
 public final String action="test.app.vod.detail."+java.util.UUID.randomUUID(); public MyVideoView player=new MyVideoView();
 public boolean visible=true,allowed=true;public int stopped,failures;
 public String getPlaybackControlAction(){return action;} public String getBackgroundPlaybackTitle(){return "title without episode";}
 public boolean isBackgroundPlaybackOwner(MyVideoView p){return allowed&&p!=null&&player==p;}
 public boolean canStartBackgroundPlayback(MyVideoView p){return visible&&isBackgroundPlaybackOwner(p)&&p.media!=null&&p.playing;}
 public void onBackgroundPlaybackStopped(String a,boolean f){stopped++;if(f)failures++;}
}''',
'com/github/tvbox/osc/util/ComponentTest.java': '''package com.github.tvbox.osc.util;
import java.io.*;import java.nio.file.*;import android.content.*;import android.app.*;import android.net.Uri;
import androidx.core.content.ContextCompat;import androidx.core.app.NotificationManagerCompat;
import com.github.tvbox.osc.server.PlayService;import com.github.tvbox.osc.ui.activity.DetailActivity;import com.github.tvbox.osc.event.RefreshEvent;
public class ComponentTest {
 static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 interface IOCall {void run() throws Exception;} static void fails(IOCall call,String message)throws Exception{checks++;try{call.run();throw new AssertionError(message);}catch(IOException expected){}}
 static Intent command(DetailActivity a){return new Intent().putExtra("playback_session",a.action);}
 static PlayService service(){PlayService s=new PlayService();s.onCreate();return s;}
 public static void main(String[] args)throws Exception {
  Context.root=new File(args[0]);Context.root.mkdirs();android.os.Environment.external=new File(Context.root.getParentFile(),"primary");android.os.Environment.external.mkdirs();
  DetailActivity a=new DetailActivity();
  check(!PlayService.start(a,null,a.action),"null player rejected");a.visible=false;
  check(!PlayService.start(a,a.player,a.action),"background activity rejected");a.visible=true;a.allowed=false;
  check(!PlayService.start(a,a.player,a.action),"disabled user setting rejected");a.allowed=true;a.player.playing=false;
  check(!PlayService.start(a,a.player,a.action),"paused player rejected");a.player.playing=true;a.player.media=null;
  check(!PlayService.start(a,a.player,a.action),"released player rejected");a.player.media=new Object();
  check(!PlayService.start(a,a.player,"wrong-session"),"mismatched action rejected");
  ContextCompat.startFailure=new IllegalStateException("background start denied");
  check(!PlayService.start(a,a.player,a.action)&&a.failures==1&&!PlayService.owns(a.action,a.player),"start denial clears refs");ContextCompat.startFailure=null;
  check(PlayService.start(a,a.player,a.action),"valid start");int starts=ContextCompat.starts;
  check(PlayService.start(a,a.player,a.action)&&ContextCompat.starts==starts,"idempotent start");
  PlayService s=service();ContextCompat.permission=-1;s.onStartCommand(command(a),0,1);
  check(s.promoted==1,"notification denial still promotes legal FGS");s.refresh(new RefreshEvent());
  check(NotificationManagerCompat.notifications==0,"notification denial skips notify");
  check(PendingIntent.broadcasts.size()==3,"three notification controls");
  for(int i=0;i<3;i++){Intent pi=PendingIntent.broadcasts.get(i);check(a.action.equals(pi.action)&&"test.app".equals(pi.pkg),"scoped routing");check((PendingIntent.broadcastFlags.get(i)&PendingIntent.FLAG_IMMUTABLE)!=0,"immutable");}
  ContextCompat.permission=0;s.refresh(new RefreshEvent());check(NotificationManagerCompat.notifications==1,"notification refresh");
  NotificationManagerCompat.failure=new SecurityException("revoked");s.refresh(new RefreshEvent());NotificationManagerCompat.failure=null;
  check(PlayService.owns(a.action,a.player),"revocation does not terminate legal playback");
  PlayService.stop("different-session");check(PlayService.owns(a.action,a.player),"stale activity cannot stop current");
  PlayService.stop(a.action);check(!PlayService.owns(a.action,a.player),"explicit stop clears refs");s.onDestroy();
  PlayService empty=service();check(empty.onStartCommand(null,0,1)==Service.START_NOT_STICKY&&empty.promoted==0&&empty.stops==1,"null restart stops safely");empty.onDestroy();
  PlayService.start(a,a.player,a.action);a.player.playing=false;PlayService paused=service();paused.onStartCommand(command(a),0,1);
  check(paused.promoted==0&&!PlayService.owns(a.action,a.player),"queued start never resumes paused player");paused.onDestroy();a.player.playing=true;
  PlayService.start(a,a.player,a.action);Service.promotionFailure=new SecurityException("missing permission");PlayService denied=service();denied.onStartCommand(command(a),0,1);
  check(denied.promoted==0&&!PlayService.owns(a.action,a.player)&&a.failures==2,"promotion denial cleans up");Service.promotionFailure=null;denied.onDestroy();
  PlayService.start(a,a.player,a.action);PlayService live=service();live.onStartCommand(command(a),0,1);a.allowed=false;live.refresh(new RefreshEvent());
  check(live.stops==1&&!PlayService.owns(a.action,a.player),"destroyed owner stops service");live.onDestroy();a.allowed=true;
  PlayService.start(a,a.player,a.action);PlayService destroy=service();destroy.onStartCommand(command(a),0,1);destroy.onDestroy();check(!PlayService.owns(a.action,a.player),"service destruction clears refs");
  check(".srt".equals(MediaFileAccess.subtitleExtension("A.SRT")),"subtitle extension normalized");fails(()->MediaFileAccess.subtitleExtension("config.json"),"non subtitle rejected");
  File copies=new File(Context.root,"copies");File f=MediaFileAccess.copy(new ByteArrayInputStream(new byte[]{1,2,3}),copies,".srt",3);check(f.length()==3,"exact limit allowed");
  fails(()->MediaFileAccess.copy(new ByteArrayInputStream(new byte[]{1,2,3,4}),copies,".srt",3),"oversized copy rejected");check(copies.list().length==1,"partial file removed");
  fails(()->MediaFileAccess.copy(new ByteArrayInputStream(new byte[0]),copies,".srt",3),"empty copy rejected");
  fails(()->MediaFileAccess.copy(new InputStream(){public int read()throws IOException{throw new IOException("broken stream");}},copies,".srt",3),"broken stream rejected");check(copies.list().length==1,"broken stream cleanup");
  Context c=new Context();File workspace=new File(c.getFilesDir(),"config-workspace");workspace.mkdirs();File video=new File(workspace,"video.mp4");Files.write(video.toPath(),new byte[]{1});
  Uri shared=MediaFileAccess.readableVideoUri(c,video.toString());check("content".equals(shared.getScheme()),"local media gets provider URI");
  File backup=new File(c.getFilesDir(),"private-backups/leak.mp4");backup.getParentFile().mkdirs();Files.write(backup.toPath(),new byte[]{1});
  fails(()->MediaFileAccess.readableVideoUri(c,backup.toString()),"backup sharing denied");
  Files.createSymbolicLink(new File(workspace,"escape.mp4").toPath(),backup.toPath());fails(()->MediaFileAccess.readableVideoUri(c,new File(workspace,"escape.mp4").toString()),"symlink escape denied");
  fails(()->MediaFileAccess.readableVideoUri(c,"relative.mp4"),"relative paths denied");
  File usb=new File(Context.root.getParentFile(),"ABCD-1234");usb.mkdirs();android.os.storage.StorageVolume.root=usb;
  File usbVideo=new File(usb,"Movies/movie.mkv");usbVideo.getParentFile().mkdirs();Files.write(usbVideo.toPath(),new byte[]{1,2});
  check("content".equals(MediaFileAccess.readableVideoUri(c,usbVideo.toString()).getScheme()),"readable secondary volume is staged");
  android.os.storage.StorageVolume.state="unmounted";fails(()->MediaFileAccess.readableVideoUri(c,usbVideo.toString()),"unmounted secondary volume rejected");
  android.os.storage.StorageVolume.state=android.os.Environment.MEDIA_MOUNTED;
  File androidData=new File(usb,"Android/data/other/video.mp4");androidData.getParentFile().mkdirs();Files.write(androidData.toPath(),new byte[]{1});
  fails(()->MediaFileAccess.readableVideoUri(c,androidData.toString()),"other app private storage rejected");
  check("smb://server/file.mkv".equals(MediaFileAccess.readableVideoUri(c,"smb://server/file.mkv").toString()),"custom network protocol preserved");
  Context.uriPermission=-1;fails(()->MediaFileAccess.readableVideoUri(c,"content://provider/video"),"content URI without grant rejected");
  Context.uriPermission=android.content.pm.PackageManager.PERMISSION_GRANTED;
  check("content://provider/video".equals(MediaFileAccess.readableVideoUri(c,"content://provider/video").toString()),"granted content URI preserved");
  MediaFileAccess.PendingExternalPlayback mailbox=new MediaFileAccess.PendingExternalPlayback();int[] launches={0};
  long first=mailbox.begin();mailbox.complete(first,7,()->launches[0]++);mailbox.consume(7,false);check(launches[0]==0,"paused result retained");
  mailbox.consume(7,true);mailbox.consume(7,true);check(launches[0]==1,"resumed result consumed exactly once");
  long stale=mailbox.begin();long current=mailbox.begin();mailbox.complete(stale,8,()->launches[0]++);mailbox.consume(8,true);check(launches[0]==1,"old request ignored");
  mailbox.complete(current,8,()->launches[0]++);mailbox.consume(9,true);check(launches[0]==1,"old generation cancelled");
  long destroyed=mailbox.begin();mailbox.complete(destroyed,10,()->launches[0]++);mailbox.cancel();mailbox.consume(10,true);check(launches[0]==1,"destroyed owner cannot launch");
  Intent grant=new Intent();MediaFileAccess.grantRead(grant,shared);MediaFileAccess.grantRead(grant,Uri.parse("content://subtitle/file"));check(grant.clip.items.size()==2&&(grant.flags&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0,"video and subtitle have concrete read grants");
  System.out.println("ComponentTest: "+checks+" assertions passed (Android contract doubles)");
 }
}''',
}


def wiring_checks():
    ns = {'a': 'http://schemas.android.com/apk/res/android'}
    manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
    permissions = {e.get('{%s}name' % ns['a']): e for e in manifest.findall('uses-permission')}
    for p in ('FOREGROUND_SERVICE_MEDIA_PLAYBACK', 'POST_NOTIFICATIONS'):
        assert 'android.permission.' + p in permissions
    assert permissions['android.permission.READ_EXTERNAL_STORAGE'].get('{%s}maxSdkVersion' % ns['a']) == '32'
    assert permissions['android.permission.WRITE_EXTERNAL_STORAGE'].get('{%s}maxSdkVersion' % ns['a']) == '29'
    paths = list(ET.parse(ROOT / 'app/src/main/res/xml/file_paths.xml').getroot())
    assert {(e.tag, e.get('path')) for e in paths} == {('cache-path', 'updates/'), ('cache-path', 'shared-media/')}
    sources = {}
    for name in ('DetailActivity', 'PlayActivity'):
        text = (JAVA / ('com/github/tvbox/osc/ui/activity/' + name + '.java')).read_text()
        sources[name] = text
        assert 'ContextCompat.RECEIVER_NOT_EXPORTED' in text
        assert 'new IntentFilter(getPlaybackControlAction())' in text
        assert 'new Intent(getPlaybackControlAction()).setPackage(getPackageName())' in text
        assert 'PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE' in text
        assert 'java.util.UUID.randomUUID()' in text
        if name == 'PlayActivity':
            assert 'registerForActivityResult(' in text
        assert 'new ChooserDialog(' not in text and 'new Intent(BROADCAST_ACTION)' not in text
    detail = sources['DetailActivity']
    assert 'registerReceiver(mHomeKeyReceiver, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))' in detail
    assert 'activityDestroyed = true;\n        playServerSwitch(false);\n        registerActionReceiver(false);' in detail
    assert 'unregisterActionReceiver();\n        super.onDestroy();' in sources['PlayActivity']
    assert '".vod.detail."' in detail and '".vod.play."' in sources['PlayActivity']
    fragment = (JAVA / 'com/github/tvbox/osc/ui/fragment/PlayFragment.java').read_text()
    assert 'keepPlaybackInBackground(mVideoView)' in fragment and 'onPreviewPlayerDestroyed()' in fragment
    for text in (fragment, sources['PlayActivity']):
        assert 'token != fallbackController.getGeneration()' in text
        assert 'MediaFileAccess.prepareExternal(' in text and 'MediaFileAccess.openVideo(' in text
        assert 'pendingExternalPlayback.complete(' in text and 'pendingExternalPlayback.consume(' in text
        assert 'pendingExternalPlayback.cancel();' in text
    home = (JAVA / 'com/github/tvbox/osc/ui/activity/HomeActivity.java').read_text()
    assert 'PlayService' not in home and 'MANAGE_EXTERNAL_STORAGE' not in home
    assert 'requestPermissions' not in home
    for name in ('MXPlayer', 'Kodi', 'ReexPlayer'):
        text = (JAVA / ('com/github/tvbox/osc/player/thirdparty/' + name + '.java')).read_text()
        assert 'MediaFileAccess.grantRead(intent, Uri.parse(url))' in text
        assert 'MediaFileAccess.grantRead(intent, Uri.parse(subtitle))' in text
    print('Static component wiring: PASS (receiver/PI/session/SAF/FGS/permissions/provider/third-party grants)')


if __name__ == '__main__':
    version = subprocess.check_output([str(JDK / 'bin/java'), '-version'], stderr=subprocess.STDOUT).decode()
    if 'version "17' not in version:
        raise SystemExit('Set JAVA_HOME to JDK17; no Gradle/device tests are run.')
    wiring_checks()
    with tempfile.TemporaryDirectory(prefix='tvbox-components-') as temp:
        root = Path(temp)
        sources = []
        for path, content in STUBS.items():
            destination = root / 'src' / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(content)
            sources.append(str(destination))
        for path in ('com/github/tvbox/osc/server/PlayService.java', 'com/github/tvbox/osc/util/MediaFileAccess.java'):
            sources.append(str(JAVA / path))
        classes = root / 'classes'
        classes.mkdir()
        subprocess.run([str(JDK / 'bin/javac'), '--release', '8', '-encoding', 'UTF-8', '-d', str(classes), *sources], check=True)
        subprocess.run([str(JDK / 'bin/java'), '-cp', str(classes), 'com.github.tvbox.osc.util.ComponentTest', str(root / 'data')], check=True)
