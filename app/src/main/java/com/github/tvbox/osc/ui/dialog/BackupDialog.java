package com.github.tvbox.osc.ui.dialog;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.ui.adapter.BackupAdapter;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.WorkspaceFiles;
import com.github.tvbox.osc.util.WorkspaceIO;
import com.github.tvbox.osc.util.WorkspaceImport;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/** Backups are private and never under the LAN workspace or FileProvider media root. */
public class BackupDialog extends BaseDialog {
    private final LinkedHashMap<String, File> entries = new LinkedHashMap<>();
    private boolean includeLegacy;
    private final BackupAdapter adapter = new BackupAdapter();

    public BackupDialog(@NonNull Context context) {
        super(context);
        setContentView(R.layout.dialog_backup);
        TvRecyclerView list = findViewById(R.id.list);
        list.setAdapter(adapter);
        adapter.setNewData(allBackup());
        adapter.setOnItemChildClickListener((base, view, position) -> {
            String selected = (String) base.getItem(position);
            if (view.getId() == R.id.tvName) {
                new AlertDialog.Builder(getContext()).setTitle("恢复所选备份？")
                        .setMessage("将恢复此备份的数据库和设置，保留备份文件。")
                        .setPositiveButton("恢复", (dialog, which) -> restore(selected)).setNegativeButton("取消", null).show();
            } else if (view.getId() == R.id.tvDel) {
                new AlertDialog.Builder(getContext()).setTitle("删除所选私有备份？")
                        .setPositiveButton("删除", (dialog, which) -> { delete(selected); adapter.setNewData(allBackup()); })
                        .setNegativeButton("取消", null).show();
            }
        });
        findViewById(R.id.backupNow).setOnClickListener(v -> { backup(); adapter.setNewData(allBackup()); });
        findViewById(R.id.backupImport).setOnClickListener(v -> WorkspaceImport.choose(getContext(), true,
                imported -> { if (isShowing()) adapter.setNewData(allBackup()); }));
        findViewById(R.id.backupExport).setOnClickListener(v -> {
            List<String> names = allBackup();
            new AlertDialog.Builder(getContext()).setTitle("选择要导出的备份")
                    .setItems(names.toArray(new String[0]), (dialog, which) -> export(entries.get(names.get(which))))
                    .setNegativeButton("取消", null).show();
        });
        findViewById(R.id.storagePermission).setOnClickListener(v -> {
            if (XXPermissions.isGranted(getContext(), DefaultConfig.StoragePermissionGroup())) {
                includeLegacy = true;
                adapter.setNewData(allBackup());
            } else XXPermissions.with(getContext()).permission(DefaultConfig.StoragePermissionGroup()).request(new OnPermissionCallback() {
                @Override public void onGranted(List<String> permissions, boolean all) {
                    includeLegacy = all;
                    adapter.setNewData(allBackup());
                }
                @Override public void onDenied(List<String> permissions, boolean never) {
                    toast("未授权，可使用系统导入旧备份；原件未改变");
                }
            });
        });
    }

    /** Enumeration has no deletion side effects and no ten-backup cap. */
    List<String> allBackup() {
        entries.clear();
        try {
            collect(WorkspaceFiles.backups(), "", 0);
            if (includeLegacy) collect(new File(Environment.getExternalStorageDirectory(), "tvbox_backup"), "旧目录/", 0);
        } catch (IOException e) { toast("备份目录不可读"); }
        return new ArrayList<>(entries.keySet());
    }

    private void collect(File root, String prefix, int depth) throws IOException {
        if (depth > 2) return;
        File[] files = root.listFiles();
        if (files == null) return;
        Arrays.sort(files, (left, right) -> right.getName().compareTo(left.getName()));
        for (File file : files) {
            if (!file.isDirectory() || !file.getAbsoluteFile().equals(file.getCanonicalFile())) continue;
            if (new File(file, "sqlite").isFile() && new File(file, "hawk").isFile()) entries.put(prefix + file.getName(), file);
            else collect(file, prefix + file.getName() + "/", depth + 1);
        }
    }

