package com.github.tvbox.osc.player;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.HawkUtils;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.ui.widget.DanmakuView;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoView;

public class MyVideoView extends VideoView implements DrawHandler.Callback {
    private DanmakuView danmuView;
    private Runnable danmuReleaseListener;
    private boolean resumeAfterLifecyclePause;
    private Runnable playbackSeekListener;
    private Runnable playbackCompletionListener;

    public MyVideoView(@NonNull Context context) {
        super(context, null);
    }

    public MyVideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public MyVideoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public AbstractPlayer getMediaPlayer() {
        return mMediaPlayer;
    }

    public int[] getVideoSize(){
        return mVideoSize;
    }

    @Override
    protected void setPlayState(int playState) {
        if (playState == STATE_PLAYBACK_COMPLETED && playbackCompletionListener != null) {
            playbackCompletionListener.run();
        }
        super.setPlayState(playState);
        if (!haveDanmu()) return;
        if (playState == STATE_PLAYING) {
            danmuView.seekTo(getCurrentPosition());
            danmuView.resume();
        } else if (playState == STATE_PAUSED) {
            danmuView.pause();
        }
    }

    @Override
    public void seekTo(long pos) {
        if (playbackSeekListener != null) playbackSeekListener.run();
        super.seekTo(pos);
        if (haveDanmu()) danmuView.seekTo(pos);
    }

    @Override
    public void resume() {
        resumeAfterLifecyclePause = false;
        super.resume();
        if (haveDanmu()) danmuView.resume();
    }

    @Override
    public void start() {
        resumeAfterLifecyclePause = false;
        super.start();
        if (haveDanmu()) danmuView.resume();
    }

    @Override
    public void pause() {
        resumeAfterLifecyclePause = false;
        super.pause();
        if (haveDanmu()) danmuView.pause();
    }

    public void pauseForLifecycle() {
        boolean shouldResume = resumeAfterLifecyclePause || isPlaying();
        pause();
        resumeAfterLifecyclePause = shouldResume;
    }

    public void resumeAfterLifecycle() {
        if (resumeAfterLifecyclePause) {
            resumeAfterLifecyclePause = false;
            resume();
        }
    }

    @Override
    public void stopPlay() {
        resumeAfterLifecyclePause = false;
        super.stopPlay();
        if (danmuReleaseListener != null) danmuReleaseListener.run();
    }

    @Override
    public void release() {
        resumeAfterLifecyclePause = false;
        super.release();
        if (danmuReleaseListener != null) danmuReleaseListener.run();
    }

    private boolean haveDanmu() {
        return danmuView != null && HawkUtils.getDanmuOpen()
                && danmuView.isShown() && danmuView.isPrepared();
    }

    public void setPlaybackSeekListener(Runnable listener) {
        playbackSeekListener = listener;
    }

    public void setPlaybackCompletionListener(Runnable listener) {
        playbackCompletionListener = listener;
    }

    public void setDanmuView(DanmakuView view) {
        view.setCallback(this);
        danmuView = view;
    }
    public void setDanmuReleaseListener(Runnable listener) {
        danmuReleaseListener = listener;
    }

    public DanmakuView getDanmuView() {
        return danmuView;
    }

    @Override
    public void prepared() {
        App.post(() -> {
            if (!haveDanmu()) return;
            danmuView.start(getCurrentPosition());
            if (!isPlaying()) danmuView.pause();
        });
    }

    @Override
    public void updateTimer(DanmakuTimer timer) {

    }

    @Override
    public void danmakuShown(BaseDanmaku danmaku) {

    }

    @Override
    public void drawingFinished() {

    }
}
