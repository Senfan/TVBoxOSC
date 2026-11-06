package com.github.tvbox.osc.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/** Short-lived SAF grants are consumed as streams; no _data paths or all-files permission. */
public final class WorkspaceImport {
    public interface Callback { void complete(File imported); }
    private static final String TAG = "workspace-import";
    private WorkspaceImport() { }

    public static void choose(Context context, boolean backup, Callback callback) {
        new AlertDialog.Builder(context)
                .setTitle(backup ? "导入备份（不覆盖旧备份）" : "导入配置及全部相对资源")
                .setItems(new String[]{"选择ZIP或文件（多个文件保持文件名）", "选择整个目录（推荐）"},
                        (dialog, which) -> launch(context, backup, which == 1, null, callback))
                .setNegativeButton("取消", null).show();
    }

    public static void exportBackup(Context context, File backup) {
        new AlertDialog.Builder(context).setTitle("导出备份")
                .setMessage("备份含配置和解密资料，ZIP未加密，请仅保存到您信任的位置。")
                .setPositiveButton("选择保存位置", (dialog, which) -> launch(context, true, false, backup, null))
                .setNegativeButton("取消", null).show();
    }

    private static FragmentActivity host(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof FragmentActivity) return (FragmentActivity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private static void launch(Context context, boolean backup, boolean tree, File export, Callback callback) {
        FragmentActivity activity = host(context);
        if (activity == null || activity.getSupportFragmentManager().isStateSaved()) {
            Toast.makeText(context, "当前页面不能打开系统文件选择器", Toast.LENGTH_LONG).show();
            return;
        }
        if (activity.getSupportFragmentManager().findFragmentByTag(TAG) != null) {
            Toast.makeText(context, "已有导入或导出正在进行", Toast.LENGTH_SHORT).show();
            return;
        }
        ImportFragment fragment = new ImportFragment();
        Bundle args = new Bundle();
        args.putBoolean("backup", backup);
        args.putBoolean("tree", tree);
        if (export != null) args.putString("export", export.getAbsolutePath());
        fragment.setArguments(args);
        fragment.callback = callback;
        activity.getSupportFragmentManager().beginTransaction().add(fragment, TAG).commitNow();
        fragment.openPicker();
    }

    public static class ImportFragment extends Fragment {
        private Callback callback;
        private volatile boolean cancelled;
        private volatile InputStream activeInput;
        private volatile OutputStream activeOutput;
        private Thread worker;
        private ProgressDialog progress;
        private final Handler main = new Handler(Looper.getMainLooper());

        @Override public void onCreate(Bundle savedState) {
            super.onCreate(savedState);
            if (savedState != null && savedState.getBoolean("copyInProgress")) {
                main.post(() -> finish(null, "页面已重建，请重新导入；原配置和备份保持不变"));
            }
        }

        @Override public void onSaveInstanceState(Bundle state) {
            super.onSaveInstanceState(state);
            state.putBoolean("copyInProgress", worker != null && worker.isAlive());
        }

        void openPicker() {
            Bundle args = requireArguments();
            Intent intent;
            if (args.containsKey("export")) {
                intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "tvbox-backup.zip");
            } else if (args.getBoolean("tree")) {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            } else {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (args.containsKey("export")) intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { startActivityForResult(intent, 41); }
            catch (RuntimeException e) { finish(null, "系统没有可用文件选择器，请使用电脑配置上传"); }
        }

        @Override public void onActivityResult(int request, int result, Intent data) {
            super.onActivityResult(request, result, data);
            if (request != 41) return;
            if (result != Activity.RESULT_OK || data == null || (data.getData() == null && data.getClipData() == null)) {
                finish(null, "已取消，原文件和设置未更改");
                return;
            }
            final Context context = requireContext().getApplicationContext();
            final ContentResolver resolver = context.getContentResolver();
            final Bundle args = requireArguments();
            progress = new ProgressDialog(requireContext());
            progress.setMessage("正在复制，请稍候…");
            progress.setCancelable(true);
            progress.setOnCancelListener(dialog -> cancel());
            progress.show();
            worker = new Thread(() -> {
                File stage = null;
                File published = null;
                String message;
                try {
                    if (args.containsKey("export")) {
                        File source = new File(args.getString("export"));
                        WorkspaceIO.resolve(WorkspaceFiles.backups(), WorkspaceIO.relative(WorkspaceFiles.backups(), source));
                        if (data.getData() == null || !"content".equals(data.getData().getScheme())) {
                            throw new IOException("请选择系统文档保存位置");
                        }
                        activeOutput = resolver.openOutputStream(data.getData(), "w");
                        if (activeOutput == null) throw new IOException("不能写入所选位置");
                        WorkspaceIO.zip(source, activeOutput);
                        activeOutput = null;
                        message = "备份已导出";
                    } else {
                        stage = WorkspaceFiles.newStage();
                        WorkspaceIO.Budget budget = new WorkspaceIO.Budget();
                        if (args.getBoolean("tree")) {
                            Uri tree = data.getData();
                            copyTree(resolver, tree, DocumentsContract.getTreeDocumentId(tree), stage, stage,
                                    budget, new HashSet<>(), 0);
                        } else {
                            ClipData clips = data.getClipData();
                            int count = clips == null ? 1 : clips.getItemCount();
                            for (int i = 0; i < count; i++) {
                                Uri uri = clips == null ? data.getData() : clips.getItemAt(i).getUri();
                                String name = displayName(resolver, uri);
                                budget.entry();
                                if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                                    File dir = count == 1 ? stage : WorkspaceIO.directory(WorkspaceIO.resolve(stage, name + "-contents"));
                                    try (InputStream input = open(resolver, uri)) { WorkspaceIO.unzip(input, dir, budget); }
                                } else copyDocument(resolver, uri, WorkspaceIO.resolve(stage, name), budget);
                            }
                        }
                        checkCancelled();
                        if (args.getBoolean("backup")) requireBackup(stage);
                        // Cancellation and publication share a lock: never report a committed import as cancelled.
                        synchronized (this) {
                            checkCancelled();
                            published = WorkspaceIO.publish(stage, args.getBoolean("backup") ? WorkspaceFiles.backups() : WorkspaceFiles.root());
                        }
                        message = "已导入私有目录，原文件保持不变";
                    }
                } catch (Exception e) {
                    message = cancelled ? "已取消，原配置和备份未更改" : "导入/导出失败：" + e.getMessage();
                    if (args.containsKey("export")) message += "；所选导出文档可能不完整，请重新导出";
                } finally {
                    closeStreams();
                    if (stage != null && stage.exists()) {
                        try { WorkspaceIO.deleteTree(stage); } catch (IOException ignored) { }
                    }
                }
                File resultFile = published;
                String resultMessage = message;
                main.post(() -> finish(resultFile, resultMessage));
            }, "workspace-import");
            worker.start();
        }

        private static void requireBackup(File root) throws IOException {
            // Accept a backup's contents or ZIPs containing named backup directories.
            if (new File(root, "sqlite").isFile() && new File(root, "hawk").isFile()) return;
            File[] children = root.listFiles();
            if (children != null) for (File child : children) {
                if (child.isDirectory() && new File(child, "sqlite").isFile() && new File(child, "hawk").isFile()) return;
            }
            throw new IOException("未找到sqlite和hawk备份文件");
        }

        private void copyTree(ContentResolver resolver, Uri tree, String id, File stage, File target,
                              WorkspaceIO.Budget budget, Set<String> visited, int depth) throws IOException {
            checkCancelled();
            if (depth >= WorkspaceIO.MAX_DEPTH || !visited.add(id)) throw new IOException("目录过深或循环引用");
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE};
            try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
                if (cursor == null) throw new IOException("目录读取被拒绝");
                while (cursor.moveToNext()) {
                    checkCancelled();
                    budget.entry();
                    String childId = cursor.getString(0);
                    String name = WorkspaceIO.name(cursor.getString(1));
                    File dest = WorkspaceIO.resolve(stage, WorkspaceIO.relative(stage, new File(target, name)));
                    if (dest.exists()) throw new IOException("目录包含重复文件名");
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                        WorkspaceIO.directory(dest);
                        copyTree(resolver, tree, childId, stage, dest, budget, visited, depth + 1);
                    } else copyDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, childId), dest, budget);
                }
            }
        }

        private InputStream open(ContentResolver resolver, Uri uri) throws IOException {
            checkCancelled();
            if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("请选择系统文档");
            InputStream input = resolver.openInputStream(uri);
            if (input == null) throw new IOException("文件读取被拒绝");
            activeInput = input;
            return input;
        }

        private void copyDocument(ContentResolver resolver, Uri uri, File target, WorkspaceIO.Budget budget) throws IOException {
            if (target.exists()) throw new IOException("重复文件名");
            try (InputStream input = open(resolver, uri); FileOutputStream output = new FileOutputStream(target)) {
                budget.add(WorkspaceIO.copy(input, output, budget.remaining()));
                output.getFD().sync();
            }
            activeInput = null;
        }

        private static String displayName(ContentResolver resolver, Uri uri) throws IOException {
            try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) return WorkspaceIO.name(cursor.getString(0));
            }
            throw new IOException("所选文件没有有效名称");
        }

        private void checkCancelled() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("已取消");
        }

        private synchronized void cancel() {
            cancelled = true;
            if (worker != null) worker.interrupt();
            closeStreams();
        }

        private void closeStreams() {
            try { if (activeInput != null) activeInput.close(); } catch (IOException ignored) { }
            try { if (activeOutput != null) activeOutput.close(); } catch (IOException ignored) { }
            activeInput = null;
            activeOutput = null;
        }

        private void finish(File imported, String message) {
            if (progress != null) progress.dismiss();
            if (!isAdded()) return;
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            if (imported != null && callback != null) callback.complete(imported);
            getParentFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        }

        @Override public void onDestroy() {
            cancel();
            callback = null;
            if (progress != null) progress.dismiss();
            super.onDestroy();
        }
    }
}
