package com.github.tvbox.osc.bean;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import xyz.doikki.videoplayer.player.VideoView;

public final class LivePlayerManagerTest {
    private static int assertions;

    public static void main(String[] args) {
        testMenuMappingAndIndependentChannels();
        testDefaultDoesNotChangeOverrides();
        testLegacyAndPartialScaleConfigs();
        testFollowDefaultMasksLegacy();
        testResetPreservesDefaultAndOtherData();
        testResetWithoutUserDefault();
        testInvalidPreferencesAndWriteFailure();
        testConfigCopiesAndUpdateOptimization();
        testReinitialization();
        System.out.println("LivePlayerManagerTest: " + assertions + " assertions passed");
    }

    private static LivePlayerManager init(VideoView view) {
        LivePlayerManager manager = new LivePlayerManager();
        manager.init(view);
        return manager;
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void player(LivePlayerManager manager, int index, String message) {
        check(manager.getLivePlayerType() == index, message);
    }

    private static JSONObject legacy(int player, String codec, int scale) {
        return new JSONObject().put("pl", player).put("ijk", codec).put("sc", scale).put("pr", 0);
    }

    private static JSONObject savedPreferences() {
        return Hawk.get(HawkConfig.LIVE_PLAYER_PREFERENCES, new JSONObject());
    }

    private static void testMenuMappingAndIndependentChannels() {
        Hawk.clear();
        VideoView view = new VideoView();
        LivePlayerManager manager = init(view);
        int[] players = {0, 1, 1, 2};
        String[] codecs = {"软解码", "硬解码", "软解码", "软解码"};
        for (int index = 0; index < 4; index++) {
            manager.changeLivePlayerType(view, index, "频道" + index);
            player(manager, index, "菜单索引应往返一致");
            check(manager.currentPlayerConfig.optInt("pl") == players[index], "播放器ID不能等于菜单索引");
            check(manager.currentPlayerConfig.optString("ijk").equals(codecs[index]), "硬／软解码应正确映射");
            check(!Hawk.values.containsKey("频道" + index), "播放器偏好不能混入比例缓存");
        }
        manager.getLiveChannelPlayer(view, "频道1");
        player(manager, 1, "A台应保留IJK硬解");
        manager.getLiveChannelPlayer(view, "频道3");
        player(manager, 3, "B台应保留Exo");
        manager.getLiveChannelPlayer(view, "没有设置的频道");
        player(manager, 0, "未设置频道使用默认");
        for (String key : Hawk.writes)
            check(HawkConfig.LIVE_PLAYER_PREFERENCES.equals(key), "手动直播选择只能写独立偏好键");
    }

    private static void testDefaultDoesNotChangeOverrides() {
        Hawk.clear();
        Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, 2);
        Hawk.put(HawkConfig.PLAY_TYPE, 0);
        VideoView view = new VideoView();
        LivePlayerManager manager = init(view);
        player(manager, 3, "直播源默认应优先于应用默认");
        manager.changeLivePlayerType(view, 1, "A");
        check(manager.setLiveDefaultPlayer(), "设为直播默认成功");
        manager.getLiveChannelPlayer(view, "B");
        player(manager, 1, "无独立选择的B台采用直播默认");
        manager.changeLivePlayerType(view, 3, "B");
        manager.setLiveDefaultPlayer();
        manager.getLiveChannelPlayer(view, "A");
        player(manager, 1, "改变默认不能影响A台明确覆盖");
        manager.getLiveChannelPlayer(view, "C");
        player(manager, 3, "新默认应应用于C台");
        check(Hawk.<Integer>get(HawkConfig.PLAY_TYPE, -1) == 0, "点播全局不变");
        check(Hawk.<Integer>get(HawkConfig.LIVE_PLAYER_TYPE, -1) == 2, "源默认不变");
        LivePlayerManager restarted = init(new VideoView());
        restarted.getLiveChannelPlayer(new VideoView(), "A");
        player(restarted, 1, "重新实例化保留频道覆盖");
        check(restarted.getDefaultLivePlayerType() == 3, "重新实例化保留直播默认");
    }

    private static void testLegacyAndPartialScaleConfigs() {
        Hawk.clear();
        JSONObject old = legacy(1, "硬解码", 2).put("custom", "keep");
        Hawk.put("旧台", old);
        Hawk.put(HawkConfig.PLAY_SCALE, 1);
        VideoView view = new VideoView();
        LivePlayerManager manager = init(view);
        manager.getLiveChannelPlayer(view, "旧台");
        player(manager, 1, "未重置前保守兼容旧播放器");
        check(manager.getLivePlayerScale() == 2, "保留旧台比例");
        manager.changeLivePlayerType(view, 3, "默认提供台");
        manager.setLiveDefaultPlayer();
        manager.getLiveChannelPlayer(view, "旧台");
        player(manager, 1, "改变默认不清除旧台可能的明确选择");
        manager.changeLivePlayerScale(view, 5, "新台");
        JSONObject scaleOnly = Hawk.get("新台", null);
        check(scaleOnly.has("sc") && !scaleOnly.has("pl") && !scaleOnly.has("ijk"), "新比例缓存不创建播放器覆盖");
        player(manager, 3, "新比例缓存继续使用默认");
        check(manager.getLivePlayerScale() == 5, "新台比例已保存");
        manager.changeLivePlayerType(view, 2, "默认提供台2");
        manager.setLiveDefaultPlayer();
        manager.getLiveChannelPlayer(view, "新台");
        player(manager, 2, "只改比例的台应随新默认变化");
        check(manager.getLivePlayerScale() == 5, "默认变化后比例不变");
        check(old.optInt("sc") == 2 && "keep".equals(old.optString("custom")), "原旧缓存不能被修改");
    }

