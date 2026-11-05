package xyz.doikki.videoplayer.player;

import org.json.JSONObject;

public class VideoView {
    public JSONObject playerConfig;
    public int configUpdates;
    public int scaleUpdates;
    public int screenScale;

    public void setScreenScaleType(int scale) {
        screenScale = scale;
        scaleUpdates++;
    }
}
