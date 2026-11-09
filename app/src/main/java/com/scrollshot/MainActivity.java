package com.scrollshot;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry screen: permission checklist, start capture, manual stitch, last result.
 *
 * v1.3: back to framework Activity (not AppCompatActivity). MaterialButton /
 * MaterialCardView are still used in the layout, but AppCompatActivity's
 * delegate was the most likely launch-crash source on Android 17 with the
 * older AppCompat 1.6.1. If the Material layout fails to inflate for any
 * reason, fall back to a framework-only layout so the app at least opens.
 */
public class MainActivity extends Activity {

    private static final int REQ_MEDIA_PROJECTION = 2001;

    // v1.39.46: CaptureService launches MainActivity with this
    // action when a capture's mirror pipeline is found dead at
    // frame 1 (the starvation class: captures 45/55/59/60) -
    // the activity goes straight to the system consent dialog
    // so the session can be re-armed in place.
    public static final String ACTION_RECONSENT = "com.scrollshot.action.RECONSENT";
    private static final int REQ_PICK_IMAGES = 2002;
    private static final int REQ_POST_NOTIFICATIONS = 2003;
    private boolean pendingDeleteAfterGrant = false;

    private TextView tvStatus;
    private TextView tvAccStatus;
    private TextView tvOverlayStatus;
    private TextView tvNotifStatus;
    private TextView tvLog;
    private TextView tvVersion;
    private TextView tvDiagStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
        } catch (Throwable t) {
            CaptureSession.lastError = "主布局加载失败，已切换兼容模式：" + t;
            try {
                setContentView(R.layout.activity_main_fallback);
            } catch (Throwable t2) {
                // Last resort: never crash on launch because of a layout.
                // Show a plain text screen instead of dying.
                CaptureSession.lastError = "两个布局都加载失败：" + t2;
                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setText("ScrollShot 启动失败：布局加载失败，请重装一次。\n" + t2);
                tv.setPadding(32, 32, 32, 32);
                setContentView(tv);
                return;
            }
        }
        tvStatus = findViewById(R.id.tvStatus);
        tvAccStatus = findViewById(R.id.tvAccStatus);
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus);
        tvNotifStatus = findViewById(R.id.tvNotifStatus);
        tvLog = findViewById(R.id.tvLog);
        tvVersion = findViewById(R.id.tvVersion);
        tvDiagStatus = findViewById(R.id.tvDiagStatus);
        if (tvVersion != null) tvVersion.setText("v" + appVersionName());
        if (tvDiagStatus != null) {
            tvDiagStatus.setText(CaptureService.diagnosticsEnabled(this) ? "诊断模式已开" : "诊断模式已关");
        }

        Button btnOneKey = findViewById(R.id.btnOneKey);
        Button btnAccessibility = findViewById(R.id.btnAccessibility);
        Button btnOverlay = findViewById(R.id.btnOverlay);
        Button btnNotification = findViewById(R.id.btnNotification);
        Button btnStart = findViewById(R.id.btnStart);
        Button btnManual = findViewById(R.id.btnManual);
        Button btnLast = findViewById(R.id.btnLast);

        btnOneKey.setOnClickListener(v -> {
            // One-tap flow: open only the first missing permission, skip granted ones.
            // Three steps: accessibility, overlay, notifications.
            if (!isAccessibilityEnabled()) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "先开无障碍，回来后会继续下一步", Toast.LENGTH_LONG).show();
            } else if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, "再允许悬浮窗，回来后会继续下一步", Toast.LENGTH_LONG).show();
            } else if (!notificationsEnabled()) {
                requestNotifications();
            } else {
                Toast.makeText(this, "三项权限都已授权，无需重复授权", Toast.LENGTH_SHORT).show();
            }
        });
        btnAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "在列表里找到 ScrollShot 并开启", Toast.LENGTH_LONG).show();
        });
        btnOverlay.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Toast.makeText(this, "悬浮窗已允许", Toast.LENGTH_SHORT).show();
            }
        });
        btnNotification.setOnClickListener(v -> {
            if (notificationsEnabled()) {
                Toast.makeText(this, "通知已允许", Toast.LENGTH_SHORT).show();
            } else {
                requestNotifications();
            }
        });
        btnStart.setOnClickListener(v -> startCapture());
        // v1.39.46: re-consent entry (see ACTION_RECONSENT).
        if (ACTION_RECONSENT.equals(getIntent().getAction())) {
            getIntent().setAction(null); // consume: never relaunch on recreation
            launchReconsent();
        }
        btnManual.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "选择多张截图"), REQ_PICK_IMAGES);
        });
        CheckBox chkDiagnostics = findViewById(R.id.chkDiagnostics);
        if (chkDiagnostics != null) {
            chkDiagnostics.setChecked(CaptureService.diagnosticsEnabled(this));
            chkDiagnostics.setOnCheckedChangeListener((v, checked) -> {
                CaptureService.setDiagnosticsEnabled(this, checked);
                if (!checked) CaptureService.clearDebugOutput();
                // Status is reported on the line under the switch instead
                // of a floating toast.
                if (tvDiagStatus != null) {
                    tvDiagStatus.setText(checked ? "诊断模式已开" : "诊断模式已关");
                }
            });
            // The whole row toggles the switch, not just the 52dp knob.
            View rowDiag = findViewById(R.id.rowDiagnostics);
            if (rowDiag != null) {
                rowDiag.setOnClickListener(v -> chkDiagnostics.toggle());
            }
        }
        // Delete button next to the diagnostics switch: one tap
        // removes the whole Download/ScrollShot folder. The button
        // is clickable itself, so tapping it does not toggle the
        // switch through the row's click listener.
        View btnClearDiag = findViewById(R.id.btnClearDiag);
        if (btnClearDiag != null) {
            btnClearDiag.setOnClickListener(v -> onClearDiagClicked());
        }
        btnLast.setOnClickListener(v -> {
            String preview = CaptureSession.lastPreviewPath;
            if (preview != null && new java.io.File(preview).exists()) {
                Intent intent = new Intent(this, ResultActivity.class);
                intent.putExtra(ResultActivity.EXTRA_PATH, preview);
                startActivity(intent);
            } else if (CaptureSession.lastSavedUri != null) {
                Intent intent = new Intent(this, ResultActivity.class);
                intent.putExtra(ResultActivity.EXTRA_URI, CaptureSession.lastSavedUri);
                startActivity(intent);
            } else {
                Toast.makeText(this, "还没有长图，先截一张或手动拼一张", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (pendingDeleteAfterGrant) {
            pendingDeleteAfterGrant = false;
            if (CaptureService.hasAllFilesAccess()
                    && CaptureService.scrollShotFolderExists()) {
                showDeleteConfirm();
            }
        }
        StringBuilder log = new StringBuilder();
        if (CaptureSession.statusText != null && !CaptureSession.statusText.isEmpty()) {
            log.append("服务状态：").append(CaptureSession.statusText).append("\n");
        }
        if (CaptureSession.lastError != null) {
            log.append("上次提示：").append(CaptureSession.lastError);
        }
        if (log.length() > 0) {
            tvLog.setText(log.toString());
            tvLog.setVisibility(View.VISIBLE);
        } else {
            tvLog.setText("");
            // An empty log line still reserves a line of height, which
            // pushed the page past one screen; hide it until it has text.
            tvLog.setVisibility(View.GONE);
        }
    }

    private boolean notificationsEnabled() {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    private void requestNotifications() {
        // Runtime dialog where the platform supports it (API 33+;
        // POST_NOTIFICATIONS is declared in the manifest). Older
        // platforms have no runtime permission: go straight to the
        // app's notification settings page.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQ_POST_NOTIFICATIONS);
        } else {
            openNotificationSettings();
        }
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "通知已允许，三项权限齐了", Toast.LENGTH_SHORT).show();
                refreshStatus();
            } else {
                // Denied in the dialog: fall back to the settings page,
                // where the switch can still be turned on manually.
                Toast.makeText(this, "弹窗里没允许，带你去设置页手动开", Toast.LENGTH_LONG).show();
                openNotificationSettings();
            }
        }
    }

    private void refreshStatus() {
        boolean acc = isAccessibilityEnabled();
        boolean connected = CaptureSession.accessibilityService != null;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notif = notificationsEnabled();
        if (tvAccStatus == null || tvOverlayStatus == null || tvNotifStatus == null) {
            // Compatibility layout: no per-row status labels, keep the
            // original multi-line summary in the single status text.
            String s = "无障碍：" + (acc ? (connected ? "已开启 · 服务已连接" : "已开启 · 服务未连接") : "未开启")
                    + "\n悬浮窗：" + (overlay ? "已允许" : "未允许")
                    + "\n通知：" + (notif ? "已允许" : "未允许");
            if (tvStatus != null) {
                tvStatus.setText(s);
                tvStatus.setVisibility(View.VISIBLE);
            }
            return;
        }
        // Status shown as a pill at the right end of each permission row.
        setRowStatus(tvAccStatus,
                acc ? (connected ? "已开启" : "未连接") : "未开启",
                acc && connected);
        setRowStatus(tvOverlayStatus, overlay ? "已允许" : "未允许", overlay);
        setRowStatus(tvNotifStatus, notif ? "已允许" : "未允许", notif);
        // tvStatus is now only the extra warning line below the card.
        if (tvStatus != null) {
            if (acc && !connected) {
                tvStatus.setText("设置里已开启但服务未连接，请到无障碍设置把 ScrollShot 关掉再打开一次。");
                tvStatus.setVisibility(View.VISIBLE);
            } else {
                tvStatus.setVisibility(View.GONE);
            }
        }
    }

    private void setRowStatus(TextView view, String text, boolean granted) {
        if (view == null) return;
        view.setText(text);
        view.setTextColor(getResources().getColor(
                granted ? R.color.ss_pill_ok_text : R.color.ss_pill_warn_text, getTheme()));
        view.setBackgroundResource(granted ? R.drawable.bg_pill_ok : R.drawable.bg_pill_warn);
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private boolean isAccessibilityEnabled() {
        // 1) Service already connected in this process: definitely enabled.
        if (CaptureSession.accessibilityService != null) return true;
        // 2) AccessibilityManager list (tolerant: match by package + class name part).
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                for (android.accessibilityservice.AccessibilityServiceInfo info :
                        am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                    String id = info.getId();
                    if (id != null && id.contains("com.scrollshot") && id.contains("ScrollAccessibilityService")) return true;
                }
            }
        } catch (Exception ignored) {}
        // 3) Settings string, tolerant: old code required exact
        // "com.scrollshot/com.scrollshot.ScrollAccessibilityService", but some
        // ROMs store the short form "com.scrollshot/.ScrollAccessibilityService".
        // Accept any entry that mentions both our package and our service class.
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (!TextUtils.isEmpty(enabled)) {
            for (String s : enabled.split(":")) {
                if (s.contains("com.scrollshot") && s.contains("ScrollAccessibilityService")) return true;
            }
        }
        return false;
    }

    // v1.39.46: re-consent entry points. The service sends the
    // user here when the mirror pipeline died; go straight to
    // the system consent dialog (the permission checks in
    // startCapture() already passed when the session was first
    // armed - re-running them could divert to settings pages).
    // onActivityResult handles the result exactly like a normal
    // arm: the fresh grant re-arms the service (ACTION_START).
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) {
            setIntent(intent);
            if (ACTION_RECONSENT.equals(intent.getAction())) {
                intent.setAction(null);
                launchReconsent();
            }
        }
    }

    private void launchReconsent() {
        try {
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            Intent intent = mgr.createScreenCaptureIntent();
            CaptureSession.statusText = "录屏画面中断，正在重新授权…";
            startActivityForResult(intent, REQ_MEDIA_PROJECTION);
        } catch (Exception e) {
            CaptureSession.statusText = "重新授权打开失败：" + e.getMessage();
            Toast.makeText(this, "重新授权打开失败，请回 App 点开始重试", Toast.LENGTH_LONG).show();
        }
    }

    private void startCapture() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            return;
        }
        if (CaptureSession.accessibilityService == null) {
            // Settings say enabled but the service is not connected
            // in this process - a state app updates leave behind on
            // some OEM ROMs until the service is toggled off/on.
            // Starting anyway used to degrade silently into manual
            // mode and save a garbage capture of a page nothing was
            // scrolling (2026-11-04 capture: ~20px of drift per
            // "scroll", torn result). Refuse, and send the user to
            // the one action that fixes the state.
            Toast.makeText(this, "无障碍已开启但服务未连接：请把 ScrollShot 关掉再打开一次，然后回来开始", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先允许悬浮窗，否则无法点开始/停止", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent intent = mgr.createScreenCaptureIntent();
        startActivityForResult(intent, REQ_MEDIA_PROJECTION);
    }

    // Confirmation prompt in the app's own glass UI (custom
    // layout, colors and button styles from the main screen),
    // deliberately not the stock system AlertDialog look.
    // okDanger renders the confirm button red.
    private void showAppDialog(String title, String message, String okText,
                               boolean okDanger, final Runnable onOk) {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_confirm, null);
        dlg.setContentView(view);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
            dlg.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        TextView tvTitle = view.findViewById(R.id.tvDialogTitle);
        TextView tvMessage = view.findViewById(R.id.tvDialogMessage);
        Button btnCancel = view.findViewById(R.id.btnDialogCancel);
        Button btnOk = view.findViewById(R.id.btnDialogOk);
        tvTitle.setText(title);
        tvMessage.setText(message);
        btnOk.setText(okText);
        if (okDanger) btnOk.setBackgroundResource(R.drawable.bg_dialog_danger);
        btnCancel.setOnClickListener(v -> dlg.dismiss());
        btnOk.setOnClickListener(v -> {
            dlg.dismiss();
            if (onOk != null) onOk.run();
        });
        dlg.show();
    }

    // Delete flow for the Download/ScrollShot folder. With
    // all-files access (granted once in system settings), plain
    // file deletion removes the whole folder - including files a
    // previous install created - so a tap is just: confirm in the
    // app dialog, delete, done. Without the access, the first tap
    // explains and jumps to the settings toggle; on return, the
    // confirmation dialog comes up by itself.
    private void onClearDiagClicked() {
        if (!CaptureService.scrollShotFolderExists()) {
            Toast.makeText(this, "Download/ScrollShot 文件夹不存在，无需删除",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (CaptureService.hasAllFilesAccess()) {
            showDeleteConfirm();
            return;
        }
        pendingDeleteAfterGrant = true;
        showAppDialog("需要先开启一个权限",
                "删除旧版本留下的文件夹，需要一次性开启「所有文件访问权限」（在系统设置里给 ScrollShot 打开）。开启后回来确认即可删除，之后每次都直接删，不再询问。",
                "去开启", false, () -> openAllFilesSettings());
    }

    private void showDeleteConfirm() {
        showAppDialog("删除 Download/ScrollShot",
                "将删除该文件夹及其中的全部文件（包括旧版本留下的），确认删除？",
                "删除", true, () -> reportFileDelete(
                        CaptureService.deleteScrollShotDownloads()));
    }

    private void reportFileDelete(int deletedFiles) {
        if (deletedFiles == -2) {
            Toast.makeText(this, "已拦截：目标路径异常，未执行删除",
                    Toast.LENGTH_LONG).show();
        } else if (deletedFiles < 0 || !CaptureService.scrollShotFolderExists()) {
            if (deletedFiles < 0) {
                Toast.makeText(this, "Download/ScrollShot 文件夹不存在，无需删除",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已删除 Download/ScrollShot 文件夹",
                        Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "删除未完成：文件夹仍然存在（已删掉 "
                    + deletedFiles + " 个文件）", Toast.LENGTH_LONG).show();
        }
    }

    private void openAllFilesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e2) {
                pendingDeleteAfterGrant = false;
                Toast.makeText(this, "无法打开设置，请到系统设置里手动开启「所有文件访问权限」",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Keep the grant in memory as well as in the Intent, to avoid parceling issues.
                CaptureSession.mediaProjectionResultCode = resultCode;
                CaptureSession.mediaProjectionData = data;
                CaptureSession.statusText = "已拿到录屏授权，正在启动服务…";
                Intent svc = new Intent(this, CaptureService.class);
                svc.setAction(CaptureService.ACTION_START);
                svc.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(CaptureService.EXTRA_DATA, data);
                startForegroundService(svc);
                moveTaskToBack(true);
                Toast.makeText(this, "已就绪：切换到目标页面，点悬浮窗“开始”", Toast.LENGTH_LONG).show();
            } else {
                CaptureSession.statusText = "未同意录屏（resultCode=" + resultCode + "，data=" + (data == null ? "空" : "有") + "）";
                Toast.makeText(this, "未同意录屏，无法截屏", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_PICK_IMAGES && resultCode == Activity.RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    uris.add(clip.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) manualStitch(uris);
        }
    }

    private void manualStitch(List<Uri> uris) {
        new Thread(() -> {
            try {
                List<Bitmap> frames = new ArrayList<>();
                for (Uri uri : uris) {
                    try (InputStream is = getContentResolver().openInputStream(uri)) {
                        Bitmap b = BitmapFactory.decodeStream(is);
                        if (b != null) {
                            if (b.getWidth() > 1440) {
                                Bitmap scaled = Bitmap.createScaledBitmap(b, 1440,
                                        (int) Math.round((double) b.getHeight() * 1440 / b.getWidth()), true);
                                b.recycle();
                                b = scaled;
                            }
                            frames.add(b);
                        }
                    }
                }
                if (frames.size() < 2) {
                    runOnUiThread(() -> Toast.makeText(this, "请至少选 2 张图", Toast.LENGTH_SHORT).show());
                    return;
                }
                Bitmap stitched = frames.get(0);
                for (int i = 1; i < frames.size(); i++) {
                    Stitcher.Result r = Stitcher.stitchVertical(stitched, frames.get(i), 0.35, 0.05);
                    Bitmap old = stitched;
                    stitched = r.bitmap;
                    if (old != stitched && old != frames.get(i)) old.recycle();
                }
                String previewPath = CaptureSession.writePreview(this, stitched);
                stitched.recycle();
                CaptureSession.lastPreviewPath = previewPath;
                final String pathFinal = previewPath;
                runOnUiThread(() -> {
                    if (pathFinal != null) {
                        Intent intent = new Intent(this, ResultActivity.class);
                        intent.putExtra(ResultActivity.EXTRA_PATH, pathFinal);
                        startActivity(intent);
                    } else {
                        Toast.makeText(this, "生成预览失败", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "拼接失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