    void restore(String name) {
        File backup = entries.get(name);
        if (backup == null) return;
        try {
            // Parse/validate Hawk before replacing the database; keep the existing serialized format.
            JSONObject json = new JSONObject(WorkspaceIO.text(WorkspaceIO.resolve(backup, "hawk")));
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) if (!(json.get(keys.next()) instanceof String)) throw new IOException("备份设置格式错误");
            File db = WorkspaceIO.resolve(backup, "sqlite");
            if (!db.isFile() || db.length() < 16 || db.length() > WorkspaceIO.MAX_FILE_BYTES) throw new IOException("备份数据库无效");
            try (java.io.DataInputStream input = new java.io.DataInputStream(new java.io.FileInputStream(db))) {
                byte[] header = new byte[16];
                input.readFully(header);
                if (!Arrays.equals(header, "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII))) {
                    throw new IOException("备份不是SQLite数据库");
                }
            }
            if (!AppDataManager.restore(db)) throw new IOException("数据库恢复失败");
            SharedPreferences.Editor hawk = App.getInstance().getSharedPreferences("Hawk2", Context.MODE_PRIVATE).edit();
            SharedPreferences.Editor crypto = App.getInstance().getSharedPreferences("crypto.KEY_256", Context.MODE_PRIVATE).edit();
            keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.equals("cipher_key")) crypto.putString(key, json.getString(key));
                else hawk.putString(key, json.getString(key));
            }
            if (!crypto.commit() || !hawk.commit()) throw new IOException("设置写入失败，请保留备份重试");
            toast("恢复成功，应用即将重启");
            new Handler().postDelayed(this::restartApp, 3000);
        } catch (Exception e) { toast("恢复失败：" + e.getMessage()); }
    }

    private void restartApp() {
        Context context = getContext();
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent != null) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            System.exit(0);
        }
    }

    void backup() {
        File stage = null;
        try {
            stage = WorkspaceFiles.newStage();
            if (!AppDataManager.backup(new File(stage, "sqlite"))) throw new IOException("数据库备份失败");
            JSONObject json = new JSONObject();
            for (String store : new String[]{"Hawk2", "crypto.KEY_256"}) {
                SharedPreferences preferences = App.getInstance().getSharedPreferences(store, Context.MODE_PRIVATE);
                for (String key : preferences.getAll().keySet()) json.put(key, preferences.getString(key, ""));
            }
            if (!FileUtils.writeSimple(json.toString().getBytes(StandardCharsets.UTF_8), new File(stage, "hawk"))) {
                throw new IOException("设置备份失败");
            }
            File target = WorkspaceIO.resolve(WorkspaceFiles.backups(), "backup-" + System.currentTimeMillis());
            if (target.exists() || !stage.renameTo(target)) throw new IOException("备份提交失败");
            toast("已备份到私有目录，可通过系统导出保存副本");
        } catch (Exception e) { toast("备份失败，旧备份未改变"); }
        finally {
            if (stage != null && stage.exists()) try { WorkspaceIO.deleteTree(stage); } catch (IOException ignored) { }
        }
    }

    private void export(File backup) {
        if (backup == null) return;
        try {
            // Legacy backups are copied first, never exposed or deleted in place.
            if (!backup.getCanonicalPath().startsWith(WorkspaceFiles.backups().getPath() + File.separator)) {
                File stage = WorkspaceFiles.newStage();
                try {
                    WorkspaceIO.copyTree(backup, stage, new WorkspaceIO.Budget());
                    backup = WorkspaceIO.publish(stage, WorkspaceFiles.backups());
                } finally { if (stage.exists()) WorkspaceIO.deleteTree(stage); }
            }
            WorkspaceImport.exportBackup(getContext(), backup);
        } catch (IOException e) { toast("备份副本创建失败，原件未改变"); }
    }

    void delete(String name) {
        File backup = entries.get(name);
        if (backup == null) return;
        try {
            String relative = WorkspaceIO.relative(WorkspaceFiles.backups(), backup);
            if (relative.isEmpty()) throw new IOException("不能删除备份根");
            WorkspaceIO.deleteTree(WorkspaceIO.resolve(WorkspaceFiles.backups(), relative));
            toast("所选私有备份已删除");
        } catch (IOException e) { toast("无法删除；旧目录备份只读保留"); }
    }

    private void toast(String message) { Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show(); }
}
