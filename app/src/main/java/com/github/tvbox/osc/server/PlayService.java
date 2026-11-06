package com.github.tvbox.osc.server;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.ui.activity.DetailActivity;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.lang.ref.WeakReference;

/** Foreground ownership of an existing Detail preview, never an HTTP keep-alive service. */
public class PlayService extends Service {
    private static final String TAG = "PlayService";
    private static final String CHANNEL_ID = "MyChannelId";
    private static final int NOTIFICATION_ID = 1;
    private static final String EXTRA_SESSION = "playback_session";

    // Neither the process nor the service owns the Activity/View lifetime.
    private static final class Session {
        final String action;
        final WeakReference<DetailActivity> owner;
        final WeakReference<MyVideoView> player;

        Session(DetailActivity owner, MyVideoView player, String action) {
            this.action = action;
            this.owner = new WeakReference<>(owner);
            this.player = new WeakReference<>(player);
        }

        boolean valid() {
            DetailActivity activity = owner.get();
            return activity != null && activity.isBackgroundPlaybackOwner(player.get());
        }
    }

    // All entry points and EventBus callbacks run on the main thread.
    private static Session pendingSession;
    private Session session;
    private boolean foreground;

    public static boolean start(DetailActivity owner, MyVideoView player, String action) {
        if (!owner.canStartBackgroundPlayback(player)
                || !owner.getPlaybackControlAction().equals(action)) return false;
        if (owns(action, player)) return true;
        if (pendingSession != null) stop(pendingSession.action);
        Session next = new Session(owner, player, action);
        pendingSession = next;
        try {
            ContextCompat.startForegroundService(owner,
                    new Intent(owner, PlayService.class).putExtra(EXTRA_SESSION, action));
            return true;
        } catch (SecurityException | IllegalStateException e) {
            // Includes ForegroundServiceStartNotAllowedException on API 31+.
            Log.e(TAG, "Media foreground service start rejected", e);
            clear(next, true);
            return false;
        }
    }

    public static boolean owns(String action, MyVideoView player) {
        return pendingSession != null && pendingSession.action.equals(action)
                && player != null && pendingSession.player.get() == player && pendingSession.valid();
    }

    public static void stop(String action) {
        Session current = pendingSession;
        if (current == null || !current.action.equals(action)) return;
        clear(current, false);
        App.getInstance().stopService(new Intent(App.getInstance(), PlayService.class));
    }

    private static void clear(Session current, boolean failed) {
        if (pendingSession == current) pendingSession = null;
        DetailActivity activity = current.owner.get();
        current.owner.clear();
        current.player.clear();
        if (activity != null) activity.onBackgroundPlaybackStopped(current.action, failed);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventBus.getDefault().register(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "后台播放",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Session requested = pendingSession;
        if (intent == null || requested == null || !requested.valid()) {
            if (requested != null) clear(requested, false);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // A queued command belonging to an older Activity must not steal a newer session.
        if (!requested.action.equals(intent.getStringExtra(EXTRA_SESSION))) return START_NOT_STICKY;
        MyVideoView player = requested.player.get();
        if (player == null || player.getMediaPlayer() == null || !player.isPlaying()) {
            clear(requested, false);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        session = requested;
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
            // Do not start/resume here: the user may have paused while the command was queued.
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "Media foreground promotion rejected", e);
            clear(requested, true);
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type != RefreshEvent.TYPE_REFRESH_NOTIFY || !foreground || session == null) return;
        if (session != pendingSession || !session.valid()) {
            if (session == pendingSession) clear(session, false);
            stopForeground(true);
            stopSelf();
            return;
        }
        // POST_NOTIFICATIONS is not required to start a FGS. When denied, skip ordinary
        // notify() calls; Android retains the foreground-service entry in Task Manager.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManagerCompat manager = NotificationManagerCompat.from(this);
        if (!manager.areNotificationsEnabled()) return;
        try {
            manager.notify(NOTIFICATION_ID, buildNotification());
        } catch (SecurityException e) {
            // Permission may be revoked between the check and notify; playback remains legal.
            Log.w(TAG, "Playback notification permission revoked", e);
        }
    }

    private Notification buildNotification() {
        DetailActivity owner = session.owner.get();
        MyVideoView player = session.player.get();
        String title = owner == null ? "" : owner.getBackgroundPlaybackTitle();
        String[] parts = (title == null ? "" : title).split("&&", 2);
        RemoteViews remoteViews = new RemoteViews(getPackageName(), R.layout.notification_player);
        remoteViews.setTextViewText(R.id.tv_title, parts[0]);
        remoteViews.setTextViewText(R.id.tv_subtitle, parts.length > 1 ? "正在播放: " + parts[1] : "");
        remoteViews.setImageViewResource(R.id.iv_play_pause,
                player != null && player.isPlaying() ? R.drawable.ic_notify_pause : R.drawable.ic_notify_play);
        remoteViews.setOnClickPendingIntent(R.id.iv_previous, getPendingIntent(DetailActivity.BROADCAST_ACTION_PREV));
        remoteViews.setOnClickPendingIntent(R.id.iv_play_pause, getPendingIntent(DetailActivity.BROADCAST_ACTION_PLAYPAUSE));
        remoteViews.setOnClickPendingIntent(R.id.iv_next, getPendingIntent(DetailActivity.BROADCAST_ACTION_NEXT));
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setCustomContentView(remoteViews)
                .setContentIntent(getPendingIntentActivity())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private PendingIntent getPendingIntentActivity() {
        Intent intent = new Intent(this, DetailActivity.class)
                .setAction(session.action)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent getPendingIntent(int actionCode) {
        return PendingIntent.getBroadcast(this, actionCode,
                new Intent(session.action).setPackage(getPackageName()).putExtra("action", actionCode),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onDestroy() {
        foreground = false;
        EventBus.getDefault().unregister(this);
        if (session != null && session == pendingSession) clear(session, false);
        session = null;
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
