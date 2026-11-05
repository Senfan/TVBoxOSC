package com.github.tvbox.osc.player;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.util.PlayerHelper;

import java.util.Arrays;

import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.BaseVideoView;

public final class PlaybackFallbackController {
    public interface Host {
        boolean isCurrent(String contentKey, String url);
        void onSuccess(int playerType);
        void onFailure();
        void onCancelled();
    }

    private final MyVideoView videoView;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PlaybackSuccessTracker tracker = new PlaybackSuccessTracker();
    private String contentKey;
    private String url;
    private AbstractPlayer player;
    private int playerType = -1;
    private long generation;
    private long lastPosition;
    private long lastTick;
    private long startupTime;
    private boolean active;
    private boolean shouldSave;
    private boolean saved;
    private boolean foreground;
    private boolean paused;
    private boolean externalPending;
    private boolean externalLeft;
    private SelectDialog<Integer> confirmation;

    private final BaseVideoView.SimpleOnStateChangeListener stateListener =
            new BaseVideoView.SimpleOnStateChangeListener() {
        @Override
        public void onPlayStateChanged(int state) {
            if (state == BaseVideoView.STATE_PAUSED) paused = true;
            else if (state == BaseVideoView.STATE_PLAYING) paused = false;
            if (state == BaseVideoView.STATE_PAUSED || state == BaseVideoView.STATE_BUFFERING) {
                tracker.discontinuity();
            }
        }
    };

    public PlaybackFallbackController(MyVideoView videoView, Host host) {
        this.videoView = videoView;
        this.host = host;
        videoView.addOnStateChangeListener(stateListener);
        videoView.setPlaybackSeekListener(tracker::discontinuity);
        // Completion must be observed before VodController advances to the next episode.
        videoView.setPlaybackCompletionListener(this::onCompletion);
    }

    public long beginAttempt(String contentKey, String url, int playerType, boolean shouldSave) {
        boolean sameContent = contentKey.equals(this.contentKey);
        cancelAttempt(!sameContent);
        this.contentKey = contentKey;
        this.url = url;
        this.playerType = playerType;
        this.shouldSave = shouldSave;
        active = true;
        return generation;
    }

    public void observeInternal() {
        if (!active) return;
        player = videoView.getMediaPlayer();
        lastTick = SystemClock.elapsedRealtime();
        handler.post(tick);
    }

    public void externalOpened() {
        externalPending = active && shouldSave;
        externalLeft = false;
    }

    public long getGeneration() {
        return generation;
    }

    public int getPlayerType() {
        return playerType;
    }

    public long getLastPosition() {
        return lastPosition;
    }

    public boolean fail(long token) {
        if (token != generation || !isCurrent()) return false;
        cancelAttempt(false);
        return true;
    }

    public void cancelAttempt(boolean clearPosition) {
        generation++;
        active = false;
        player = null;
        saved = false;
        paused = false;
        startupTime = 0;
        tracker.reset();
        externalPending = false;
        externalLeft = false;
        handler.removeCallbacksAndMessages(null);
        dismissConfirmation();
        if (clearPosition) lastPosition = 0;
    }

    public void setForeground(boolean foreground) {
        this.foreground = foreground;
        lastTick = SystemClock.elapsedRealtime();
        tracker.discontinuity();
        if (!foreground && externalPending) externalLeft = true;
    }

    public void showExternalConfirmation(Activity activity, boolean visible) {
        if (!visible || !foreground || !externalPending || !externalLeft || confirmation != null
                || !isCurrent() || activity == null || activity.isFinishing()) return;
        final long token = generation;
        SelectDialog<Integer> dialog = new SelectDialog<>(activity);
        confirmation = dialog;
        dialog.setTip(activity.getString(R.string.player_external_confirm,
                PlayerHelper.getPlayerName(playerType)));
        dialog.setItemCheckDisplay(false);
        dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
            @Override
            public void click(Integer choice, int pos) {
                if (token != generation || !isCurrent() || !externalPending) return;
                externalPending = false;
                dialog.dismiss();
                if (choice == 0) {
                    commitSuccess();
                } else if (choice == 1) {
                    host.onFailure();
                } else {
                    cancelAttempt(false);
                    host.onCancelled();
                }
            }

            @Override
            public String getDisplay(Integer choice) {
                int id = choice == 0 ? R.string.player_external_success
                        : choice == 1 ? R.string.player_external_failure : R.string.player_external_cancel;
                return activity.getString(id);
            }
        }, new DiffUtil.ItemCallback<Integer>() {
            @Override
            public boolean areItemsTheSame(@NonNull Integer oldItem, @NonNull Integer newItem) {
                return oldItem.equals(newItem);
            }

            @Override
            public boolean areContentsTheSame(@NonNull Integer oldItem, @NonNull Integer newItem) {
                return oldItem.equals(newItem);
            }
        }, Arrays.asList(0, 1, 2), 0);
        dialog.setOnDismissListener(ignored -> {
            if (confirmation == dialog) confirmation = null;
            if (token == generation && externalPending && isCurrent()) {
                cancelAttempt(false);
                host.onCancelled();
            }
        });
        dialog.show();
    }

    private void dismissConfirmation() {
        if (confirmation == null) return;
        SelectDialog<Integer> dialog = confirmation;
        confirmation = null;
        dialog.setOnDismissListener(null);
        dialog.dismiss();
    }

    private boolean isCurrent() {
        return active && host.isCurrent(contentKey, url);
    }

    private boolean isInternalCurrent() {
        return isCurrent() && playerType < 10 && player != null
                && videoView.getMediaPlayer() == player;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!isInternalCurrent()) return;
            long now = SystemClock.elapsedRealtime();
            if (foreground && !paused) startupTime += Math.min(now - lastTick, 1000);
            lastTick = now;
            int state = videoView.getCurrentPlayState();
            boolean playing = foreground && !paused && videoView.isPlaying()
                    && (state == BaseVideoView.STATE_PLAYING || state == BaseVideoView.STATE_BUFFERED);
            long position = videoView.getCurrentPosition();
            if (playing) lastPosition = position;
            if (tracker.sample(now, position, playing, videoView.getSpeed())) commitSuccess();
            if (foreground && !paused && !tracker.hasProgress() && startupTime >= 30000) {
                host.onFailure();
                return;
            }
            handler.postDelayed(this, 500);
        }
    };

    private void onCompletion() {
        if (isInternalCurrent() && foreground && tracker.canConfirmCompletion()) commitSuccess();
    }

    private void commitSuccess() {
        if (!isCurrent() || saved) return;
        saved = true;
        if (shouldSave) host.onSuccess(playerType);
    }

    public void release() {
        cancelAttempt(true);
        videoView.removeOnStateChangeListener(stateListener);
        videoView.setPlaybackSeekListener(null);
        videoView.setPlaybackCompletionListener(null);
    }
}