    private static void testFollowDefaultMasksLegacy() {
        Hawk.clear();
        JSONObject old = legacy(1, "硬解码", 3);
        Hawk.put("旧台", old);
        VideoView view = new VideoView();
        LivePlayerManager manager = init(view);
        manager.changeLivePlayerType(view, 3, "提供默认的台");
        manager.setLiveDefaultPlayer();
        manager.getLiveChannelPlayer(view, "旧台");
        player(manager, 1, "旧台初始使用旧覆盖");
        check(manager.followLiveDefaultPlayer("旧台"), "跟随默认成功");
        check(manager.isLivePlayerChanged("旧台"), "从旧IJK变Exo需要重新配置");
        manager.getLiveChannelPlayer(view, "旧台");
        player(manager, 3, "跟随默认标记屏蔽旧覆盖");
        check(manager.getLivePlayerScale() == 3, "跟随默认保留比例");
        check(old.optInt("pl") == 1, "旧缓存本身不删除或改写");
        manager = init(new VideoView());
        manager.getLiveChannelPlayer(new VideoView(), "旧台");
        player(manager, 3, "重新实例化后不复活旧播放器");
    }

    private static void testResetPreservesDefaultAndOtherData() {
        Hawk.clear();
        JSONObject old = legacy(1, "硬解码", 4).put("pr", 1).put("custom", "unchanged");
        Hawk.put("未打开的旧台", old);
        Hawk.put(HawkConfig.PLAY_TYPE, 0);
        Hawk.put(HawkConfig.IJK_CODEC, "自定义软解");
        Hawk.put(HawkConfig.LIVE_CHANNEL, "旧台");
        Hawk.put("favorites", "keep favorites");
        VideoView view = new VideoView();
        LivePlayerManager manager = init(view);
        manager.changeLivePlayerType(view, 1, "A");
        manager.changeLivePlayerType(view, 3, "B");
        manager.setLiveDefaultPlayer();
        JSONObject before = savedPreferences();
        String beforeString = before.toString();
        Hawk.writes.clear();
        check(manager.resetLivePlayerOverrides(), "全部重置成功");
        check(beforeString.equals(before.toString()), "重置不能原地修改已存偏好对象");
        check(savedPreferences().optInt("default", -1) == 3, "重置保留直播默认");
        check(savedPreferences().optJSONObject("channels").length() == 0, "一次清空所有新覆盖");
        check(savedPreferences().optBoolean("ignoreLegacy"), "重置屏蔽所有旧覆盖");
        for (String channel : new String[]{"A", "B", "未打开的旧台", "新台"}) {
            manager.getLiveChannelPlayer(view, channel);
            player(manager, 3, "所有频道应跟随直播默认：" + channel);
        }
        manager.getLiveChannelPlayer(view, "未打开的旧台");
        check(manager.getLivePlayerScale() == 4, "重置保留旧比例");
        check(manager.currentPlayerConfig.optInt("pr") == 1, "重置保留渲染字段");
        check("unchanged".equals(manager.currentPlayerConfig.optString("custom")), "重置保留其他字段");
        check(Hawk.get("未打开的旧台", null) == old, "不能删除整份旧缓存");
        check(Hawk.writes.size() == 1 && Hawk.writes.contains(HawkConfig.LIVE_PLAYER_PREFERENCES), "重置只写独立偏好");
        check("keep favorites".equals(Hawk.get("favorites", "")), "收藏不受影响");
        check(Hawk.<Integer>get(HawkConfig.PLAY_TYPE, -1) == 0, "点播默认不受影响");
        check("自定义软解".equals(Hawk.get(HawkConfig.IJK_CODEC, "")), "全局解码不受影响");
        manager.changeLivePlayerScale(view, 2, "未打开的旧台");
        manager = init(new VideoView());
        manager.getLiveChannelPlayer(new VideoView(), "未打开的旧台");
        player(manager, 3, "重置后改比例和重启不能复活旧覆盖");
        check(manager.getLivePlayerScale() == 2, "重置后仍能单独改比例");
        manager.changeLivePlayerType(view, 2, "A");
        manager.getLiveChannelPlayer(view, "A");
        player(manager, 2, "重置后可新增频道独立选择");
        manager.getLiveChannelPlayer(view, "B");
        player(manager, 3, "新例外不影响其他频道");
        manager.resetLivePlayerOverrides();
        String once = savedPreferences().toString();
        manager.resetLivePlayerOverrides();
        check(once.equals(savedPreferences().toString()), "重复重置幂等");
        manager.getDefaultLiveChannelPlayer(view);
        player(manager, 3, "默认路径不能清掉用户默认");
    }

