package com.github.tvbox.osc.player.danmu;

import android.text.TextUtils;
import android.view.View;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.Danmu;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.util.HawkUtils;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import master.flame.danmaku.controller.DrawHandler;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.ui.widget.DanmakuView;

public final class DanmuController {
    public interface VisibilityChecker {
        boolean canShow();
    }

    private final MyVideoView videoView;
    private final DanmakuView danmuView;
    private final DanmakuContext context = DanmakuContext.create();
    private final VisibilityChecker visibilityChecker;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> loadTask;
    private Parser.LoadControl loadControl;
    private boolean loading;
    private boolean preparing;
    private volatile int generation;
    private volatile boolean released;
    private String content;
    private String cachedContent;
    private Danmu cachedDanmu;

    public DanmuController(MyVideoView videoView, DanmakuView danmuView,
                           VisibilityChecker visibilityChecker) {
        this.videoView = videoView;
        this.danmuView = danmuView;
        this.visibilityChecker = visibilityChecker;
        videoView.setDanmuView(danmuView);
        videoView.setDanmuReleaseListener(this::stop);
        danmuView.hide();
        danmuView.setVisibility(View.GONE);
    }

    public void setContent(String content) {
        this.content = content;
        refresh(true);
    }

    public void clear() {
        content = null;
        cachedContent = null;
        cachedDanmu = null;
        stop();
    }

    public void release() {
        if (released) return;
        clear();
        released = true;
        videoView.setDanmuReleaseListener(null);
        executor.shutdown();
    }

    public void refresh(boolean reload) {
        if (released) return;
        if (!canShow()) {
            stop();
            return;
        }
        if (!reload && ((danmuView.getVisibility() == View.VISIBLE && danmuView.isPrepared())
                || preparing || loading)) {
            applySettings();
            return;
        }

        stop();
        final int taskGeneration = generation;
        final String taskContent = content;
        final Danmu cached = taskContent.equals(cachedContent) ? cachedDanmu : null;
        final Parser.LoadControl taskControl = new Parser.LoadControl();
        loadControl = taskControl;
        loading = true;
        loadTask = executor.submit(() -> {
            try {
                if (!isCurrent(taskGeneration) || !HawkUtils.getDanmuOpen()) return;
                Parser parser = cached == null ? new Parser(taskContent, taskControl)
                        : new Parser(cached, taskControl);
                App.post(() -> {
                    if (!isCurrent(taskGeneration)) return;
                    loading = false;
                    loadTask = null;
                    if (!canShow()) {
                        stop();
                        return;
                    }
                    cachedContent = taskContent;
                    cachedDanmu = parser.getDanmu();
                    prepare(parser, taskGeneration);
                });
            } catch (Exception e) {
                e.printStackTrace();
                App.post(() -> {
                    if (isCurrent(taskGeneration)) stop();
                });
            } finally {
                App.post(() -> {
                    if (isCurrent(taskGeneration)) loading = false;
                });
            }
        });
    }

    private void prepare(Parser parser, int taskGeneration) {
        preparing = true;
        applySettings();
        danmuView.setVisibility(View.VISIBLE);
        danmuView.show();
        danmuView.setCallback(new DrawHandler.Callback() {
            @Override
            public void prepared() {
                App.post(() -> {
                    if (!isCurrent(taskGeneration)) return;
                    preparing = false;
                    if (canShow()) videoView.prepared();
                    else stop();
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
        });
        danmuView.prepare(parser, context);
    }

    private boolean canShow() {
        return !released && !TextUtils.isEmpty(content) && HawkUtils.getDanmuOpen()
                && visibilityChecker.canShow();
    }

    private boolean isCurrent(int taskGeneration) {
        return !released && generation == taskGeneration;
    }

    private void stop() {
        if (released) return;
        generation++;
        loading = false;
        preparing = false;
        if (loadControl != null) {
            loadControl.cancel();
            loadControl = null;
        }
        if (loadTask != null) {
            loadTask.cancel(true);
            loadTask = null;
        }
        danmuView.hide();
        danmuView.setVisibility(View.GONE);
        // DFM release waits for its draw thread; serialize it off the UI thread.
        executor.execute(danmuView::release);
    }

    private void applySettings() {
        HashMap<Integer, Integer> maxLines = new HashMap<>();
        int maxLine = HawkUtils.getDanmuMaxLine();
        maxLines.put(BaseDanmaku.TYPE_FIX_TOP, maxLine);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_RL, maxLine);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_LR, maxLine);
        maxLines.put(BaseDanmaku.TYPE_FIX_BOTTOM, maxLine);
        context.setMaximumLines(maxLines)
                .setScrollSpeedFactor(HawkUtils.getDanmuSpeed())
                .setDanmakuTransparency(HawkUtils.getDanmuAlpha())
                .setScaleTextSize(HawkUtils.getDanmuSizeScale());
        context.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3).setDanmakuMargin(8);
    }
}
