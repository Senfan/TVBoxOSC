package com.github.tvbox.osc.util;

import org.json.JSONObject;

import xyz.doikki.videoplayer.player.VideoView;

public final class PlayerHelper {
    public static void updateCfg(VideoView videoView, JSONObject config) {
        videoView.playerConfig = new JSONObject(config.toString());
        videoView.configUpdates++;
        videoView.setScreenScaleType(config.optInt("sc"));
    }
}
