package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import java.io.File;
import java.io.IOException;
import com.github.tvbox.osc.util.WorkspaceFiles;
import com.github.tvbox.osc.util.WorkspaceIO;
import com.github.tvbox.osc.util.WorkspaceImport;
import com.obsez.android.lib.filechooser.ChooserDialog;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.ui.adapter.ApiHistoryDialogAdapter;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 描述
 *
 * @author pj567
 * @since 2020/12/27
 */
public class ApiDialog extends BaseDialog {
    private final ImageView ivQRCode;
    private final TextView tvAddress;
    private final EditText inputApi;
    private final EditText inputLive;
    private final EditText inputEPG;
    private final EditText inputProxy;

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_API_URL_CHANGE) {
            inputApi.setText((String) event.obj);
        }
        if (event.type == RefreshEvent.TYPE_LIVE_URL_CHANGE) {
            inputLive.setText((String) event.obj);
        }
        if (event.type == RefreshEvent.TYPE_EPG_URL_CHANGE) {
            inputEPG.setText((String) event.obj);
        }
        if (event.type == RefreshEvent.TYPE_PROXYS_CHANGE) {
            inputProxy.setText((String) event.obj);
        }
    }

    public ApiDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_api);
        setCanceledOnTouchOutside(true);
        ivQRCode = findViewById(R.id.ivQRCode);
        tvAddress = findViewById(R.id.tvAddress);
        inputApi = findViewById(R.id.input);
        inputApi.setText(Hawk.get(HawkConfig.API_URL, ""));

        // takagen99: Add Live & EPG Address
        inputLive = findViewById(R.id.input_live);
        inputLive.setText(Hawk.get(HawkConfig.LIVE_URL, ""));
        inputEPG = findViewById(R.id.input_epg);
        inputEPG.setText(Hawk.get(HawkConfig.EPG_URL, ""));
        inputProxy = findViewById(R.id.input_proxy);
        inputProxy.setText(Hawk.get(HawkConfig.PROXY_SERVER, ""));

        findViewById(R.id.inputSubmit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String newApi = inputApi.getText().toString().trim();
                String newLive = inputLive.getText().toString().trim();
                String newEPG = inputEPG.getText().toString().trim();
                String newProxyServer = inputProxy.getText().toString().trim();
                try {
                    if (newApi.startsWith("file:/")) {
                        File file = WorkspaceFiles.fromLocalUrl(newApi);
                        if (file != null) newApi = WorkspaceFiles.clan(file);
                    } else if (newApi.startsWith("./")) {
                        newApi = WorkspaceFiles.clan(WorkspaceFiles.resolve(newApi.substring(2)));
                    }
                } catch (IOException e) {
                    Toast.makeText(getContext(), "本地路径无效，请导入整个配置目录或ZIP", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!newApi.isEmpty()) {
                    ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
                    if (!history.contains(newApi))
                        history.add(0, newApi);
                    if (history.size() > 20)
                        history.remove(20);
                    Hawk.put(HawkConfig.API_HISTORY, history);
                    listener.onchange(newApi);
                    dismiss();
                }
                // Capture Live input into Settings & Live History (max 20)
                Hawk.put(HawkConfig.LIVE_URL, newLive);
                if (!newLive.isEmpty()) {
                    ArrayList<String> liveHistory = Hawk.get(HawkConfig.LIVE_HISTORY, new ArrayList<String>());
                    if (!liveHistory.contains(newLive))
                        liveHistory.add(0, newLive);
                    if (liveHistory.size() > 20)
                        liveHistory.remove(20);
                    Hawk.put(HawkConfig.LIVE_HISTORY, liveHistory);
                }
                // Capture EPG input into Settings
                Hawk.put(HawkConfig.EPG_URL, newEPG);
                if (!newEPG.isEmpty()) {
                    ArrayList<String> EPGHistory = Hawk.get(HawkConfig.EPG_HISTORY, new ArrayList<String>());
                    if (!EPGHistory.contains(newEPG))
                        EPGHistory.add(0, newEPG);
                    if (EPGHistory.size() > 20)
                        EPGHistory.remove(20);
                    Hawk.put(HawkConfig.EPG_HISTORY, EPGHistory);
                }
                // Capture oroxy server input into Settings
                Hawk.put(HawkConfig.PROXY_SERVER, newProxyServer);
            }
        });
        findViewById(R.id.apiHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<String> history = Hawk.get(HawkConfig.API_HISTORY, new ArrayList<String>());
                if (history.isEmpty())
                    return;
                String current = Hawk.get(HawkConfig.API_URL, "");
                int idx = 0;
                if (history.contains(current))
                    idx = history.indexOf(current);
                ApiHistoryDialog dialog = new ApiHistoryDialog(getContext());
                dialog.setTip(HomeActivity.getRes().getString(R.string.dia_history_list));
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String value) {
                        inputApi.setText(value);
                        listener.onchange(value);
                        dialog.dismiss();
                    }

                    @Override
                    public void del(String value, ArrayList<String> data) {
                        Hawk.put(HawkConfig.API_HISTORY, data);
                    }
                }, history, idx);
                dialog.show();
            }
        });
        findViewById(R.id.liveHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<String> liveHistory = Hawk.get(HawkConfig.LIVE_HISTORY, new ArrayList<String>());
                if (liveHistory.isEmpty())
                    return;
                String current = Hawk.get(HawkConfig.LIVE_URL, "");
                int idx = 0;
                if (liveHistory.contains(current))
                    idx = liveHistory.indexOf(current);
                ApiHistoryDialog dialog = new ApiHistoryDialog(getContext());
                dialog.setTip(HomeActivity.getRes().getString(R.string.dia_history_live));
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String liveURL) {
                        inputLive.setText(liveURL);
                        Hawk.put(HawkConfig.LIVE_URL, liveURL);
                        dialog.dismiss();
                    }

                    @Override
                    public void del(String value, ArrayList<String> data) {
                        Hawk.put(HawkConfig.LIVE_HISTORY, data);
                    }
                }, liveHistory, idx);
                dialog.show();
            }
        });
        findViewById(R.id.EPGHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<String> EPGHistory = Hawk.get(HawkConfig.EPG_HISTORY, new ArrayList<String>());
                if (EPGHistory.isEmpty())
                    return;
                String current = Hawk.get(HawkConfig.EPG_URL, "");
                int idx = 0;
                if (EPGHistory.contains(current))
                    idx = EPGHistory.indexOf(current);
                ApiHistoryDialog dialog = new ApiHistoryDialog(getContext());
                dialog.setTip(HomeActivity.getRes().getString(R.string.dia_history_epg));
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String epgURL) {
                        inputEPG.setText(epgURL);
                        Hawk.put(HawkConfig.EPG_URL, epgURL);
                        dialog.dismiss();
                    }

                    @Override
                    public void del(String value, ArrayList<String> data) {
                        Hawk.put(HawkConfig.EPG_HISTORY, data);
                    }
                }, EPGHistory, idx);
                dialog.show();
            }
        });
        findViewById(R.id.workspaceImport).setOnClickListener(v ->
                WorkspaceImport.choose(getContext(), false, this::selectImportedConfig));
        findViewById(R.id.storagePermission).setOnClickListener(v ->
                new AlertDialog.Builder(getContext()).setTitle("可选传统目录迁移")
                        .setMessage("仅用于旧系统/旧目录。授权后请选择配置所在目录，完整复制到私有工作区，不删除原件。不需要此功能可使用系统导入。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("选择传统目录", (dialog, which) -> requestLegacyImport()).show());
        refreshQRCode();
    }

    private void requestLegacyImport() {
        if (XXPermissions.isGranted(getContext(), DefaultConfig.StoragePermissionGroup())) {
            chooseLegacyDirectory();
            return;
        }
        XXPermissions.with(getContext()).permission(DefaultConfig.StoragePermissionGroup()).request(new OnPermissionCallback() {
            @Override public void onGranted(List<String> permissions, boolean all) {
                if (all) chooseLegacyDirectory();
                else Toast.makeText(getContext(), "未获得完整授权，请使用系统导入", Toast.LENGTH_LONG).show();
            }
            @Override public void onDenied(List<String> permissions, boolean never) {
                Toast.makeText(getContext(), "未授权，原配置未改变；可使用系统导入", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void chooseLegacyDirectory() {
        new ChooserDialog(getContext(), R.style.FileChooserStyle).withFilter(true, true)
                .withStringResources("选择完整配置目录（不移动原件）", "复制", "取消").enableDpad(true)
                .withChosenListener((path, source) -> {
                    Toast.makeText(getContext(), "正在复制配置目录…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        File stage = null;
                        try {
                            stage = WorkspaceFiles.newStage();
                            WorkspaceIO.copyTree(source, stage, new WorkspaceIO.Budget());
                            File imported = WorkspaceIO.publish(stage, WorkspaceFiles.root());
                            inputApi.post(() -> { if (isShowing()) selectImportedConfig(imported); });
                        } catch (IOException e) {
                            inputApi.post(() -> Toast.makeText(getContext(), "迁移失败，原件未改变：" + e.getMessage(), Toast.LENGTH_LONG).show());
                        } finally {
                            if (stage != null && stage.exists()) try { WorkspaceIO.deleteTree(stage); } catch (IOException ignored) { }
                        }
                    }, "legacy-config-copy").start();
                }).show();
    }

    private void selectImportedConfig(File directory) {
        if (!isShowing()) return;
        ArrayList<File> configs = new ArrayList<>();
        findConfigs(directory, configs);
        if (configs.isEmpty()) {
            Toast.makeText(getContext(), "已导入资源；未发现JSON/TXT配置，可在电脑配置页选择文件", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[configs.size()];
        for (int i = 0; i < names.length; i++) names[i] = configs.get(i).getAbsolutePath().substring(directory.getAbsolutePath().length() + 1);
        new AlertDialog.Builder(getContext()).setTitle("选择配置（确定后生效）").setItems(names, (dialog, which) -> {
            try { inputApi.setText(WorkspaceFiles.clan(configs.get(which))); }
            catch (IOException e) { Toast.makeText(getContext(), "文件路径无效", Toast.LENGTH_SHORT).show(); }
        }).setNegativeButton("取消", null).show();
    }

    private static void findConfigs(File directory, ArrayList<File> result) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) findConfigs(file, result);
            else if (file.getName().toLowerCase(java.util.Locale.ROOT).matches(".*\\.(json|txt)$")) result.add(file);
        }
    }

    private void refreshQRCode() {
        String address = ControlManager.get().getAddress(false);
        tvAddress.setText(String.format("手机/电脑扫描上方二维码或者直接浏览器访问地址\n%s", address));
        ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address, AutoSizeUtils.mm2px(getContext(), 300), AutoSizeUtils.mm2px(getContext(), 300)));
    }

    public void setOnListener(OnListener listener) {
        this.listener = listener;
    }

    OnListener listener = null;

    public interface OnListener {
        void onchange(String api);
    }
}