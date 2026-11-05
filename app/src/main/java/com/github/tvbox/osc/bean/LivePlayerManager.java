package com.github.tvbox.osc.bean;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.PlayerHelper;
import com.orhanobut.hawk.Hawk;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

import xyz.doikki.videoplayer.player.VideoView;

public class LivePlayerManager {
    JSONObject defaultPlayerConfig = new JSONObject();
    JSONObject currentPlayerConfig;

    public void init(VideoView videoView) {
        currentPlayerConfig = null;
        try {
            int playerType = Hawk.get(HawkConfig.LIVE_PLAYER_TYPE, -1);
            if (playerType == -1) playerType = Hawk.get(HawkConfig.PLAY_TYPE, 0);
            defaultPlayerConfig.put("pl", playerType);
            defaultPlayerConfig.put("ijk", Hawk.get(HawkConfig.IJK_CODEC, "软解码"));
            defaultPlayerConfig.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 0));
            defaultPlayerConfig.put("sc", Hawk.get(HawkConfig.PLAY_SCALE, 0));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        getDefaultLiveChannelPlayer(videoView);
    }

    private JSONObject copyConfig(JSONObject config) {
        try {
            return config == null ? new JSONObject() : new JSONObject(config.toString());
        } catch (JSONException e) {
            e.printStackTrace();
            return new JSONObject();
        }
    }

    private JSONObject getPreferences() {
        Object saved = Hawk.get(HawkConfig.LIVE_PLAYER_PREFERENCES, null);
        return copyConfig(saved instanceof JSONObject ? (JSONObject) saved : null);
    }

    private JSONObject getChannelConfig(String channelName) {
        Object saved = channelName == null ? null : Hawk.get(channelName, null);
        return copyConfig(saved instanceof JSONObject ? (JSONObject) saved : null);
    }

    private boolean isPlayerIndex(int index) {
        return index >= 0 && index < 4;
    }

    private int getPreferenceIndex(JSONObject config, String key, int fallback) {
        if (config == null) return fallback;
        Object value = config.opt(key);
        if (!(value instanceof Number)) return fallback;
        Number number = (Number) value;
        int index = number.intValue();
        return index >= -1 && index < 4 && number.doubleValue() == index ? index : fallback;
    }

    private void applyPlayerIndex(JSONObject config, int index) throws JSONException {
        if (!isPlayerIndex(index)) return;
        config.put("pl", index == 3 ? 2 : (index == 0 ? 0 : 1));
        config.put("ijk", index == 1 ? "硬解码" : "软解码");
    }

    private int getPlayerIndex(JSONObject config) {
        switch (config.optInt("pl", -1)) {
            case 0:
                return 0;
            case 1:
                return "硬解码".equals(config.optString("ijk")) ? 1 : 2;
            case 2:
                return 3;
            default:
                return -1;
        }
    }

    private JSONObject resolvePlayerConfig(String channelName) {
        JSONObject config = copyConfig(defaultPlayerConfig);
        JSONObject channelConfig = getChannelConfig(channelName);
        JSONObject preferences = getPreferences();
        JSONObject channels = preferences.optJSONObject("channels");
        int channelIndex = channelName == null ? -2 : getPreferenceIndex(channels, channelName, -2);
        boolean useLegacyPlayer = channelConfig.has("pl") && channelIndex != -1
                && !preferences.optBoolean("ignoreLegacy", false);
        try {
            Iterator<String> keys = channelConfig.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"pl".equals(key) && !"ijk".equals(key))
                    config.put(key, channelConfig.get(key));
            }
            if (isPlayerIndex(channelIndex)) {
                applyPlayerIndex(config, channelIndex);
            } else if (useLegacyPlayer) {
                config.put("pl", channelConfig.get("pl"));
                config.put("ijk", channelConfig.optString("ijk", config.optString("ijk")));
            } else {
                applyPlayerIndex(config, getPreferenceIndex(preferences, "default", -1));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return config;
    }

    private boolean samePlayer(JSONObject first, JSONObject second) {
        return first != null && second != null
                && first.optInt("pl") == second.optInt("pl")
                && first.optInt("pr") == second.optInt("pr")
                && first.optString("ijk").equals(second.optString("ijk"));
    }

    private void applyConfig(VideoView videoView, JSONObject playerConfig) {
        if (currentPlayerConfig != null && playerConfig.toString().equals(currentPlayerConfig.toString()))
            return;
        if (samePlayer(playerConfig, currentPlayerConfig))
            videoView.setScreenScaleType(playerConfig.optInt("sc"));
        else
            PlayerHelper.updateCfg(videoView, playerConfig);
        currentPlayerConfig = playerConfig;
    }

    public void getDefaultLiveChannelPlayer(VideoView videoView) {
        applyConfig(videoView, resolvePlayerConfig(null));
    }

    public void getLiveChannelPlayer(VideoView videoView, String channelName) {
        applyConfig(videoView, resolvePlayerConfig(channelName));
    }

    public boolean isLivePlayerChanged(String channelName) {
        return !samePlayer(currentPlayerConfig, resolvePlayerConfig(channelName));
    }

    public int getLivePlayerType() {
        return currentPlayerConfig == null ? -1 : getPlayerIndex(currentPlayerConfig);
    }

    public int getDefaultLivePlayerType() {
        return getPlayerIndex(resolvePlayerConfig(null));
    }

    public int getLivePlayerScale() {
        return currentPlayerConfig == null ? 0 : currentPlayerConfig.optInt("sc");
    }

    public boolean rememberLivePlayerType(int playerType, String channelName) {
        if (!isPlayerIndex(playerType) || channelName == null) return false;
        JSONObject preferences = getPreferences();
        try {
            JSONObject channels = preferences.optJSONObject("channels");
            if (channels == null) channels = new JSONObject();
            channels.put(channelName, playerType);
            preferences.put("channels", channels);
            return Hawk.put(HawkConfig.LIVE_PLAYER_PREFERENCES, preferences);
        } catch (JSONException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void changeLivePlayerType(VideoView videoView, int playerType, String channelName) {
        if (rememberLivePlayerType(playerType, channelName))
            getLiveChannelPlayer(videoView, channelName);
    }

    public boolean setLiveDefaultPlayer() {
        int index = getLivePlayerType();
        if (!isPlayerIndex(index)) return false;
        JSONObject preferences = getPreferences();
        try {
            preferences.put("default", index);
            return Hawk.put(HawkConfig.LIVE_PLAYER_PREFERENCES, preferences);
        } catch (JSONException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean followLiveDefaultPlayer(String channelName) {
        if (channelName == null) return false;
        JSONObject preferences = getPreferences();
        try {
            JSONObject channels = preferences.optJSONObject("channels");
            if (channels == null) channels = new JSONObject();
            // 显式跟随默认，避免旧频道缓存的播放器重新覆盖默认。
            channels.put(channelName, -1);
            preferences.put("channels", channels);
            return Hawk.put(HawkConfig.LIVE_PLAYER_PREFERENCES, preferences);
        } catch (JSONException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean resetLivePlayerOverrides() {
        JSONObject preferences = getPreferences();
        try {
            preferences.put("channels", new JSONObject());
            preferences.put("ignoreLegacy", true);
            return Hawk.put(HawkConfig.LIVE_PLAYER_PREFERENCES, preferences);
        } catch (JSONException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void changeLivePlayerScale(@NonNull VideoView videoView, int playerScale, String channelName) {
        JSONObject channelConfig = getChannelConfig(channelName);
        try {
            // 比例单独保存；新频道不再顺带保存默认播放器。
            channelConfig.put("sc", playerScale);
            Hawk.put(channelName, channelConfig);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        getLiveChannelPlayer(videoView, channelName);
    }
}