    private static void testResetWithoutUserDefault() {
        Hawk.clear();
        Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, 2);
        Hawk.put("旧台", legacy(1, "硬解码", 2));
        VideoView view = new VideoView();
        LivePlayerManager manager = init(view);
        manager.resetLivePlayerOverrides();
        manager.getLiveChannelPlayer(view, "旧台");
        player(manager, 3, "无用户默认重置后使用源默认");
        Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, 0);
        manager = init(view);
        manager.getLiveChannelPlayer(view, "旧台");
        player(manager, 0, "重进直播采用新源默认，旧覆盖不复活");
    }

    private static void testInvalidPreferencesAndWriteFailure() {
        Hawk.clear();
        Hawk.put(HawkConfig.LIVE_PLAYER_PREFERENCES, "malformed");
        VideoView view = new VideoView();
        LivePlayerManager manager = init(view);
        player(manager, 0, "错误类型偏好使用原默认");
        JSONObject invalid = new JSONObject().put("default", 99)
                .put("channels", new JSONObject().put("A", 10));
        Hawk.put(HawkConfig.LIVE_PLAYER_PREFERENCES, invalid);
        manager.getLiveChannelPlayer(view, "A");
        player(manager, 0, "非法默认和覆盖不扩展外部播放器");
        Hawk.put(HawkConfig.LIVE_PLAYER_PREFERENCES, new JSONObject().put("default", 3.5)
                .put("channels", new JSONObject().put("A", "3")));
        manager.getLiveChannelPlayer(view, "A");
        player(manager, 0, "小数和字符串不当成合法菜单索引");
        check(!manager.rememberLivePlayerType(-1, "A"), "手动引擎不接受跟随哨兵");
        check(!manager.rememberLivePlayerType(4, "A"), "动作索引不当成引擎");
        check(!manager.rememberLivePlayerType(1, null), "空频道不保存覆盖");
        check(!manager.followLiveDefaultPlayer(null), "空频道不能跟随默认");
        manager.rememberLivePlayerType(1, "A");
        JSONObject saved = savedPreferences();
        String snapshot = saved.toString();
        Hawk.failWrites = true;
        check(!manager.resetLivePlayerOverrides(), "保存失败不能报告重置成功");
        check(snapshot.equals(savedPreferences().toString()), "重置写入失败不能改变持久偏好");
        check(!manager.followLiveDefaultPlayer("A"), "跟随保存失败有明确结果");
        check(!manager.rememberLivePlayerType(3, "A"), "手动保存失败有明确结果");
        check(snapshot.equals(saved.toString()), "失败操作不改原对象");
        Hawk.failWrites = false;
        Hawk.put(HawkConfig.LIVE_PLAYER_PREFERENCES, new JSONObject().put("channels", "bad"));
        check(manager.followLiveDefaultPlayer("A"), "错误类型channels可修复为对象");
    }

    private static void testConfigCopiesAndUpdateOptimization() {
        Hawk.clear();
        JSONObject old = legacy(1, "硬解码", 3);
        Hawk.put("旧台", old);
        VideoView view = new VideoView();
        LivePlayerManager manager = init(view);
        String baseline = manager.defaultPlayerConfig.toString();
        manager.changeLivePlayerType(view, 3, "提供默认的台");
        manager.setLiveDefaultPlayer();
        manager.resetLivePlayerOverrides();
        manager.getLiveChannelPlayer(view, "旧台");
        player(manager, 3, "先合并偏好再比较旧缓存");
        check(old.optInt("pl") == 1, "合并不修改旧缓存");
        int fullUpdates = view.configUpdates;
        manager.getLiveChannelPlayer(view, "旧台");
        check(view.configUpdates == fullUpdates, "配置相同不重建播放器");
        manager.changeLivePlayerScale(view, 5, "旧台");
        check(view.configUpdates == fullUpdates, "仅比例变化不重建播放器");
        check(view.screenScale == 5, "比例即时应用");
        check(!manager.isLivePlayerChanged("旧台"), "比例变化不要求释放播放器");
        manager.rememberLivePlayerType(3, "旧台");
        manager.getLiveChannelPlayer(view, "旧台");
        check(view.configUpdates == fullUpdates, "同引擎显式选择不重建");
        check(baseline.equals(manager.defaultPlayerConfig.toString()), "所有操作不修改默认基线");
        check(old.optInt("sc") == 3, "比例保存前复制原缓存");
    }

    private static void testReinitialization() {
        Hawk.clear();
        LivePlayerManager manager = init(new VideoView());
        VideoView secondView = new VideoView();
        manager.init(secondView);
        check(secondView.configUpdates == 1, "重新初始化必须配置新VideoView");
        Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, 3);
        manager.init(secondView);
        check(!manager.setLiveDefaultPlayer(), "未支持的源播放器不错误保存为系统");
    }
}
