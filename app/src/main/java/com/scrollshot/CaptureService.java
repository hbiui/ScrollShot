package com.scrollshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service that owns the MediaProjection, the floating
 * Start/Stop overlay, the capture loop and the final stitch + save.
 *
 * Flow:
 *  1. MainActivity grants MediaProjection and starts this service (ACTION_START).
 *  2. Service goes foreground, creates a VirtualDisplay + ImageReader,
 *     and shows a small overlay with a Start button. The user navigates
 *     to the target page and taps Start.
 *  3. Capture loop (background thread):
 *       hide overlay -> capture frame -> crop status/nav bars -> show overlay
 *       -> stitch with overlap matching -> ask AccessibilityService to scroll
 *       -> repeat until no node scrolls, the frame stops changing, the user
 *         taps Stop, or MAX_FRAMES is reached.
 *  4. The stitched bitmap is written to the app cache and a notification
 *     opens ResultActivity, where the user previews and taps 保存长图 to
 *     put it into Pictures/ScrollShot. The overlay is removed.
 *
 * The overlay is hidden before every frame capture, so it never appears
 * in the stitched image.
 */
public class CaptureService extends Service {

    public static final String ACTION_START = "com.scrollshot.ACTION_START";
    public static final String ACTION_BEGIN = "com.scrollshot.ACTION_BEGIN";
    public static final String ACTION_STOP = "com.scrollshot.ACTION_STOP";
    public static final String ACTION_QUIT = "com.scrollshot.ACTION_QUIT";
    public static final String ACTION_TEST_OVERLAY = "com.scrollshot.ACTION_TEST_OVERLAY";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "data";

    private static final String CHANNEL_ID = "scrollshot_capture";
    private static final int NOTIF_ID = 1001;
    private static final int MAX_FRAMES = 40;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private WindowManager windowManager;
    private View overlayView;
    private ImageButton overlayStart;
    private ImageButton overlayStop;
    private ImageButton overlayQuit;
    private WindowManager.LayoutParams overlayParams;
    private int overlayW, overlayH;

    private HandlerThread captureThread;
    private Handler captureHandler;
    private volatile boolean loopRunning = false;
    private volatile boolean stopRequested = false;
    private volatile boolean volumeChangeStop = false;
    // v1.39.43: human-readable provenance of this capture's
    // stop, written by whichever path requested it and printed
    // in the END block's SUMMARY line. Until now the log made a
    // false stop indistinguishable from a real key press
    // (capture 47 read as a manual stop the user never made).
    private String loopStopReason = "";
    private volatile boolean startedFromNotification = false;
    private volatile int captureStartVolume = -1;
    private volatile boolean panelStop = false;
    // v1.39.45: set by confirmPanelSuspect when the suspect
    // could not be cleared as content (probe transient with no
    // pill window, or unverifiable): the caller retakes the
    // frame once instead of stitching a possibly covered one.
    private boolean panelSuspectRetake = false;
    // v1.39.46: re-consent fallback may fire at most once per capture.
    private boolean reconsentTried = false;
    // v1.39.48: a floating bottom proposal (useTop) awaiting
    // cross-scale reproduction before its implied bottom cut
    // may be trusted; -1 = none pending.
    private int pendingFloatUseTop = -1;
    // Why the most recent captureFrameWithRetry gave up
    // (v1.39.25) - written into the capture log when a grab
    // failure ends the loop, so a zero-frame capture explains
    // itself instead of going silent.
    private String lastGrabFailure = "";
    // The most recent finished (cropped, non-blank) frame
    // the reader produced (v1.39.29). When the reader queue
    // runs dry on a perfectly static screen, this still IS
    // the current screen: a new image is only queued when
    // something actually repaints.
    private Bitmap lastScreenFrame = null;
    // (v1.39.20's media-session volume route lived here; it
    // was removed in v1.39.22 - see the note at the watcher.)
    private Bitmap panelPrevThumb;
    private String lastWinSig = "";
    private volatile boolean quitRequested = false;
    // Capture-loop state shared with stitchOneFrame() (the loop was
    // restructured in v1.20 so deferred detection frames can be
    // stitched through the same unit).
    private Bitmap loopStitched = null;
    private Bitmap loopPreviousFrame = null;
    // Zero-scroll guard (v1.35): set when two consecutive joins
    // moved the document by only a small fraction of the gesture
    // that was performed - the page is not scrolling at all, so
    // the capture aborts instead of saving garbage, and the
    // finally block skips the save when this is set.
    private volatile boolean abortCapture = false;
    // v1.39.47: set by stitchOneFrame when a step-prior join
    // fails verification (content gap): the capture ends and
    // the certified canvas is SAVED (unlike abortCapture).
    private volatile boolean gapStop = false;
    private int loopZeroStreak = 0;
    // Premature-end guard (v1.37): a single near-identical frame can be
    // a transient hiccup (page reflow, lazy image load, gesture absorbed
    // by a nested container), not proof that the current node is done.
    // Only mark it exhausted after two consecutive unchanged frames.
    private int loopUnchangedStreak = 0;
    // Last realized scroll step (canvas height added by the join) and
    // the gesture distance that produced it. Used by the end-of-page
    // check: if the page barely moved despite a large intended swipe,
    // the node was almost certainly marked exhausted by a transient
    // gesture failure rather than reaching the bottom, so we reset and
    // retry instead of stopping.
    private int lastRealizedStep = 0;
    private int lastIntendedStep = 0;
    private int loopJoins = 0;
    private boolean loopAnyScrollOk = false;
    // Bottom of any screen-fixed chrome still inside the content
    // frames (content coordinates): the seam placement in the
    // stitcher must not splice above this row (v1.36).
    private int loopChromeBottom = 0;
    private double loopStepFloor = 0.20;
    private double loopStepCap = 0.40;
    // Confirmed mid-screen fixed elements (content coordinates
    // {x0, y0, x1, y1}) and the canvas rows still waiting for
    // their deferred fill ({canvasRow, rectIndex, age}).
    private final java.util.List<int[]> loopFixedRects = new java.util.ArrayList<>();
    private final java.util.List<int[]> loopPendings = new java.util.ArrayList<>();
    // Realized scroll steps (frame height - effective overlap)
    // of this capture's MATCHED joins, in order. When a later
    // join's matcher fails, the session's own recent steps are
    // far better evidence for the fallback overlap than the
    // nominal gesture distance: feed momentum made real steps
    // overshoot the intended one by ~330px on device captures
    // 43 and 46, and the nominal fallback tore those seams.
    private final java.util.List<Integer> loopRealizedSteps = new java.util.ArrayList<>();
    // v1.39.43 diagnostic counters (log-only).
    private int loopKickCount = 0;
    private int loopJoinsMatched = 0;
    private int loopJoinsUnmatched = 0;
    // The system status bar cropped off every frame: cropBars
    // stashes the latest strip, captureFrameWithRetry adopts the
    // first accepted frame's strip, and the save path draws it
    // back at the very top of the long image (v1.30 - otherwise
    // the result starts at the app content and the system
    // status bar the user saw is simply missing).
    private Bitmap statusStrip = null;
    private Bitmap pendingTopStrip = null;
    private int loopFrames = 0;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private int statusBarHeight;
    private int navBarHeight;

    @Override
    public void onCreate() {
        super.onCreate();
        CaptureSession.captureService = this;
        createChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        Display display = windowManager.getDefaultDisplay();
        display.getRealMetrics(dm);
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        screenDensity = dm.densityDpi;
        statusBarHeight = getSystemBarHeight("status_bar_height");
        navBarHeight = getSystemBarHeight("navigation_bar_height");
        // Navigation bar height is only real on 3-button navigation; gesture
        // navigation reports a height too, but cropping it would cut content.
        // We therefore only crop it if it is a small, plausible bar.
        if (navBarHeight > screenHeight / 8) navBarHeight = 0;
    }

    private int getSystemBarHeight(String name) {
        Resources res = getResources();
        int id = res.getIdentifier(name, "dimen", "android");
        if (id > 0) {
            try { return res.getDimensionPixelSize(id); } catch (Exception ignored) {}
        }
        return 0;
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "滚动截屏", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("ScrollShot 正在截屏");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent stop = new Intent(this, CaptureService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // The notification used to carry only a Stop action, so if the
        // overlay's Start button was ever not visible there was NO way
        // to start a capture at all. Give the notification its own
        // Start action too (distinct request code from Stop).
        Intent begin = new Intent(this, CaptureService.class);
        begin.setAction(ACTION_BEGIN);
        PendingIntent beginPi = PendingIntent.getService(this, 1, begin,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent main = new Intent(this, MainActivity.class);
        PendingIntent mainPi = PendingIntent.getActivity(this, 0, main,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                // v1.39.51: put Start/Stop INSIDE a custom content
        // view. Standard notification actions only render in
        // the EXPANDED notification, and on Android 17 /
        // MagicOS the shade shows this notification contracted,
        // so Hayes had to expand it before the buttons even
        // appeared. A custom content view is what the system
        // renders in the CONTRACTED state, so the same two
        // buttons (same PendingIntents, same behaviour) are
        // visible and tappable the moment the shade opens.
        // Title/text and the standard actions stay as the
        // fallback for surfaces that ignore custom views.
        android.widget.RemoteViews rv = new android.widget.RemoteViews(
                getPackageName(), R.layout.notification_capture);
        rv.setTextViewText(R.id.notif_text, text);
        rv.setOnClickPendingIntent(R.id.notif_btn_start, beginPi);
        rv.setOnClickPendingIntent(R.id.notif_btn_stop, stopPi);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ScrollShot 滚动截屏")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(mainPi)
                .setCustomContentView(rv)
                .setCustomBigContentView(rv)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_play),
                        "开始", beginPi).build())
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                        "停止", stopPi).build())
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_BEGIN.equals(action)) {
            // Start from the notification. Same entry point the overlay
            // Start button uses, so both paths behave identically.
            if (!loopRunning) {
                if (mediaProjection == null) {
                    Toast.makeText(this, "录屏还没准备好，请先回 App 点一次开始滚动截屏并允许录屏", Toast.LENGTH_LONG).show();
                    updateNotification("录屏还没准备好，请先回 App 授权一次");
                } else {
                    // Started from the notification: the shade
                    // is still open. Collapse it first, and let
                    // the loop wait for the target app to be
                    // focused before the first frame (see
                    // captureLoop) - otherwise the run captures
                    // the shade itself, nothing scrolls and the
                    // capture ends with "没有截到任何画面".
                    startedFromNotification = true;
                    if (CaptureSession.accessibilityService != null) {
                        CaptureSession.accessibilityService.dismissNotificationShade();
                    }
                    startCaptureLoop();
                }
            }
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            if (loopRunning) {
                // Capturing: stop the loop (it will save and show the result).
                // Give immediate feedback so the tap does not feel dead.
                stopRequested = true;
                updateNotification("正在停止截屏…");
            } else {
                // Idle: there is no capture to stop, so Stop means quit the
                // service entirely (remove overlay + notification). Old code
                // only set a flag here, so tapping Stop while idle did nothing
                // visible at all.
                cleanupAndStop();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_QUIT.equals(action)) {
            quitRequested = true;
            stopRequested = true;
            if (!loopRunning) {
                cleanupAndStop();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_TEST_OVERLAY.equals(action)) {
            startForeground(NOTIF_ID, buildNotification("测试：悬浮窗测试，点悬浮窗“开始”可试截屏"));
            showOverlay();
            CaptureSession.statusText = "测试服务已启动，悬浮窗应已显示";
            return START_STICKY;
        }
        if (ACTION_START.equals(action)) {
            // Prefer the in-memory grant (no parceling); fall back to the Intent extra.
            int resultCode = CaptureSession.mediaProjectionResultCode;
            Intent data = CaptureSession.mediaProjectionData;
            if (data == null) {
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
                } else {
                    data = intent.getParcelableExtra(EXTRA_DATA);
                }
            }
            startForeground(NOTIF_ID, buildNotification("已就绪：请切换到目标页面，点悬浮窗“开始”"));
            CaptureSession.statusText = "服务已启动，正在启动录屏…";
            boolean ok = startProjection(resultCode, data);
            showOverlay();
            if (!ok) {
                // Do NOT stop the service on projection failure: keep the
                // foreground notification and overlay visible with the error,
                // otherwise the user sees nothing at all (the v1.4 symptom).
                CaptureSession.statusText = "录屏启动失败：" + CaptureSession.lastError + "，请回到 App 重新点开始";
                updateNotification("录屏启动失败，请回到 App 重新点开始");
            } else {
                CaptureSession.statusText = "服务已启动，悬浮窗已显示，可点开始";
            }
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    // v1.39.46: bring MainActivity forward in re-consent mode;
    // it launches the system consent dialog immediately, and
    // its onActivityResult re-arms this service with the fresh
    // grant (ACTION_START -> startProjection, which releases
    // the dead trio first). The failed capture itself is not
    // resumed in place: after consenting, the user switches
    // back to the target page and taps Start (notification or
    // overlay) - but the app/session no longer needs a restart.
    private void requestReconsent() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.setAction(MainActivity.ACTION_RECONSENT);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception e) {
            if (capLog != null) capLog.append("re-consent launch failed: ").append(String.valueOf(e)).append("\n");
        }
    }

    private boolean startProjection(int resultCode, Intent data) {
        try {
            if (data == null) {
                CaptureSession.lastError = "录屏授权数据为空（未拿到系统返回的 Intent）";
                Toast.makeText(this, "录屏授权数据为空，请重试", Toast.LENGTH_LONG).show();
                return false;
            }
            // v1.39.46: re-arming (the re-consent fallback lands
            // here with a FRESH grant) must release the previous
            // projection trio first - the old (possibly dead)
            // display/reader would otherwise leak and linger.
            if (virtualDisplay != null) {
                try { virtualDisplay.release(); } catch (Exception ignored) {}
                virtualDisplay = null;
            }
            if (imageReader != null) {
                try { imageReader.close(); } catch (Exception ignored) {}
                imageReader = null;
            }
            if (mediaProjection != null) {
                try { mediaProjection.stop(); } catch (Exception ignored) {}
                mediaProjection = null;
            }
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = mgr.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                CaptureSession.lastError = "系统返回的 MediaProjection 为空";
                Toast.makeText(this, "录屏授权失败，请重试", Toast.LENGTH_LONG).show();
                return false;
            }
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopRequested = true;
                }
            }, null);
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay("ScrollShot",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null);
            return true;
        } catch (Exception e) {
            CaptureSession.lastError = e.toString();
            Toast.makeText(this, "启动录屏失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void showOverlay() {
        if (overlayView != null) {
            // Overlay already exists from a kept-alive session: make sure
            // its buttons are back in the idle state, so Start is there.
            if (!loopRunning) {
                mainThread(() -> {
                    showOverlayAgain();
                    if (overlayStart != null) overlayStart.setVisibility(View.VISIBLE);
                    if (overlayStop != null) overlayStop.setVisibility(View.GONE);
                });
            }
            return;
        }
        // Small, fixed-size icon bar. No text, no dynamic width, no
        // animation: the overlay is visible during polling and must not
        // change on its own. Progress/status lives in the notification.
        int btn = dp(34);
        int dragW = dp(18);
        int pad = dp(3);
        int height = dp(38);
        overlayW = btn * 2 + dragW + pad * 2;
        overlayH = height;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(pad, 0, pad, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C1E);
        bg.setCornerRadius(height / 2.0f);
        layout.setBackground(bg);

        overlayStart = makeOverlayButton(R.drawable.ic_overlay_play, 0xFF0A84FF);
        overlayStart.setContentDescription("开始");
        overlayStart.setVisibility(View.VISIBLE);
        overlayStart.setOnClickListener(v -> {
            startedFromNotification = false;
            startCaptureLoop();
        });

        overlayStop = makeOverlayButton(R.drawable.ic_overlay_stop, 0xFFFF453A);
        overlayStop.setContentDescription("停止");
        overlayStop.setVisibility(View.GONE);
        overlayStop.setOnClickListener(v -> stopRequested = true);

        overlayQuit = makeOverlayButton(R.drawable.ic_overlay_close, 0xFF8E8E93);
        overlayQuit.setContentDescription("退出");
        overlayQuit.setOnClickListener(v -> {
            // The overlay's close button closes ONLY the overlay
            // (Hayes, 2026-11-06: the overlay and the notification
            // exist independently - closing one must not drag the
            // other down with it). The service, the screen-capture
            // grant and the notification all stay alive: captures
            // can still be started and stopped from the
            // notification, and opening the app re-creates the
            // overlay (ACTION_START -> showOverlay). The full exit
            // lives on the notification's Stop action while idle,
            // which is the notification's own independent close.
            if (loopRunning) {
                stopRequested = true;
            } else {
                removeOverlay();
                CaptureSession.statusText = "悬浮窗已关闭（服务和通知仍在运行）";
                updateNotification("悬浮窗已关闭：可从通知开始截屏，打开 App 可恢复悬浮窗");
            }
        });

        ImageView dragHandle = new ImageView(this);
        dragHandle.setImageResource(R.drawable.ic_overlay_drag);
        dragHandle.setColorFilter(0xFF8E8E93);
        dragHandle.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        // Start and Stop share one fixed slot, so the bar never changes
        // width or shifts its icons when capture state changes.
        FrameLayout toggleSlot = new FrameLayout(this);
        toggleSlot.addView(overlayStart, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        toggleSlot.addView(overlayStop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        layout.addView(toggleSlot, new LinearLayout.LayoutParams(btn, height));
        layout.addView(overlayQuit, new LinearLayout.LayoutParams(btn, height));
        dragHandle.setContentDescription("拖动");
        layout.addView(dragHandle, new LinearLayout.LayoutParams(dragW, height));

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                overlayW,
                overlayH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences prefs = getSharedPreferences("scrollshot_overlay", MODE_PRIVATE);
        params.x = prefs.getInt("x", (screenWidth - overlayW) / 2);
        params.y = prefs.getInt("y", dp(80));
        overlayParams = params;
        clampOverlay(params);

        // Drag the window by its grip or empty background. Buttons keep
        // their own taps; only the grip/background moves the window.
        View.OnTouchListener drag = new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            private boolean moved;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = overlayParams.x;
                        startY = overlayParams.y;
                        downX = event.getRawX();
                        downY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (!moved && Math.hypot(dx, dy) < dp(6)) return true;
                        moved = true;
                        overlayParams.x = startX + (int) dx;
                        overlayParams.y = startY + (int) dy;
                        clampOverlay(overlayParams);
                        try {
                            windowManager.updateViewLayout(layout, overlayParams);
                        } catch (Exception ignored) {}
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            getSharedPreferences("scrollshot_overlay", MODE_PRIVATE)
                                    .edit()
                                    .putInt("x", overlayParams.x)
                                    .putInt("y", overlayParams.y)
                                    .apply();
                        }
                        return moved;
                    default:
                        return false;
                }
            }
        };
        dragHandle.setOnTouchListener(drag);
        layout.setOnTouchListener(drag);

        try {
            windowManager.addView(layout, params);
            overlayView = layout;
        } catch (Exception e) {
            Toast.makeText(this, "悬浮窗失败，请用通知里的停止按钮：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private ImageButton makeOverlayButton(int resId, int tint) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(resId);
        b.setColorFilter(tint);
        b.setBackgroundColor(0x00000000);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        int p = dp(7);
        b.setPadding(p, p, p, p);
        return b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Keep the dragged window on screen, below the status bar. */
    private void clampOverlay(WindowManager.LayoutParams p) {
        if (screenWidth <= 0 || screenHeight <= 0 || overlayW <= 0 || overlayH <= 0) return;
        int margin = dp(4);
        int top = getSystemBarHeight("status_bar_height") + margin;
        int bottom = screenHeight - overlayH - dp(48);
        if (bottom < top) bottom = top;
        if (p.x < margin) p.x = margin;
        if (p.x > screenWidth - overlayW - margin) {
            p.x = Math.max(margin, screenWidth - overlayW - margin);
        }
        if (p.y < top) p.y = top;
        if (p.y > bottom) p.y = bottom;
    }

    private void hideOverlay() {
        if (overlayView != null) {
            try { overlayView.setVisibility(View.GONE); } catch (Exception ignored) {}
        }
    }

    private void showOverlayAgain() {
        if (overlayView != null) {
            try { overlayView.setVisibility(View.VISIBLE); } catch (Exception ignored) {}
        }
    }

    private void removeOverlay() {
        if (overlayView != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
            overlayView = null;
        }
        overlayParams = null;
        overlayStart = null;
        overlayStop = null;
        overlayQuit = null;
    }

    /**
     * Icon-only overlay: there is no text to update. Progress and status
     * messages are shown in the notification instead.
     */
    private void setOverlayText(final String s) {
    }

    /**
     * Let injected accessibility scroll gestures pass through the
     * overlay even if the user dragged it over the scroll node. The
     * window is not touchable for about the gesture duration only;
     * stop/quit are tappable again right after.
     */
    private void setOverlayTouchable(boolean touchable) {
        mainThread(() -> {
            if (overlayView == null || overlayParams == null) return;
            try {
                if (touchable) {
                    overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                } else {
                    overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                }
                windowManager.updateViewLayout(overlayView, overlayParams);
            } catch (Exception ignored) {}
        });
    }

    private void mainThreadDelayed(Runnable r, long delayMs) {
        new Handler(getMainLooper()).postDelayed(r, delayMs);
    }

    private void startCaptureLoop() {
        if (loopRunning) return;
        if (CaptureSession.accessibilityService == null) {
            // Do NOT refuse to start with a false "not enabled" prompt when
            // the user has already enabled it in settings but the service
            // has not connected in this process. Start in manual mode:
            // the loop will capture changed frames while the user scrolls
            // manually, instead of ending immediately.
            Toast.makeText(this, "无障碍服务未连接，进入手动模式：你手动滚，App 会截变化的画面；想自动滚请开关一次无障碍", Toast.LENGTH_LONG).show();
            updateNotification("手动模式：请手动滚动，点停止结束");
        }
        loopRunning = true;
        stopRequested = false;
        quitRequested = false;
        // Start conservative: the old 0.40 first step plus the app's
        // fling could move a 1405px content frame by 1333px, leaving
        // only a 72px overlap and a broken first join (capture
        // 2026-11-04). 0.22 keeps the first overlap near the stable
        // ~700px band; the normal adaptive feedback can grow it.
        CaptureSession.scrollStepFactor = 0.22;
        CaptureSession.capturing = true;
        // v1.39.45: lastError is session-scoped and SUMMARY
        // prints it verbatim; capture 55's failure text bled
        // into captures 56/57's summaries although they saved
        // fine. Every capture starts with a clean slate.
        CaptureSession.lastError = null;
        reconsentTried = false;
        pendingFloatUseTop = -1;
        CaptureSession.volumeStopRequested = false;
        volumeChangeStop = false;
        loopStopReason = "";
        panelStop = false;
        if (lastScreenFrame != null) {
            lastScreenFrame.recycle();
            lastScreenFrame = null;
        }
        // v1.39.12: the overlay hides for the WHOLE capture (it
        // used to blink off and on around every single frame
        // grab). Stop is the volume-down key - accessibility key
        // filter, with the volume-change watcher below as a
        // fallback - or the notification's Stop action. The
        // overlay comes back when the capture ends.
        mainThread(() -> hideOverlay());
        startStopWatcher();
        if (CaptureSession.accessibilityService != null) {
            CaptureSession.accessibilityService.resetScrollState();
        }
        if (overlayStart != null) overlayStart.setVisibility(View.GONE);
        if (overlayStop != null) overlayStop.setVisibility(View.VISIBLE);
        captureThread = new HandlerThread("scrollshot-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        captureHandler.post(this::captureLoop);
    }

    private boolean isBlankFrame(Bitmap bmp) {
        if (bmp == null) return true;
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            int first = bmp.getPixel(0, 0);
            int same = 0, total = 0;
            int min = 255, max = 0;
            long sum = 0;
            for (int y = 0; y < h; y += Math.max(1, h / 20)) {
                for (int x = 0; x < w; x += Math.max(1, w / 20)) {
                    int px = bmp.getPixel(x, y);
                    total++;
                    if (px == first) same++;
                    int lum = (Color.red(px) * 299 + Color.green(px) * 587
                            + Color.blue(px) * 114) / 1000;
                    if (lum < min) min = lum;
                    if (lum > max) max = lum;
                    sum += lum;
                }
            }
            if (total == 0) return true;
            if ((double) same / total > 0.98) return true;
            // v1.39.30: a capture-protected surface, or a screen
            // still covered at grab time, is NOT pixel-uniform -
            // it is near-black with slight per-pixel variation
            // (capture 33's accepted first frame sampled mean 7,
            // max 13), so the equality test above waved it
            // through and the capture "succeeded" black. A frame
            // whose samples are all very dark AND span almost no
            // range carries no stitchable content: call it blank,
            // so the retry budgets and the honest anti-capture
            // error path apply. Dark-mode pages stay safe: their
            // text and bubbles push the sampled maximum far
            // above the range gate.
            double mean = (double) sum / total;
            return (max - min) <= 16 && mean <= 24.0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Bitmap captureFrameWithRetry() {
        // The overlay stays hidden for the whole capture
        // (v1.39.12), so grabs no longer hide/show it per frame.
        int nullImages = 0, blankFrames = 0;
        for (int i = 0; i < 5; i++) {
            Bitmap frame = captureFrame();
            if (frame == null) {
                nullImages++;
                sleep(300);
                continue;
            }
            frame = cropBars(frame);
            if (isBlankFrame(frame)) {
                blankFrames++;
                // Blank (all black/white) usually means the projection surface
                // has not updated yet, or the page blocks capture. Retry a few
                // times instead of saving a blank image.
                frame.recycle();
                sleep(400);
                continue;
            }
            if (statusStrip == null && pendingTopStrip != null) {
                statusStrip = pendingTopStrip;
                pendingTopStrip = null;
            }
            lastGrabFailure = "";
            retainScreenFrame(frame);
            return frame;
        }
        lastGrabFailure = "no-image x" + nullImages + ", blank x" + blankFrames;
        return null;
    }

    // The FIRST frame of a capture races the app switch and
    // the shade-dismiss transition: the target app may still
    // be painting its launch screen (uniform white reads as
    // "blank") for longer than the normal grab budget
    // (~2-3 s). Capture 27 died exactly there - zero frames,
    // and the log could not even say why. Give the first
    // frame up to ~7 s of retries; later frames keep the
    // normal budget (the page is live by then).
    private Bitmap captureFirstFrame() {
        // v1.39.45: deterministic ladder. The v1.39.44 form
        // shared one 14 s wall-clock budget across full
        // 5-attempt rounds; on the wedged pipeline each
        // acquireLatestImage blocks ~1 s on this ROM, so a
        // round costs 6-8 s and the upper rungs were starved
        // out exactly when needed (capture 55 reached only
        // rung 1; capture 59 only rung 2). Rounds are now
        // SHORT (3 attempts) and rung-driven: every rung is
        // guaranteed to execute, with a 30 s hard cap as a
        // backstop only. Two instruments ride along:
        //  - an image-available counter: at exit it separates
        //    'the display produced nothing' (signaled stays 0)
        //    from 'produced but unconsumable';
        //  - a damage burst before round 0: the mirrored
        //    display only emits frames when the screen
        //    changes, and by the time the shade has settled a
        //    static page may emit none - the overlay is
        //    flashed once (then the queue is drained, so the
        //    burst's own frames are never stitched).
        // Honest limit (device record): rungs 1-3 were all
        // executed in capture 60 and none revived that
        // pipeline; the ladder's job now is to guarantee the
        // attempts and to LOG precisely where the flow is
        // dead, not to promise a cure.
        // v1.39.47 (Hayes approved 2026-11-06 22:06): FAST-PATH.
        // Across every documented starvation (captures 45, 55,
        // 59, 60, 66) no revive rung EVER produced a frame, and
        // every healthy start succeeded in round 0 - the upper
        // rungs only added ~8 s of dead waiting before the
        // re-consent offer. The ladder now stops after round 1
        // (burst + round 0 + dwell kick + round 1 is a conclusive
        // death certificate); reviveDisplay's rungs 2/3 remain
        // in the code but are no longer climbed here.
        final int[] signaled = new int[1];
        try {
            if (imageReader != null) {
                imageReader.setOnImageAvailableListener(
                        r -> signaled[0]++, null);
            }
        } catch (Exception ignored) { }
        if (overlayView != null) {
            mainThread(() -> {
                if (overlayView != null) overlayView.setVisibility(View.VISIBLE);
            });
            sleep(200);
            mainThread(() -> hideOverlay());
            sleep(120);
            long drainUntil = System.currentTimeMillis() + 400;
            while (System.currentTimeMillis() < drainUntil) {
                Image img = null;
                try {
                    img = imageReader != null ? imageReader.acquireLatestImage() : null;
                } catch (Exception ignored) { }
                if (img != null) {
                    img.close();
                } else {
                    sleep(50);
                }
            }
            if (capLog != null) capLog.append("first frame: damage burst done, signaled=")
                    .append(signaled[0]).append("\n");
        } else {
            if (capLog != null) capLog.append("first frame: damage burst skipped (no overlay)\n");
        }
        long hardDeadline = System.currentTimeMillis() + 30000;
        Bitmap f = null;
        int okRung = -1;
        for (int rung = 0; rung <= 1; rung++) {
            if (rung > 0) {
                reviveDisplay(rung);
                sleep(400);
            }
            f = captureFrameQuick();
            if (capLog != null) capLog.append("first frame round ").append(rung)
                    .append(": ").append(f != null ? "ok" : lastGrabFailure)
                    .append(" signaled=").append(signaled[0]).append("\n");
            if (f != null) {
                okRung = rung;
                break;
            }
            if (System.currentTimeMillis() > hardDeadline) {
                if (capLog != null) capLog.append("first frame: hard cap reached\n");
                break;
            }
        }
        try {
            if (imageReader != null) {
                imageReader.setOnImageAvailableListener(null, null);
            }
        } catch (Exception ignored) { }
        if (f != null && okRung > 0 && capLog != null) {
            capLog.append("first frame recovered at revive rung ").append(okRung).append("\n");
        }
        return f;
    }

    // Short grab for the first-frame ladder rounds (3 attempts
    // instead of captureFrameWithRetry's 5): on a wedged
    // pipeline every acquire can block ~1 s, and the ladder's
    // progression matters more than one round's persistence.
    private Bitmap captureFrameQuick() {
        int nullImages = 0, blankFrames = 0;
        for (int i = 0; i < 3; i++) {
            Bitmap frame = captureFrame();
            if (frame == null) {
                nullImages++;
                sleep(250);
                continue;
            }
            frame = cropBars(frame);
            if (isBlankFrame(frame)) {
                blankFrames++;
                frame.recycle();
                sleep(300);
                continue;
            }
            if (statusStrip == null && pendingTopStrip != null) {
                statusStrip = pendingTopStrip;
                pendingTopStrip = null;
            }
            lastGrabFailure = "";
            retainScreenFrame(frame);
            return frame;
        }
        lastGrabFailure = "no-image x" + nullImages + ", blank x" + blankFrames;
        return null;
    }

    // Revival ladder for a dry mirror pipeline (v1.39.44).
    // After a capture finishes, the session's VirtualDisplay
    // can stop delivering frames to the ImageReader even though
    // nothing was released; the next capture then starves at
    // frame 1 (captures 45 and detection_461). Android 14+
    // forbids a second createVirtualDisplay on the same
    // MediaProjection (v1.39.40's SecurityException), so the
    // ladder restarts production on the EXISTING display:
    //   rung 1: dwell kick - resize to height+1 and HOLD it
    //           ~700 ms, draining odd-size frames, so the
    //           compositor really renders at the new geometry;
    //           then resize back (the caller's retry grabs a
    //           normal-size frame of the re-rendered screen).
    //   rung 2: detach and re-attach the display's output
    //           surface, restarting the producer stream.
    //   rung 3: close and recreate the ImageReader itself and
    //           point the display at the fresh surface - a
    //           wedged buffer queue is replaced wholesale.
    // Every rung is logged, so a run that still fails shows
    // exactly how far the ladder got.
    private void reviveDisplay(int rung) {
        if (virtualDisplay == null) return;
        try {
            if (rung == 1) {
                virtualDisplay.resize(screenWidth, screenHeight + 1, screenDensity);
                loopKickCount++;
                if (capLog != null) capLog.append("revive rung 1: dwell kick (hold +1, draining)\n");
                long until = System.currentTimeMillis() + 700;
                while (System.currentTimeMillis() < until) {
                    Image img = (imageReader != null) ? imageReader.acquireLatestImage() : null;
                    if (img != null) {
                        img.close();
                    } else {
                        sleep(60);
                    }
                }
                virtualDisplay.resize(screenWidth, screenHeight, screenDensity);
            } else if (rung == 2) {
                if (capLog != null) capLog.append("revive rung 2: surface reattached\n");
                virtualDisplay.setSurface(null);
                sleep(150);
                if (imageReader != null) virtualDisplay.setSurface(imageReader.getSurface());
            } else {
                if (capLog != null) capLog.append("revive rung 3: reader recreated\n");
                ImageReader old = imageReader;
                ImageReader fresh = ImageReader.newInstance(
                        screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
                virtualDisplay.setSurface(fresh.getSurface());
                imageReader = fresh;
                if (old != null) old.close();
            }
        } catch (Exception e) {
            if (capLog != null) capLog.append("revive rung ").append(rung)
                    .append(" failed: ").append(String.valueOf(e)).append("\n");
        }
    }

    /**
     * Decide what happens when an auto-scroll attempt did not move the
     * page. If every scrollable container is exhausted, the page is
     * simply finished: end the capture NORMALLY (save + preview) right
     * away - the old code dropped into a 60 s "自动滚动失败" manual
     * wait at the end of every long page. Only when scrolling might
     * still be possible (no accessibility service, or containers that
     * are not all exhausted) do we wait for a manual scroll.
     * Returns true if the capture loop should continue.
     */
    private boolean scrollEndOrManualWait() {
        ScrollAccessibilityService svc = CaptureSession.accessibilityService;
        if (svc == null) {
            // The accessibility service is gone (never connected,
            // or unbound mid-capture). Falling back to "wait for a
            // manual scroll" here was the capture-6 failure: the
            // manual-wait change test is passed by any playing
            // video, so the loop kept capturing a page nothing was
            // scrolling and saved garbage. Abort instead.
            abortCapture = true;
            CaptureSession.lastError = "无障碍服务未连接，自动滚动不可用：请到无障碍设置把 ScrollShot 关掉再打开一次";
            return false;
        }
        if (svc.isScrollExhausted()) {
            // Safety net: if the last scroll barely moved the page
            // (realized step well below the gesture distance), the
            // node was very likely marked exhausted by a transient
            // gesture cancellation (or the swipe landed on a small
            // nested container) rather than the page actually ending.
            // Reset the scroll state and give it one more chance
            // instead of stopping short of the bottom.
            if (lastIntendedStep >= 200
                    && lastRealizedStep < lastIntendedStep * 0.4) {
                svc.resetScrollState();
                loopUnchangedStreak = 0;
                setOverlayText("滚动未到位，重试一次…");
                updateNotification("滚动未到位，重试一次…");
                return true;
            }
            if (loopJoins == 0) {
                // No frame was ever stitched: the page never
                // scrolled at all - do not dress that up as
                // reaching the bottom.
                setOverlayText("未能滚动页面，已停止");
                updateNotification("未能滚动页面，已停止");
                return false;
            }
            setOverlayText("已滚到底，正在保存…");
            updateNotification("已滚到底，正在保存长图…");
            return false;
        }
        return waitForManualChange();
    }

    /** Wait for the user to scroll manually: poll for a changed frame. */
    private boolean waitForManualChange() {
        setOverlayText("自动滚动已停：可手动滚动继续，或点停止结束");
        updateNotification("自动滚动已停：可手动滚动继续，或点停止结束");
        long deadline = System.currentTimeMillis() + 60000;
        Bitmap base = null;
        try {
            while (!stopRequested && System.currentTimeMillis() < deadline) {
                sleep(800);
                Bitmap frame = capturePollFrame();
                if (frame == null) continue;
                if (base == null) {
                    base = frame;
                    continue;
                }
                double diff = Stitcher.frameDifference(base, frame);
                double median = Stitcher.rowMedianDifference(base, frame);
                base.recycle();
                base = frame;
                // Movement, not mere change: a list re-sorting
                // itself raises the mean difference but leaves
                // the median row identical, and must not count
                // as the user scrolling - a live list would keep
                // this wait "succeeding" forever while nothing
                // actually scrolls.
                if (diff >= 2.0 && median >= 1.0) return true;
            }
            return false;
        } finally {
            if (base != null) base.recycle();
        }
    }

    /**
     * One capture WITHOUT hiding the overlay. Used only for polling
     * (stability / change detection), where consecutive poll frames
     * all contain the same overlay at the same place, so it cancels
     * out of the comparison. The frames that actually get stitched
     * still come from the hiding path (captureFrameWithRetry), so the
     * overlay never enters the long image - but the overlay no longer
     * blinks on and off several times per captured frame, which made
     * the stop/quit buttons nearly impossible to tap.
     */
    private Bitmap capturePollFrame() {
        Bitmap frame = captureFrame();
        if (frame == null) return null;
        frame = cropBars(frame);
        if (isBlankFrame(frame)) {
            frame.recycle();
            return null;
        }
        retainScreenFrame(frame);
        return frame;
    }

    /** Keep a private copy of the latest real screen frame. */
    private void retainScreenFrame(Bitmap frame) {
        if (frame == null) return;
        try {
            Bitmap copy = frame.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) return;
            if (lastScreenFrame != null) lastScreenFrame.recycle();
            lastScreenFrame = copy;
        } catch (Exception ignored) {
        }
    }

    /** A fresh copy of the last known screen frame, or null. */
    private Bitmap lastScreenFrameCopy() {
        if (lastScreenFrame == null) return null;
        try {
            return lastScreenFrame.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Capture a frame only after the screen has settled. The old loop
     * waited a fixed 250ms after the gesture, so frames were often
     * taken mid-fling / mid-animation with a scroll offset that did
     * not match anything, corrupting the overlap match. Now poll
     * until two consecutive frames are (nearly) identical, or a
     * timeout passes. If the reader returns no new frame the screen
     * is not changing at all, which also means "settled".
     */
    private Bitmap captureStableFrame() {
        // v1.16: stricter settling. Web pages keep shifting for a while
        // after a scroll (lazy images loading, layout settling), and a
        // frame taken during that shift cannot align with its
        // neighbours by ANY single offset - several garbled seams in
        // the browser tests came from this. Require TWO consecutive
        // polls that are near-identical (< 0.6) before accepting.
        // v1.18: the polls no longer hide/show the overlay (it blinked
        // up to 8 times per frame, making stop/quit hard to tap). The
        // polls only decide THAT the screen is settled; the frame that
        // gets stitched is then taken once through the hiding path.
        Bitmap prev = capturePollFrame();
        if (prev == null) {
            // No poll frame at all: fall back to one clean capture.
            Bitmap f = captureFrameWithRetry();
            if (f != null) return f;
            // v1.39.29: the reader queue is dry - since the last
            // produced frame NOTHING has repainted (capture 33:
            // after one black frame the surface produced nothing
            // more, no new frame ever arrived and the old code
            // died here with "no-image x5"). A dry queue means the
            // screen still shows exactly the last produced frame:
            // hand that to the loop, whose movement checks judge
            // "no movement" and escalate / end honestly instead
            // of the capture dying as a grab failure.
            Bitmap last = lastScreenFrameCopy();
            if (last != null && capLog != null) {
                capLog.append("grab starved: using last known screen frame\n");
            }
            return last;
        }
        int stablePolls = 0;
        boolean settled = false;
        for (int i = 0; i < 7 && !stopRequested; i++) {
            sleep(250);
            Bitmap cur = capturePollFrame();
            if (cur == null) { settled = true; break; }
            double diff = Stitcher.frameDifference(prev, cur);
            prev.recycle();
            prev = cur;
            if (diff < 0.6) {
                stablePolls++;
                if (stablePolls >= 2) { settled = true; break; }
            } else {
                stablePolls = 0;
            }
        }
        if (stopRequested) return prev;
        Bitmap clean = captureFrameWithRetry();
        if (clean != null) {
            prev.recycle();
            return clean;
        }
        // Clean capture failed repeatedly: use the settled poll frame
        // rather than aborting the whole capture (the overlay in it is
        // static across frames, so stitching treats it like a top bar).
        return prev;
    }

    /** Independent, mutable copy of a horizontal strip of a bitmap. */
    private static Bitmap copyStrip(Bitmap src, int y, int height) {
        if (src == null || height <= 0) return null;
        Bitmap part = Bitmap.createBitmap(src, 0, y, src.getWidth(), height);
        Bitmap copy = part.copy(Bitmap.Config.ARGB_8888, true);
        if (part != copy) part.recycle();
        return copy;
    }

    /** Copy of the middle (content) region of a frame, sticky bars removed. */
    private static Bitmap cropContent(Bitmap src, int top, int bottom) {
        int h = src.getHeight() - top - bottom;
        return copyStrip(src, top, Math.max(1, h));
    }

    // One pair's floating detection, merged across detectors:
    // {key, ext, type, span} or null. Key = band cluster top if a
    // wide element was found, else the cell-cluster top; ext = the
    // highest floating top from either detector; type 0 = band,
    // 1 = cell; span is set by the caller (0 adjacent, 1 skip).
    private static int[] detectPair(Bitmap a, Bitmap b) {
        int[] info = Stitcher.floatingBottomInfo(a, b);
        int cell = Stitcher.floatingCellClusterTop(a, b);
        int key = -1, ext = -1, type = 0;
        if (info != null) { key = info[0]; ext = info[1]; }
        if (cell > 0) {
            ext = ext < 0 ? cell : Math.min(ext, cell);
            if (key < 0) { key = cell; type = 1; }
        }
        if (key < 0) return null;
        return new int[]{key, ext, type, 0};
    }

    // Confirmation across two detections: same detector type only
    // (keys from different detectors are not comparable), band keys
    // within 24px on any two pairs, cell keys within 40px AND at
    // different spans (scroll-step scales).
    private static boolean confirmPair(int[] x, int[] y) {
        if (x[2] != y[2]) return false;
        if (x[2] == 1) {
            return x[3] != y[3] && Math.abs(x[0] - y[0]) <= 40;
        }
        return Math.abs(x[0] - y[0]) <= 24;
    }

    private StringBuilder capLog;

    // ---- Diagnostics: dump the frames the detector actually
    // sees plus a per-attempt detection log, so a failing page can
    // be replayed offline instead of guessed at. Files land in
    // Download/ScrollShot/debug/ and are overwritten by each
    // capture. v1.22-v1.31 always wrote them; v1.32 makes them
    // opt-in via the main screen's diagnostics switch (default
    // off): a normal capture now saves only the finished long
    // image. The dumps are write-only forensics - stitching never
    // reads them back.
    static final String PREFS_NAME = "scrollshot_prefs";
    static final String PREF_DIAGNOSTICS = "diagnostics";

    private boolean diagEnabled;

    static boolean diagnosticsEnabled(android.content.Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS_NAME,
                    android.content.Context.MODE_PRIVATE)
                    .getBoolean(PREF_DIAGNOSTICS, false);
        } catch (Exception e) {
            return false;
        }
    }

    static void setDiagnosticsEnabled(android.content.Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences(PREFS_NAME,
                    android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_DIAGNOSTICS, on).apply();
        } catch (Exception ignored) {
        }
    }

    private static java.io.File debugDir() {
        return new java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS),
                "ScrollShot/debug");
    }

    // Delete every diagnostic file (frame dumps + detection log).
    // Runs when the switch is toggled off and at the start of a
    // diagnostics-off capture, so dumps left by older versions do
    // not linger either.
    static void clearDebugOutput() {
        try {
            java.io.File[] files = debugDir().listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    String n = f.getName();
                    if (n.startsWith("frame") || n.equals("detection.txt")
                            || n.equals("detection_history.txt")) f.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    static boolean scrollShotFolderExists() {
        try {
            return new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    "ScrollShot").exists();
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Folder delete (all-files access) ----
    // Deleting Download/ScrollShot wholesale runs into scoped
    // storage three ways: File.delete() refuses files a PREVIOUS
    // install created, MediaStore cannot cover unindexed files
    // (.nomedia) or the directories themselves, and the SAF
    // folder grant proved fiddly on the user's phone (a failed
    // delete dropped the grant, so every attempt re-ran the
    // picker). With all-files access (MANAGE_EXTERNAL_STORAGE,
    // granted once in system settings) plain file deletion works
    // on everything in shared storage regardless of creator, so
    // this is a plain recursive delete again. The delete code
    // only ever targets this one folder.
    // prepareDebugOutput() recreates the folder on the next
    // diagnostics capture, so deleting it is always safe.
    static boolean hasAllFilesAccess() {
        return android.os.Build.VERSION.SDK_INT < 30
                || android.os.Environment.isExternalStorageManager();
    }

    // Returns how many files were actually deleted, -1 when the
    // folder does not exist, or -2 when the hard path guard
    // refused to run.
    static int deleteScrollShotDownloads() {
        java.io.File root = new java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS),
                "ScrollShot");
        // Hard guard: only ever delete this exact folder. The
        // target's canonical path must BE Download/ScrollShot
        // under the public downloads directory - anything else
        // (a symlink standing in for the folder, a future
        // refactor handing in the wrong file) is refused
        // outright, before anything is touched.
        try {
            String expected = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    "ScrollShot").getCanonicalPath();
            if (!expected.equals(root.getCanonicalPath())) return -2;
        } catch (Exception e) {
            return -2;
        }
        if (!root.exists()) return -1;
        int[] count = {0};
        deleteRecursively(root, count);
        return count[0];
    }

    private static void deleteRecursively(java.io.File f, int[] count) {
        try {
            java.io.File[] kids = f.listFiles();
            if (kids != null) {
                for (java.io.File k : kids) deleteRecursively(k, count);
            }
            if (f.isFile()) {
                if (f.delete()) count[0]++;
            } else {
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    // Per-capture setup: diagnostics on -> fresh folder plus a
    // .nomedia marker (diagnostic frames stay out of the gallery
    // even while the switch is on); off -> sweep stale dumps.
    private void prepareDebugOutput() {
        if (!diagEnabled) {
            clearDebugOutput();
            return;
        }
        try {
            java.io.File dir = debugDir();
            if (!dir.exists()) dir.mkdirs();
            java.io.File nomedia = new java.io.File(dir, ".nomedia");
            if (!nomedia.exists()) nomedia.createNewFile();
        } catch (Exception ignored) {
        }
        clearDebugFrames();
    }

    private void dumpDebugFrame(Bitmap raw, int index1Based) {
        if (!diagEnabled) return;
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    "ScrollShot/debug");
            if (!dir.exists()) dir.mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(
                    new java.io.File(dir, "frame" + index1Based + ".png"));
            raw.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch (Exception ignored) {
        }
    }

    // v1.26: every raw frame as a half-scale JPEG, so a whole
    // capture's joins can be replayed offline (the first-6 PNGs
    // serve the detector; these serve the stitcher).
    private void dumpDebugFrameJpeg(Bitmap raw, int index1Based) {
        if (!diagEnabled) return;
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    "ScrollShot/debug");
            if (!dir.exists()) dir.mkdirs();
            Bitmap half = Bitmap.createScaledBitmap(raw,
                    Math.max(1, raw.getWidth() / 2), Math.max(1, raw.getHeight() / 2), true);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(
                    new java.io.File(dir, String.format("frame%02d.jpg", index1Based)));
            half.compress(Bitmap.CompressFormat.JPEG, 72, fos);
            fos.close();
            if (half != raw) half.recycle();
        } catch (Exception ignored) {
        }
    }

    private void clearDebugFrames() {
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    "ScrollShot/debug");
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    if (f.getName().startsWith("frame")) f.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String lastDumpedLogText = "";

    private void dumpDebugLog(String text) {
        if (!diagEnabled) return;
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    "ScrollShot/debug");
            if (!dir.exists()) dir.mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(
                    new java.io.File(dir, "detection.txt"));
            fos.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.close();
            // v1.39.50: also APPEND to a session history file.
            // detection.txt only ever holds the LATEST capture,
            // so a starved attempt's log - the evidence needed
            // most - was silently overwritten by the very
            // re-consented capture it triggered (captures
            // 71/72). The history keeps every capture's log;
            // within one capture the text only grows, so append
            // just the new tail.
            synchronized (CaptureService.class) {
                String delta;
                if (lastDumpedLogText != null && text.startsWith(lastDumpedLogText)) {
                    delta = text.substring(lastDumpedLogText.length());
                } else {
                    delta = "\n===== capture "
                            + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                    java.util.Locale.US).format(new java.util.Date())
                            + " =====\n" + text;
                }
                if (!delta.isEmpty()) {
                    java.io.FileOutputStream hos = new java.io.FileOutputStream(
                            new java.io.File(dir, "detection_history.txt"), true);
                    hos.write(delta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    hos.close();
                }
                lastDumpedLogText = text;
            }
        } catch (Exception ignored) {
        }
    }

    private void captureLoop() {
        loopStitched = null;           // content-only loopStitched image (bars stripped once detected)
        loopPreviousFrame = null;      // previous CONTENT frame (for diff / stitch)
        loopStepFloor = 0.20;
        // Keep the adaptive step deliberately conservative. The
        // old 0.40 cap let the feedback grow to a ~50% content step,
        // which left too little overlap on fling-heavy lists and
        // made the same page alternate between clean and broken
        // captures. 0.25 targets a ~55-65% overlap band.
        loopStepCap = 0.25;
        abortCapture = false;
        gapStop = false;
        loopZeroStreak = 0;
        loopUnchangedStreak = 0;
        lastRealizedStep = 0;
        lastIntendedStep = 0;
        loopJoins = 0;
        loopAnyScrollOk = false;
        loopChromeBottom = 0;
        loopFixedRects.clear();
        loopPendings.clear();
        loopRealizedSteps.clear();
        loopKickCount = 0;
        loopJoinsMatched = 0;
        loopJoinsUnmatched = 0;
        if (statusStrip != null) { statusStrip.recycle(); statusStrip = null; }
        if (pendingTopStrip != null) { pendingTopStrip.recycle(); pendingTopStrip = null; }
        loopFrames = 0;
        // Deferred bar/floating detection state (v1.20): raw loopFrames
        // held while detection tries successive frame pairs, the
        // floating-top candidates seen so far (a cut needs two pairs
        // agreeing within 24px), and the first attempt's sticky bars
        // (used if no floating cluster is ever confirmed).
        java.util.List<Bitmap> deferredRaws = new java.util.ArrayList<>();
        java.util.List<int[]> floatingCandidates = new java.util.ArrayList<>();
        java.util.List<int[]> maskCandidates = new java.util.ArrayList<>();
        int savedStickyTop = 0;
        java.util.ArrayList<Integer> sTopSamples = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> sBotSamples = new java.util.ArrayList<>();
        int savedStickyBottom = 0;
        int maxTopSeen = 0;
        StringBuilder debugLog = new StringBuilder();
        capLog = debugLog;
        long loopStartMs = System.currentTimeMillis();
        debugLog.append("START svcConnected=")
                .append(CaptureSession.accessibilityService != null)
                .append(" factor=").append(CaptureSession.scrollStepFactor)
                .append(" vol=").append(captureStartVolume)
                .append(" screen=").append(screenWidth).append("x").append(screenHeight)
                .append(" density=").append(screenDensity)
                .append("\n");
        diagEnabled = diagnosticsEnabled(this);
        prepareDebugOutput();
        // Every capture starts from a clean scroll state. The
        // end-of-capture reset only runs on the non-quit path,
        // so without this a capture that ended via Quit could
        // leave a spent node/blind strategy behind and silently
        // disable scrolling for the whole next capture.
        if (CaptureSession.accessibilityService != null) {
            CaptureSession.accessibilityService.resetScrollState();
        }
        Bitmap firstTopBar = null;     // static top bar of the first raw frame
        Bitmap lastBottomStrip = null; // latest raw frame's cut region (edge bar and/or
                                       // floating cluster), attached once at the very end
        int stickyTop = 0;
        int stickyBottom = 0;          // bar contiguous with the bottom edge (re-attached at the end)
        int bottomCut = 0;             // total bottom crop for content: max(edge bar, floating cluster)
        // (loopStepFloor field: adaptive scroll-step floor, raised
        // when a floating cut is active)
        boolean stickyDetected = false;
        int direction = 0;
        try {
            kickDisplay();
            // Let the overlay-hide posted at capture start land
            // before the first frame is grabbed.
            sleep(300);
            if (startedFromNotification) {
                // Give the shade time to collapse and the target
                // app time to come back to the front: poll until
                // an application window is focused (max ~2.5 s).
                ScrollAccessibilityService svc0 = CaptureSession.accessibilityService;
                long shadeDeadline = System.currentTimeMillis() + 2500;
                while (svc0 != null && System.currentTimeMillis() < shadeDeadline) {
                    if (svc0.isAppWindowFocused()) break;
                    sleep(200);
                }
                if (capLog != null) capLog.append("start: from notification, shade wait done\n");
            }
            while (!stopRequested && loopFrames < MAX_FRAMES) {
                long grabT0 = System.currentTimeMillis();
                Bitmap raw = (loopFrames == 0) ? captureFirstFrame() : captureStableFrame();
                if (loopFrames == 0 && raw != null && capLog != null) {
                    capLog.append("first frame ok in ")
                            .append(System.currentTimeMillis() - grabT0)
                            .append("ms (kicks=").append(loopKickCount).append(")\n");
                }
                if (raw != null && !stopRequested && captureStartVolume >= 0) {
                    // Synchronous volume check: the watcher
                    // polls every 150 ms, and capture 18 lost the
                    // race - the volume-down press landed DURING
                    // the last frame's grab, the frame returned
                    // with the volume HUD in it, and the watcher
                    // only noticed ~200 ms later, after the frame
                    // had already been stitched. Reading the
                    // volume HERE, at the decision point, has no
                    // polling latency: if it already dropped, the
                    // press already happened and this frame is
                    // suspect - register the stop so the retake
                    // below fires.
                    int vNow = currentMusicVolume();
                    if (vNow >= 0 && vNow < captureStartVolume) {
                        volumeChangeStop = true;
                        stopRequested = true;
                        loopStopReason = "volume-change";
                    }
                }
                if (raw != null) {
                    // Transient-panel cover check (v1.39.17): a
                    // solid block newly covering the right edge
                    // means a system panel (volume HUD) is in
                    // this frame - the user pressed a key to
                    // stop. This signal works even when the
                    // volume itself cannot drop (already at
                    // minimum - capture 19, where two presses
                    // were captured into frames 13/14 and the
                    // capture ran on).
                    Bitmap curThumb = null;
                    try {
                        curThumb = Bitmap.createScaledBitmap(raw, 320,
                                Math.max(1, (int) Math.round(320.0 * raw.getHeight() / raw.getWidth())), true);
                    } catch (Exception ignored) { }
                    if (curThumb != null) {
                        // v1.39.35: window-layer pill check -
                        // backdrop-independent (see
                        // ScrollAccessibilityService.pollWindows).
                        // A pill window present at this frame's
                        // decision point is the stop key, on any
                        // surface; the frame flows into the
                        // final-frame retake below. The changing
                        // window signature is logged so a miss
                        // shows its reason in the log.
                        ScrollAccessibilityService svcW = CaptureSession.accessibilityService;
                        if (svcW != null) {
                            boolean pillWin = svcW.pollWindows();
                            String wsig = ScrollAccessibilityService.windowSignature;
                            if (!wsig.equals(lastWinSig)) {
                                lastWinSig = wsig;
                                if (capLog != null) capLog.append("windows: ")
                                        .append(wsig.isEmpty() ? "(none)" : wsig).append("\n");
                            }
                            if (pillWin && !stopRequested) {
                                panelStop = true;
                                stopRequested = true;
                                loopStopReason = "volume-window";
                                if (capLog != null) capLog.append("stop suspect: volume window present (stop key seen)\n");
                            }
                        }
                        if (!stopRequested && panelPrevThumb != null
                                && Stitcher.panelCovered(panelPrevThumb, curThumb)) {
                            // v1.39.21: a cover event is only a
                            // SUSPECT - on image-rich pages a
                            // light photo region trips the same
                            // test (capture 22: a news feed
                            // stopped itself mid-page). Confirm
                            // before stopping: a system panel is
                            // transient, page content is stable.
                            if (confirmPanelSuspect(panelPrevThumb, curThumb)) {
                                panelStop = true;
                                stopRequested = true;
                                if (capLog != null) capLog.append("stop suspect: panel cover confirmed (transient system panel)\n");
                            } else {
                                if (panelSuspectRetake) {
                                    // v1.39.45: the suspect was
                                    // neither confirmed (no pill
                                    // window) nor cleared as
                                    // content - swap the frame
                                    // for a fresh grab, verified
                                    // the usual way, and roll on.
                                    panelSuspectRetake = false;
                                    if (capLog != null) capLog.append("panel suspect unverifiable/transient: retaking frame once, continuing\n");
                                    sleep(900);
                                    Bitmap cleanGrab = captureFrameWithRetry();
                                    if (cleanGrab != null) {
                                        Bitmap cleanThumb = thumb320(cleanGrab);
                                        boolean cWin = pillWindowPresent();
                                        boolean cStrong = cleanThumb != null && Stitcher.panelPresentStrong(cleanThumb);
                                        boolean cInset = cleanThumb != null && Stitcher.panelInsetSlab(cleanThumb);
                                        if (cWin || cStrong || cInset) {
                                            if (capLog != null) capLog.append("panel suspect retake still covered, continuing with it: window=")
                                                    .append(cWin).append(" strong=").append(cStrong)
                                                    .append(" inset=").append(cInset).append("\n");
                                        }
                                        raw.recycle();
                                        raw = cleanGrab;
                                        if (cleanThumb != null) {
                                            curThumb.recycle();
                                            curThumb = cleanThumb;
                                        }
                                    }
                                }
                                if (capLog != null) capLog.append("panel suspect rejected: stable page content, continuing\n");
                            }
                        }
                        boolean strongP = Stitcher.panelPresentStrong(curThumb);
                        boolean insetP = !strongP && Stitcher.panelInsetSlab(curThumb);
                        boolean looseP = !strongP && !insetP && Stitcher.panelInsetLoose(curThumb);
                        if (!stopRequested && insetP && pillWindowPresent()) {
                            // v1.39.34 -> v1.39.45: a crisp inset
                            // slab WITH the pill window is the
                            // stop key's panel and stops the
                            // capture as before. WITHOUT the
                            // window it no longer stops anything:
                            // capture 54's dragon-photo sky
                            // chained past the crisp threshold on
                            // a browser page (the 'clean frames
                            // <= 13' record was WeChat-era), so
                            // the slab alone is demoted to the
                            // probe/retake treatment below. Real
                            // presses are unaffected: in every
                            // verified press the pill window is
                            // present in the same cycle and the
                            // window check above fires first.
                            panelStop = true;
                            stopRequested = true;
                            loopStopReason = "inset-panel(window-verified)";
                            if (capLog != null) capLog.append("stop suspect: inset panel + pill window (stop key seen)\n");
                        } else if (!stopRequested && (strongP || insetP || looseP)) {
                            // v1.39.33: the loose inset trigger
                            // (a mid-fade pill on a chat-like
                            // page - capture 36's frame 18)
                            // must first prove itself TRANSIENT
                            // at the same position: probe the
                            // unchanged screen after 900 ms and
                            // measure the suspect region. Stable
                            // (a fixed element, a photo
                            // background) -> keep the frame and
                            // skip the retake entirely; changed
                            // -> the stop key's fading panel, so
                            // stop (v1.39.34); unprovable (probe
                            // failed) -> the retake below, never
                            // a stop on unproven evidence.
                            boolean proceedRetake = true;
                            if (looseP || insetP) {
                                sleep(900);
                                Bitmap probe = captureFrameWithRetry();
                                if (probe != null) {
                                    Bitmap pThumb = thumb320(probe);
                                    float chg = pThumb != null
                                            ? Stitcher.insetRegionChange(curThumb, pThumb) : -1f;
                                    if (pThumb != null) pThumb.recycle();
                                    probe.recycle();
                                    if (chg >= 0f && chg <= 4.0f) {
                                        proceedRetake = false;
                                    } else if (chg > 4.0f) {
                                        // v1.39.45: a changing
                                        // region is NOT a stop
                                        // any more. Capture 57:
                                        // the WeChat list page
                                        // settling after the shade
                                        // produced change > 4.0
                                        // with no key pressed and
                                        // stopped at frame 1.
                                        // Without a pill window
                                        // the verdict is: retake
                                        // the frame and roll on
                                        // (proceedRetake stays
                                        // true).
                                        if (capLog != null) capLog.append("inset suspect transient but NO pill window - retaking, not stopping\n");
                                    }
                                }
                            }
                            if (stopRequested) {
                                // Unreachable since v1.39.45 (the
                                // probe no longer stops); kept as
                                // the branch's safety shape.
                            } else if (!proceedRetake) {
                                if (capLog != null) capLog.append("panel loose suspect stable, keeping frame\n");
                            } else {
                            // A FULL system panel sits in this
                            // frame mid-capture (v1.39.22): the
                            // press's other signals have not
                            // landed yet, and stitching the frame
                            // as-is would bake the panel into
                            // the result (capture 19's frames
                            // 13/14, capture 23's frame 6). The
                            // page has not scrolled, so retake
                            // the frame once after the panel's
                            // expiry and continue with the clean
                            // copy - no stop is implied.
                            if (capLog != null) capLog.append("panel in frame mid-capture: retaking once").append(strongP ? " [strong]" : insetP ? " [inset]" : " [inset loose]").append("\n");
                            sleep(1200);
                            Bitmap cleanGrab = captureFrameWithRetry();
                            if (cleanGrab != null) {
                                Bitmap cleanThumb = thumb320(cleanGrab);
                                boolean gWin = pillWindowPresent();
                                boolean gStrong = cleanThumb != null && Stitcher.panelPresentStrong(cleanThumb);
                                boolean gInset = cleanThumb != null && Stitcher.panelInsetSlab(cleanThumb);
                                if (gWin || gStrong || gInset) {
                                    if (capLog != null) capLog.append("panel frame retake still covered, continuing with it: window=")
                                            .append(gWin).append(" strong=").append(gStrong)
                                            .append(" inset=").append(gInset).append("\n");
                                }
                                raw.recycle();
                                raw = cleanGrab;
                                if (cleanThumb != null) {
                                    curThumb.recycle();
                                    curThumb = cleanThumb;
                                }
                            }
                            }
                        }
                        if (panelPrevThumb != null) panelPrevThumb.recycle();
                        panelPrevThumb = curThumb;
                    }
                }
                if (raw != null && stopRequested) {
                    // The stop arrived while this frame was being
                    // settled/grabbed - on the volume-key path the
                    // stop key itself raises a transient system
                    // HUD (volume panel) that can sit IN the frame
                    // (capture 17's last frame). The capture ends
                    // either way; wait for the transient to expire
                    // and retake the frame clean before it is
                    // stitched or held.
                    // Wait out the transient (the volume HUD
                    // lives ~2 s). When the stop came from the
                    // notification, the shade may still be open:
                    // dismiss it and wait for the target app to
                    // be focused again before retaking, or the
                    // retake grabs the shade's blurred view
                    // itself (capture 19's final join came back
                    // unmatched for exactly that reason).
                    long waitT0 = System.currentTimeMillis();
                    ScrollAccessibilityService svcR = CaptureSession.accessibilityService;
                    if (svcR != null) svcR.dismissNotificationShade();
                    while (true) {
                        long el = System.currentTimeMillis() - waitT0;
                        boolean focused = svcR == null || svcR.isAppWindowFocused();
                        if ((el >= 1600 && focused) || el >= 2500) break;
                        sleep(150);
                    }
                    Bitmap retake = captureFrameWithRetry();
                    // Verify the retake is really clean: the
                    // timed wait is not proof the panel expired
                    // (capture 20's retaken frame still held the
                    // full volume panel and it was stitched into
                    // the result). While the retake still shows
                    // a panel block, wait and grab again.
                    // v1.39.27: max 2 retries (was 3). The wait
                    // before the first retake is already
                    // >= 1.6 s and each retry adds 900 ms;
                    // genuine panels cleared by retry 1 in
                    // every capture so far (28 cleared at (1),
                    // 26/29 passed at once). The third retry
                    // only stretched the visible stop latency -
                    // and a user who presses again during it
                    // re-raises the panel and feeds it
                    // (capture 30 burned all three).
                    for (int rt = 0; retake != null && rt < 2; rt++) {
                        Bitmap rThumb = null;
                        try {
                            rThumb = Bitmap.createScaledBitmap(retake, 320,
                                    Math.max(1, (int) Math.round(320.0 * retake.getHeight() / retake.getWidth())), true);
                        } catch (Exception ignored) { }
                        // v1.39.24: the STRONG test, not the
                        // sensitive panelPresent - the latter
                        // (>= 1.5% of rows) fires on ordinary
                        // news-feed frames with large light
                        // images (capture 25 burned all three
                        // retries on clean frames). A full
                        // panel is what must stay out of the
                        // result; fading slivers are left to
                        // the timed wait above.
                        boolean covWin = pillWindowPresent();
                        boolean covStrong = rThumb != null && Stitcher.panelPresentStrong(rThumb);
                        boolean covInset = rThumb != null && Stitcher.panelInsetSlab(rThumb);
                        boolean covered = covWin || covStrong || covInset;
                        if (rThumb != null) rThumb.recycle();
                        if (!covered) break;
                        if (capLog != null) capLog.append("final frame retake still covered (").append(rt + 1)
                                .append("): window=").append(covWin)
                                .append(" strong=").append(covStrong)
                                .append(" inset=").append(covInset).append("\n");
                        sleep(900);
                        Bitmap again = captureFrameWithRetry();
                        if (again == null) break;
                        retake.recycle();
                        retake = again;
                    }
                    if (retake != null) {
                        raw.recycle();
                        raw = retake;
                        if (capLog != null) capLog.append("final frame retaken after stop (transient overlay wait)\n");
                    }
                }
                if (raw == null) {
                    if (capLog != null) capLog.append("grab failed at frame ")
                            .append(loopFrames + 1).append(": ").append(lastGrabFailure).append("\n");
                    // v1.39.46: RE-CONSENT FALLBACK (Hayes approved
                    // 2026-11-06 21:31). Frame-1 total starvation
                    // (no-image: the mirror pipeline produced
                    // nothing through the whole deterministic
                    // ladder) is the one failure class no
                    // in-session remedy has ever cured on device
                    // (kicks, dwell, surface re-attach, reader
                    // recreation - captures 55/59/60). Instead of
                    // dying with the misleading anti-capture text,
                    // offer the one cure that works - a fresh
                    // MediaProjection grant: MainActivity opens
                    // the consent dialog, the fresh grant re-arms
                    // this service, and the user re-runs the
                    // capture from the target page. Once per
                    // capture; blank frames (pipeline alive,
                    // protected content) do NOT qualify.
                    if (loopFrames == 0 && !reconsentTried && lastGrabFailure != null
                            && lastGrabFailure.startsWith("no-image")) {
                        reconsentTried = true;
                        loopStopReason = "first-frame-dead(reconsent offered)";
                        if (capLog != null) capLog.append("first frame: pipeline dead (no-image through ladder) - offering re-consent fallback\n");
                        CaptureSession.lastError = "录屏画面中断（系统镜像失效），已为你打开重新授权：点“允许”后切回目标页面再点开始即可";
                        CaptureSession.statusText = "录屏画面中断，正在重新授权…";
                        updateNotification("录屏画面中断，已打开重新授权");
                        requestReconsent();
                        break;
                    }
                    CaptureSession.lastError = "截到的画面是空白或无画面，可能是防截屏页面或录屏未生效";
                    break;
                }
                loopFrames++;
                direction = CaptureSession.lastDirection;
                if (loopFrames <= 6 && direction == 0) dumpDebugFrame(raw, loopFrames);
                if (direction == 0) dumpDebugFrameJpeg(raw, loopFrames);
                if (loopStitched == null) {
                    loopStitched = raw;
                    loopPreviousFrame = raw;
                    setOverlayText("已截 1 帧，正在尝试自动滚动…");
                } else {
                    // Order matters (fixed after self-audit of this code):
                    // 1) compare the new frame with the previous one in the
                    //    SAME form (raw vs raw before sticky detection,
                    //    content vs content after it);
                    // 2) only then detect / strip sticky bars, which
                    //    recycles the previous raw frame, so nothing may
                    //    use it afterwards in this iteration.
                    Bitmap frame = raw;
                    double diff;
                    if (!stickyDetected && direction == 0) {
                        diff = Stitcher.frameDifference(loopPreviousFrame, raw);
                        double medianDiff = Stitcher.rowMedianDifference(loopPreviousFrame, raw);
                        if (diff < 2.0 || medianDiff < 1.0) {
                            raw.recycle();
                            noteNoMovement(diff, medianDiff);
                            boolean scrolled = scrollOnce();
                            if (!scrolled) {
                                if (!scrollEndOrManualWait()) break;
                            }
                            continue;
                        }
                        loopUnchangedStreak = 0;
                        // Deferred multi-attempt bar/floating
                        // detection (v1.20). v1.16-v1.19 decided the
                        // cuts from the FIRST TWO raw frames only, and
                        // whether that single pair happened to be
                        // detectable decided the whole capture: on the
                        // browser news page the same code cut the
                        // floating dock correctly in one run (v1.18
                        // test) and missed it entirely in the next
                        // (v1.19 test: dock and round button stitched
                        // into every frame - one copy per captured
                        // frame, so the count grows with the capture). Whatever
                        // sits behind the translucent dock in the first
                        // pair (a playing video, a state swap, a layout
                        // shift) can poison that one measurement. Now
                        // the first up-to-six frames are held raw,
                        // detection is attempted on successive pairs
                        // ((1,2), (2,3), (1,3), (3,4), (1,4)), and the
                        // held frames are only stitched once the cuts
                        // are settled. A floating cut additionally
                        // needs TWO pairs agreeing on its top within
                        // 24px: with several attempts, one lucky pair
                        // can also produce a FALSE cluster (blank bands
                        // of a plain page aligning at the same screen
                        // rows) - on the bench that false cut removed
                        // 442px from every frame of a bar-free page. A
                        // real dock sits at a fixed screen position, so
                        // genuine detections agree; false ones land at
                        // random positions.
                        Bitmap partnerRaw = deferredRaws.isEmpty() ? loopPreviousFrame
                                : deferredRaws.get(deferredRaws.size() - 1);
                        int maxBar = raw.getHeight() / 3;
                        int sTop = Stitcher.staticTopHeight(partnerRaw, raw, maxBar);
                        int sBot = Stitcher.staticBottomHeight(partnerRaw, raw, maxBar);
                        if (sTop > maxTopSeen) maxTopSeen = sTop;
                        sTopSamples.add(sTop);
                        sBotSamples.add(sBot);
                        // Detections from the two pair kinds
                        // (partner = adjacent frames, span 0;
                        // first-frame = skip pair, span 1), merged
                        // across the band detector (wide elements:
                        // docks, bars) and the cell-cluster detector
                        // (narrow fixed elements: floating buttons).
                        // Confirmation discipline lives in
                        // confirmPair: same detector type only, and
                        // cell detections must reproduce at a
                        // different scroll-step scale - self-similar
                        // content can coincide at one scale, a real
                        // fixed element is static at every scale.
                        int[] detP = detectPair(partnerRaw, raw);
                        int useTop = -1;
                        int useSTop = 0;
                        int useSBot = 0;
                        boolean useTopViaDetF = false;
                        if (detP != null) {
                            for (int[] cand : floatingCandidates) {
                                if (confirmPair(detP, cand)) {
                                    useTop = Math.min(cand[1], detP[1]);
                                    useSTop = sTop;
                                    useSBot = sBot;
                                    break;
                                }
                            }
                        }
                        int[] detF = null;
                        if (useTop < 0 && !deferredRaws.isEmpty()) {
                            detF = detectPair(loopPreviousFrame, raw);
                            if (detF != null) {
                                detF[3] = 1;
                                boolean agree = detP != null && confirmPair(detF, detP);
                                int agreeExt = detF[1];
                                if (agree) agreeExt = Math.min(agreeExt, detP[1]);
                                if (!agree) {
                                    for (int[] cand : floatingCandidates) {
                                        if (confirmPair(detF, cand)) {
                                            agree = true;
                                            agreeExt = Math.min(agreeExt, cand[1]);
                                            break;
                                        }
                                    }
                                }
                                if (agree) {
                                    useTop = agreeExt;
                                    useTopViaDetF = true;
                                    useSTop = Stitcher.staticTopHeight(loopPreviousFrame, raw, maxBar);
                                    useSBot = Stitcher.staticBottomHeight(loopPreviousFrame, raw, maxBar);
                                } else {
                                    floatingCandidates.add(detF);
                                }
                            }
                        }
                        debugLog.append("frame ").append(loopFrames)
                                .append(": sTop=").append(sTop)
                                .append(" sBot=").append(sBot)
                                .append(" detP=").append(detP == null ? "null"
                                        : detP[0] + "/" + detP[1] + "/t" + detP[2])
                                .append(" detF=").append(detF == null ? "null"
                                        : detF[0] + "/" + detF[1] + "/t" + detF[2])
                                .append(" useTop=").append(useTop)
                                .append(" ncands=").append(floatingCandidates.size())
                                .append("\n");
                        // Mid-screen fixed-element candidates
                        // (v1.30): rects from the same two pair
                        // kinds, tagged with their span (0
                        // adjacent, 1 skip) for the cross-span
                        // confirmation at finalize time.
                        int[][] rectsP = Stitcher.fixedElementRects(partnerRaw, raw);
                        for (int[] rc : rectsP) {
                            maskCandidates.add(new int[]{rc[0], rc[1], rc[2], rc[3], rc[4], rc[5], 0});
                        }
                        int nRectsF = 0;
                        if (!deferredRaws.isEmpty()) {
                            int[][] rectsF = Stitcher.fixedElementRects(loopPreviousFrame, raw);
                            nRectsF = rectsF.length;
                            for (int[] rc : rectsF) {
                                maskCandidates.add(new int[]{rc[0], rc[1], rc[2], rc[3], rc[4], rc[5], 1});
                            }
                        }
                        debugLog.append("  mask rectsP=").append(rectsP.length)
                                .append(" rectsF=").append(nRectsF)
                                .append(" cands=").append(maskCandidates.size()).append("\n");
                        if (detP != null && useTop < 0) floatingCandidates.add(detP);
                        if (deferredRaws.isEmpty()) {
                            savedStickyTop = sTop;
                            savedStickyBottom = sBot;
                        }
                        boolean floatingFound = useTop > 0
                                && (raw.getHeight() - useTop) > useSBot
                                && useTop > useSTop + 400;
                        boolean finalizeDetection = floatingFound || deferredRaws.size() >= 4;
                        // v1.39.48: BOTTOM-CUT CORROBORATION (Hayes
                        // approved 2026-11-06 22:31). When the
                        // bottom cut rests on the INFERENCE
                        // rawH - useTop (everything below the
                        // floating element is non-document) rather
                        // than on the direct static-bottom
                        // measurement (useSBot), a self-similar
                        // page can fake the static band: capture
                        // 68's pale card stack read a 'static'
                        // band top at 1695 on adjacent pairs only,
                        // implying an 892px cut that amputated a
                        // third of every frame and corrupted the
                        // result's tail, while the direct sBot
                        // samples read 0. Discipline (the same one
                        // confirmPair states for cell detections):
                        // a real fixed element is static at EVERY
                        // scale. So the inference is trusted at
                        // once only when the skip pair (detF, ~2x
                        // step) agreed on useTop; an adjacent-only
                        // sighting must first REPRODUCE at the
                        // next pair (within 24px) - one extra held
                        // frame - or the cut falls back to the
                        // measured useSBot.
                        boolean floatCutTrusted = true;
                        if (floatingFound && (raw.getHeight() - useTop) > useSBot
                                && !useTopViaDetF) {
                            if (pendingFloatUseTop > 0) {
                                floatCutTrusted = Math.abs(useTop - pendingFloatUseTop) <= 24;
                                if (capLog != null) capLog.append("  floating bottom proposal ")
                                        .append(pendingFloatUseTop).append(" reproduced as ")
                                        .append(useTop).append(": trusted=").append(floatCutTrusted).append("\n");
                            } else if (deferredRaws.size() < 4) {
                                pendingFloatUseTop = useTop;
                                finalizeDetection = false;
                                if (capLog != null) capLog.append("  floating bottom proposal useTop=")
                                        .append(useTop).append(" impliedCut=")
                                        .append(raw.getHeight() - useTop)
                                        .append(" (adjacent-scale only) - awaiting reproduction\n");
                            } else {
                                floatCutTrusted = false;
                            }
                        }
                        if (!finalizeDetection) {
                            // Hold this frame raw and scroll on; the
                            // next frame gives detection another pair.
                            // Still report the true captured count -
                            // v1.20 left the frame-1 text up until
                            // detection settled several scrolls later,
                            // so the overlay seemed stuck at 1 frame.
                            deferredRaws.add(raw);
                            setOverlayText("已截 " + loopFrames + " 帧");
                            updateNotification("已截 " + loopFrames + " 帧，正在拼接…");
                            // v1.39.49: re-check the stop flag
                            // BEFORE this scroll. The decision
                            // point above may have just registered
                            // a stop (volume drop / pill window)
                            // and even retaken this frame as the
                            // final one, but the post-stitch check
                            // at the loop bottom never runs in the
                            // detection phase (frames are held, not
                            // stitched) - capture 69's first press
                            // was registered instantly yet the loop
                            // still scrolled once more, which felt
                            // like 'did not stop'. Breaking here
                            // lets the end-of-loop flush stitch the
                            // held frames exactly as before, minus
                            // the unwanted extra scroll.
                            if (stopRequested || abortCapture || gapStop) break;
                            boolean scrolled = scrollOnce();
                            if (!scrolled) {
                                if (!scrollEndOrManualWait()) break;
                            }
                            sleep(250);
                            continue;
                        }
                        debugLog.append("FINAL at frame ").append(loopFrames)
                                .append(": floatingFound=").append(floatingFound)
                                .append(" useTop=").append(useTop).append("\n");
                        stickyDetected = true;
                        if (floatingFound) {
                            stickyTop = useSTop;
                            stickyBottom = useSBot;
                            // v1.39.48: an uncorroborated inference
                            // cut falls back to the directly
                            // measured static bottom (useSBot).
                            bottomCut = floatCutTrusted
                                    ? Math.max(useSBot, raw.getHeight() - useTop)
                                    : useSBot;
                            if (!floatCutTrusted && capLog != null) {
                                capLog.append("  floating bottom cut UNCORROBORATED: implied ")
                                        .append(raw.getHeight() - useTop)
                                        .append(" -> fallback measured sBot ").append(useSBot).append("\n");
                            }
                        } else {
                            stickyTop = savedStickyTop;
                            stickyBottom = savedStickyBottom;
                            bottomCut = stickyBottom;
                        }
                        // v1.39.42: the finalized sticky top must
                        // be the value the detection pairs
                        // CORROBORATE, not one pair's read. The
                        // first pair and the skip pair both
                        // under-read on pages whose fixed header
                        // contains an animated element (device
                        // captures 44/46: adjacent pairs read 325
                        // three times running, the finalized value
                        // was 23/241). An under-read leaves fixed
                        // chrome inside every content frame, and
                        // the deferred fill then copies those
                        // chrome rows into document positions -
                        // the header "ghost strips" down capture
                        // 44's result. Two pairs at different
                        // scroll offsets agreeing within 24px
                        // cannot both be fooled: scrolling
                        // content never holds still twice.
                        int corrTop = corroboratedTop(sTopSamples);
                        if (corrTop > stickyTop) {
                            debugLog.append("  stickyTop corroborated ")
                                    .append(stickyTop).append(" -> ").append(corrTop).append("\n");
                            stickyTop = corrTop;
                        }
                        if (bottomCut > stickyBottom) {
                            // Floating cluster confirmed - coverage
                            // rule: a document point in the excluded
                            // bottom band reaches the kept region next
                            // frame only if the scroll step exceeds the
                            // excluded height, so raise the adaptive
                            // step floor accordingly.
                            if (CaptureSession.lastScrollDistancePx > 0
                                    && CaptureSession.scrollStepFactor > 0) {
                                double nodeSize = CaptureSession.lastScrollDistancePx
                                        / CaptureSession.scrollStepFactor;
                                if (nodeSize > 0) {
                                    loopStepFloor = Math.min(0.45, Math.max(0.20,
                                            (bottomCut + 120.0) / nodeSize));
                                }
                            }
                        }
                        if (stickyTop + bottomCut >= raw.getHeight() * 0.7) {
                            stickyTop = 0;
                            stickyBottom = 0;
                            bottomCut = 0;
                        }
                        // Chrome that survives inside the content
                        // frames: some pairs see a taller static
                        // top than the pair that settled the cut
                        // (capture 7: 325 vs the cut 75 - the
                        // browser's rotating search hint breaks
                        // the static run on later pairs, but the
                        // tab bar below it is still screen-fixed).
                        // The seam guard uses the tallest seen.
                        if (stickyTop > maxTopSeen) maxTopSeen = stickyTop;
                        loopChromeBottom = Math.max(0, maxTopSeen - stickyTop);
                        // Confirm mid-screen fixed elements
                        // (v1.30): a candidate rect reproduced by
                        // another candidate from a pair at a
                        // DIFFERENT scroll-step span, overlapping
                        // >= 50% of the smaller box, confirms it
                        // (a content coincidence can align at one
                        // step scale; a real fixed element is
                        // static at every scale). Confirmed union
                        // boxes move to content coordinates;
                        // chrome-suspect boxes (hugging the top
                        // bar, inside the bottom cut, nearly
                        // full-width) are dropped, overlaps merge.
                        loopFixedRects.clear();
                        {
                            int contentH = raw.getHeight() - stickyTop - bottomCut;
                            java.util.List<int[]> confirmed = new java.util.ArrayList<>();
                            for (int i = 0; i < maskCandidates.size(); i++) {
                                int[] rc = maskCandidates.get(i);
                                for (int j = i + 1; j < maskCandidates.size(); j++) {
                                    int[] rd = maskCandidates.get(j);
                                    if (rc[6] == rd[6]) continue;
                                    int ix0 = Math.max(rc[0], rd[0]);
                                    int iy0 = Math.max(rc[1], rd[1]);
                                    int ix1 = Math.min(rc[2], rd[2]);
                                    int iy1 = Math.min(rc[3], rd[3]);
                                    if (ix1 <= ix0 || iy1 <= iy0) continue;
                                    long inter = (long) (ix1 - ix0) * (iy1 - iy0);
                                    long aMin = Math.min(
                                            (long) (rc[2] - rc[0]) * (rc[3] - rc[1]),
                                            (long) (rd[2] - rd[0]) * (rd[3] - rd[1]));
                                    if (aMin <= 0 || inter * 2 < aMin) continue;
                                    confirmed.add(new int[]{
                                            Math.min(rc[0], rd[0]), Math.min(rc[1], rd[1]),
                                            Math.max(rc[2], rd[2]), Math.max(rc[3], rd[3]),
                                            Math.min(rc[4], rd[4]), Math.max(rc[5], rd[5])});
                                }
                            }
                            for (int[] u : confirmed) {
                                int y0 = u[1] - stickyTop;
                                int y1 = Math.min(u[3] - stickyTop, contentH);
                                int cy0 = Math.max(0, u[4] - stickyTop);
                                int cy1 = Math.min(contentH, u[5] - stickyTop);
                                if (cy0 >= cy1) {
                                    // The element straddles the
                                    // bottom cut: its detailed core
                                    // sits inside the cut zone and
                                    // clips to nothing. Fall back to
                                    // the box's own rows for the
                                    // occlusion test - an empty core
                                    // would make the test vacuous
                                    // and let a small-step fill copy
                                    // the element itself (found by
                                    // offline replay of a real
                                    // capture whose button straddled
                                    // the cut line).
                                    cy0 = y0;
                                    cy1 = y1;
                                }
                                if (y0 < 40 || y0 >= contentH) continue;
                                if (u[2] - u[0] >= raw.getWidth() * 0.92) continue;
                                if (y1 - y0 < 24) continue;
                                // A "fixed element" taller than 30% of
                                // the content band is not a floating
                                // button/dock; it is a content card
                                // mis-detected as static (this capture
                                // produced a 768px mask over the
                                // recommendation card and the first
                                // join starved). Keep the mask for
                                // small chrome only.
                                if (y1 - y0 > contentH * 0.30) continue;
                                boolean merged = false;
                                for (int[] ex : loopFixedRects) {
                                    if (u[0] < ex[2] && ex[0] < u[2] && y0 < ex[3] && ex[1] < y1) {
                                        ex[0] = Math.min(ex[0], u[0]);
                                        ex[1] = Math.min(ex[1], y0);
                                        ex[2] = Math.max(ex[2], u[2]);
                                        ex[3] = Math.max(ex[3], y1);
                                        ex[4] = Math.min(ex[4], cy0);
                                        ex[5] = Math.max(ex[5], cy1);
                                        merged = true;
                                        break;
                                    }
                                }
                                if (!merged) {
                                    loopFixedRects.add(new int[]{u[0], y0, u[2], y1, cy0, cy1});
                                }
                            }
                            StringBuilder mb = new StringBuilder("FINAL mask: ");
                            mb.append(loopFixedRects.size()).append(" rects");
                            for (int[] r : loopFixedRects) {
                                mb.append(" [").append(r[0]).append(',').append(r[1])
                                        .append("->").append(r[2]).append(',').append(r[3]).append(']');
                            }
                            debugLog.append(mb).append("\n");
                        }
                        // Overlap-health cap (v1.29): the scroll
                        // step must leave a healthy overlap of the
                        // CONTENT band (>= ~35%), or the matcher
                        // starves - replaying Hayes's v1.28 capture
                        // at the v1.29 cut (content 1809, step
                        // ~1375, overlap 24%) broke two joins that
                        // were exact at the old cut's 36%. The step
                        // factor is relative to the scroll node's
                        // height, which can exceed the screen, so
                        // cap the factor by the content height:
                        // step <= 0.65 * contentH.
                        if (CaptureSession.lastScrollDistancePx > 0
                                && CaptureSession.scrollStepFactor > 0) {
                            double nodeSize = CaptureSession.lastScrollDistancePx
                                    / CaptureSession.scrollStepFactor;
                            int contentH = raw.getHeight() - stickyTop - bottomCut;
                            if (nodeSize > 0 && contentH > 0) {
                                loopStepCap = Math.max(0.15, Math.min(0.25,
                                        0.65 * contentH / nodeSize));
                                if (loopStepFloor > loopStepCap) {
                                    loopStepFloor = loopStepCap;
                                }
                                if (CaptureSession.scrollStepFactor > loopStepCap) {
                                    CaptureSession.scrollStepFactor = loopStepCap;
                                }
                                if (!loopFixedRects.isEmpty()) {
                                    int minY0 = Integer.MAX_VALUE;
                                    for (int[] r : loopFixedRects) {
                                        minY0 = Math.min(minY0, r[4]);
                                    }
                                    // Occlusion coverage (v1.30):
                                    // with the step no larger than
                                    // the highest element's top,
                                    // every occluded document row
                                    // reappears clear of the element
                                    // in the next frame and the
                                    // deferred fill restores it.
                                    // Bounded below so a high
                                    // element cannot stall the
                                    // capture; rows left behind
                                    // simply keep the element once.
                                    double covCap = Math.max(minY0, 0.35 * contentH) / nodeSize;
                                    loopStepCap = Math.min(loopStepCap,
                                            Math.max(0.15, Math.min(0.25, covCap)));
                                    if (loopStepFloor > loopStepCap) {
                                        loopStepFloor = loopStepCap;
                                    }
                                    if (CaptureSession.scrollStepFactor > loopStepCap) {
                                        CaptureSession.scrollStepFactor = loopStepCap;
                                    }
                                }
                            }
                        }
                        if (stickyTop > 0 || bottomCut > 0) {
                            if (stickyTop > 0) {
                                firstTopBar = copyStrip(loopPreviousFrame, 0, stickyTop);
                            }
                            Bitmap contentFirst = cropContent(loopPreviousFrame, stickyTop, bottomCut);
                            Bitmap oldFirst = loopStitched;
                            loopStitched = contentFirst;
                            // oldFirst IS loopPreviousFrame (first raw
                            // frame); its pixels are copied into
                            // firstTopBar and contentFirst, and
                            // loopPreviousFrame is replaced by the
                            // stitching below, so recycling is safe.
                            if (oldFirst != contentFirst) oldFirst.recycle();
                            loopPreviousFrame = null;
                        }
                        // Register the first frame's occluded
                        // rows (v1.30): they are already in the
                        // canvas (document rows hidden behind a
                        // confirmed fixed element); later frames
                        // may restore them via the deferred fill.
                        loopPendings.clear();
                        if (!loopFixedRects.isEmpty() && loopStitched != null) {
                            int ch = loopStitched.getHeight();
                            for (int ri = 0; ri < loopFixedRects.size(); ri++) {
                                int[] rect = loopFixedRects.get(ri);
                                for (int r = rect[1]; r < rect[3] && r < ch; r++) {
                                    loopPendings.add(new int[]{r, ri, 0});
                                }
                            }
                        }
                        // Stitch the held frames now, in capture
                        // order, through the same per-frame unit as
                        // everything else; the current frame falls
                        // through to the shared stitch below.
                        for (Bitmap heldRaw : deferredRaws) {
                            if (stopRequested || abortCapture) {
                                heldRaw.recycle();
                                continue;
                            }
                            if (bottomCut > 0) {
                                Bitmap nb = copyStrip(heldRaw, heldRaw.getHeight() - bottomCut, bottomCut);
                                if (lastBottomStrip != null && lastBottomStrip != nb) lastBottomStrip.recycle();
                                lastBottomStrip = nb;
                            }
                            Bitmap heldFrame = (stickyTop > 0 || bottomCut > 0)
                                    ? cropContent(heldRaw, stickyTop, bottomCut) : heldRaw;
                            if (heldFrame == null) heldFrame = heldRaw;
                            if (heldFrame != heldRaw) heldRaw.recycle();
                            stitchOneFrame(heldFrame);
                            if (gapStop) break;
                        }
                        deferredRaws.clear();
                        if (stopRequested || abortCapture || gapStop) {
                            // Memory guard fired while stitching the
                            // held frames, or the zero-scroll guard
                            // aborted, or a content gap ended the
                            // stitch; the current raw is unstitched.
                            raw.recycle();
                            break;
                        }
                        if (stickyTop > 0 || bottomCut > 0) {
                            frame = cropContent(raw, stickyTop, bottomCut);
                            if (frame == null) frame = raw;
                        }
                    } else {
                        if (stickyDetected && direction == 0 && (stickyTop > 0 || bottomCut > 0)) {
                            frame = cropContent(raw, stickyTop, bottomCut);
                            if (frame == null) frame = raw;
                        }
                        diff = Stitcher.frameDifference(loopPreviousFrame, frame);
                        double medianDiff2 = Stitcher.rowMedianDifference(loopPreviousFrame, frame);
                        if (diff < 2.0 || medianDiff2 < 1.0) {
                            if (frame != raw) raw.recycle();
                            if (frame != loopPreviousFrame) frame.recycle();
                            noteNoMovement(diff, medianDiff2);
                            boolean scrolled = scrollOnce();
                            if (!scrolled) {
                                if (!scrollEndOrManualWait()) break;
                            }
                            continue;
                        }
                        loopUnchangedStreak = 0;
                    }
                    if (stickyDetected && direction == 0 && bottomCut > 0) {
                        Bitmap newBottom = copyStrip(raw, raw.getHeight() - bottomCut, bottomCut);
                        if (lastBottomStrip != null && lastBottomStrip != newBottom) lastBottomStrip.recycle();
                        lastBottomStrip = newBottom;
                    }
                    if (raw != frame) {
                        // raw's pixels now live on in `frame` (a copy) and
                        // in the bar copies; the full raw frame is done.
                        raw.recycle();
                    }
                    stitchOneFrame(frame);
                }
                if (stopRequested || abortCapture || gapStop) break;
                boolean scrolled = scrollOnce();
                if (!scrolled) {
                    if (!scrollEndOrManualWait()) break;
                }
                sleep(250);
            }
        } catch (Exception e) {
            // v1.39.25: the exception used to be swallowed into
            // lastError only, leaving the capture log silent
            // about why the loop died. Write it where the
            // evidence lives.
            if (capLog != null) capLog.append("loop error: ").append(String.valueOf(e)).append("\n");
            CaptureSession.lastError = e.getMessage();
        } finally {
            loopRunning = false;
            CaptureSession.capturing = false;
            if (panelPrevThumb != null) { panelPrevThumb.recycle(); panelPrevThumb = null; }
            if (debugLog.length() > 0 && CaptureSession.volumeStopRequested) {
                debugLog.append("stop: volume-down key\n");
            }
            if (debugLog.length() > 0 && volumeChangeStop) {
                debugLog.append("stop: volume change (key not consumed)\n");
            }
            if (debugLog.length() > 0 && panelStop) {
                debugLog.append("stop: transient panel cover (stop key seen in frame)\n");
            }
            // Give the fallback stop its volume notch back: on
            // devices that route the key to the audio policy,
            // every volume-down stop CONSUMES a notch, and
            // repeated test stops eventually reach minimum
            // volume - where a press changes nothing and the
            // fallback signal dies (capture 19). Restoring the
            // pre-capture volume keeps the signal alive for the
            // next capture.
            if (volumeChangeStop && captureStartVolume >= 0) {
                try {
                    android.media.AudioManager am = (android.media.AudioManager)
                            getSystemService(AUDIO_SERVICE);
                    if (am != null) am.setStreamVolume(
                            android.media.AudioManager.STREAM_MUSIC, captureStartVolume, 0);
                } catch (Exception ignored) { }
            }
            if (debugLog.length() > 0) {
                debugLog.append("END: stickyTop=").append(stickyTop)
                        .append(" stickyBottom=").append(stickyBottom)
                        .append(" bottomCut=").append(bottomCut)
                        .append(" frames=").append(loopFrames).append("\n");
                // v1.39.43: one-line self-diagnosis. Every fact
                // needed to judge a run without re-reading the
                // whole log: WHY it stopped (with provenance),
                // what detection measured and decided, and the
                // session's realized steps.
                String reason = loopStopReason;
                if (reason == null || reason.isEmpty()) {
                    if (CaptureSession.volumeStopRequested) reason = "volume-down-key";
                    else if (volumeChangeStop) reason = "volume-change";
                    else if (panelStop) reason = "panel-cover";
                    else reason = "page-end/manual/max-frames";
                }
                debugLog.append("SUMMARY stopReason=").append(reason)
                        .append(" durationMs=").append(System.currentTimeMillis() - loopStartMs)
                        .append(" frames=").append(loopFrames)
                        .append(" joins=").append(loopJoinsMatched).append("/")
                        .append(loopJoinsUnmatched)
                        .append(" stickyTop=").append(stickyTop)
                        .append(" samples=").append(sTopSamples)
                        .append(" corrTop=").append(corroboratedTop(sTopSamples))
                        .append(" sBotSamples=").append(sBotSamples)
                        .append(" bottomCut=").append(bottomCut)
                        .append(" maskRects=").append(loopFixedRects.size())
                        .append(" steps=").append(loopRealizedSteps)
                        .append(" kicks=").append(loopKickCount)
                        .append(" vol=").append(captureStartVolume).append("->")
                        .append(currentMusicVolume())
                        .append(" error=").append(CaptureSession.lastError == null
                                ? "-" : CaptureSession.lastError)
                        .append("\n");
                dumpDebugLog(debugLog.toString());
            }
            if (!abortCapture && !gapStop && !deferredRaws.isEmpty() && loopStitched != null && loopPreviousFrame != null) {
                // The capture ended (Stop, error, frame cap) before
                // bar/floating detection settled: stitch the held
                // frames with the first attempt's sticky values
                // instead of dropping them. v1.20 recycled them here,
                // so stopping during the first few scrolls saved an
                // image containing only the first frame even though
                // the page had already scrolled several times.
                // Held frames are only stitchable if the page
                // actually scrolled while they were taken. If no
                // scroll ever succeeded in this capture (the app
                // exposed nothing to scroll - the WeChat case),
                // the held frames are in-place edits of the first
                // frame, and stitching them duplicates content:
                // drop them and keep the first frame. (Frames
                // that reach the hold always moved against their
                // predecessor - the no-movement branch recycles
                // the rest - so when scrolls DID succeed, held
                // frames are genuine.)
                boolean flushTrust = loopAnyScrollOk || loopJoins > 0;
                if (!flushTrust && capLog != null) {
                    capLog.append("flush: held frames dropped (no scroll ever succeeded)\n");
                }
                stickyDetected = true;
                stickyTop = savedStickyTop;
                stickyBottom = savedStickyBottom;
                bottomCut = stickyBottom;
                int corrTopF = corroboratedTop(sTopSamples);
                if (corrTopF > stickyTop) {
                    if (capLog != null) capLog.append("flush: stickyTop corroborated ")
                            .append(stickyTop).append(" -> ").append(corrTopF).append("\n");
                    stickyTop = corrTopF;
                }
                loopChromeBottom = Math.max(0, maxTopSeen - stickyTop);
                if (stickyTop + bottomCut >= loopStitched.getHeight() * 0.7) {
                    stickyTop = 0;
                    stickyBottom = 0;
                    bottomCut = 0;
                }
                if (stickyTop > 0 || bottomCut > 0) {
                    if (stickyTop > 0) {
                        firstTopBar = copyStrip(loopPreviousFrame, 0, stickyTop);
                    }
                    Bitmap contentFirst = cropContent(loopPreviousFrame, stickyTop, bottomCut);
                    Bitmap oldFirst = loopStitched;
                    loopStitched = contentFirst;
                    if (oldFirst != contentFirst) oldFirst.recycle();
                    loopPreviousFrame = null;
                }
                for (Bitmap heldRaw : deferredRaws) {
                    if (!flushTrust) {
                        heldRaw.recycle();
                        continue;
                    }
                    if (bottomCut > 0) {
                        Bitmap nb = copyStrip(heldRaw, heldRaw.getHeight() - bottomCut, bottomCut);
                        if (lastBottomStrip != null && lastBottomStrip != nb) lastBottomStrip.recycle();
                        lastBottomStrip = nb;
                    }
                    Bitmap heldFrame = (stickyTop > 0 || bottomCut > 0)
                            ? cropContent(heldRaw, stickyTop, bottomCut) : heldRaw;
                    if (heldFrame == null) heldFrame = heldRaw;
                    if (heldFrame != heldRaw) heldRaw.recycle();
                    stitchOneFrame(heldFrame);
                    if (gapStop) break;
                }
                deferredRaws.clear();
            } else {
                for (Bitmap held : deferredRaws) held.recycle();
                deferredRaws.clear();
            }
            if (abortCapture) {
                String reason = CaptureSession.lastError != null ? CaptureSession.lastError
                        : "已停止：页面没有滚动";
                if (capLog != null) {
                    capLog.append("RESULT aborted: ").append(reason).append("\n");
                    dumpDebugLog(capLog.toString());
                }
                setOverlayText(reason);
                updateNotification(reason);
                mainThread(() -> Toast.makeText(this, reason, Toast.LENGTH_LONG).show());
            } else if (loopStitched != null) {
                // Re-attach the stripped regions exactly once: the
                // first frame's top bar at the very top, the last
                // frame's bottom cut region (edge bar and/or floating
                // dock cluster) at the very bottom. During stitching
                // the content between them was kept bar-free.
                Bitmap toSave = loopStitched;
                if (firstTopBar != null || lastBottomStrip != null || statusStrip != null) {
                    try {
                        int stH = statusStrip != null ? statusStrip.getHeight() : 0;
                        int topH = firstTopBar != null ? firstTopBar.getHeight() : 0;
                        int botH = lastBottomStrip != null ? lastBottomStrip.getHeight() : 0;
                        Bitmap full = Bitmap.createBitmap(loopStitched.getWidth(),
                                loopStitched.getHeight() + stH + topH + botH, Bitmap.Config.ARGB_8888);
                        android.graphics.Canvas cv = new android.graphics.Canvas(full);
                        if (statusStrip != null) cv.drawBitmap(statusStrip, 0, 0, null);
                        if (firstTopBar != null) cv.drawBitmap(firstTopBar, 0, stH, null);
                        cv.drawBitmap(loopStitched, 0, stH + topH, null);
                        if (lastBottomStrip != null) cv.drawBitmap(lastBottomStrip, 0, stH + topH + loopStitched.getHeight(), null);
                        toSave = full;
                    } catch (Exception ignored) {
                        toSave = loopStitched;
                    }
                }
                String path = CaptureSession.writePreview(this, toSave);
                if (capLog != null) {
                    capLog.append("RESULT saved: ").append(toSave.getWidth()).append("x")
                            .append(toSave.getHeight())
                            .append(" path=").append(path)
                            .append(" bytes=").append(path != null ? new java.io.File(path).length() : -1)
                            .append("\n");
                    dumpDebugLog(capLog.toString());
                }
                if (toSave != loopStitched) toSave.recycle();
                if (firstTopBar != null) firstTopBar.recycle();
                if (lastBottomStrip != null) lastBottomStrip.recycle();
                if (statusStrip != null) { statusStrip.recycle(); statusStrip = null; }
                if (pendingTopStrip != null) { pendingTopStrip.recycle(); pendingTopStrip = null; }
                CaptureSession.lastPreviewPath = path;
                if (path != null) {
                    openResult(path);
                } else {
                    Toast.makeText(this, "生成预览失败", Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "没有截到任何画面", Toast.LENGTH_LONG).show();
            }
            if (quitRequested) {
                cleanupAndStop();
            } else {
                // Keep the service and the MediaProjection alive so the user
                // can start another capture without granting screen capture
                // again. Only "退出" on the overlay stops the service.
                stopRequested = false;
                if (CaptureSession.accessibilityService != null) {
                    CaptureSession.accessibilityService.resetScrollState();
                }
                mainThread(() -> {
                    if (overlayStart != null) overlayStart.setVisibility(View.VISIBLE);
                    if (overlayStop != null) overlayStop.setVisibility(View.GONE);
                });
                updateNotification("已就绪：可再次开始，无需重新授权，点悬浮窗“退出”结束");
            }
            // The END line and the first dump happen at the top
            // of this finally, BEFORE the held frames are
            // stitched - their join lines (and any late scroll
            // attempts) only exist by now. Dump once more so the
            // file on disk is complete.
            if (debugLog.length() > 0) {
                drainScrollLog(CaptureSession.accessibilityService);
                dumpDebugLog(debugLog.toString());
            }
        }
    }


    /**
     * Stitch one content frame into the running long image (the
     * per-frame unit of captureLoop, extracted in v1.20 so the
     * deferred-detection frames can be stitched through exactly the
     * same path). The caller owns the frame's raw source: strip
     * bookkeeping and raw recycling happen there; this method takes
     * ownership of `frame` (it becomes loopPreviousFrame).
     */
    private void stitchOneFrame(Bitmap frame) {
        double expectedOverlap;
        if (CaptureSession.lastScrollDistancePx > 0 && frame.getHeight() > 0) {
            expectedOverlap = 1.0 - (double) CaptureSession.lastScrollDistancePx / frame.getHeight();
        } else {
            expectedOverlap = 0.55;
        }
        expectedOverlap = Math.max(0.05, Math.min(0.9, expectedOverlap));
        Stitcher.Result r;
        int joinOverlap = -1;
        boolean joinMatched = false;
        int dupBlock = 0;
        String joinKind = "h";
        if (CaptureSession.lastDirection == 1) {
            r = Stitcher.stitchHorizontal(loopStitched, frame, expectedOverlap);
        } else {
            // Find the overlap (consensus bands + global
            // fallback + full-resolution refinement, all in
            // Stitcher), then stitch append-only with it.
            // The v1.14 patch back-fill that used to sit
            // between these two steps is REMOVED: it drew
            // hundreds of small patches from the next frame
            // into the stitched image, and whenever that
            // frame's alignment was off by a few pixels the
            // patches themselves became the garbled strips
            // seen all over the browser results. Nothing is
            // ever drawn into the stitched image any more
            // except whole appended content strips.
            // Split stitch first: if the page reflowed mid-capture
            // (a video player or lazy image expanded, shifting the
            // lower part of this frame against the stitched canvas),
            // no single offset aligns both sides - the split path
            // stitches two segments and hides the seam inside the
            // boundary band. Its gates are strict (two tight band
            // clusters, both segments verified, both implied
            // scrolls plausible); when they do not fire, the normal
            // single-offset path below runs unchanged.
            Stitcher.Result splitResult = Stitcher.trySplitStitch(
                    loopStitched, frame, expectedOverlap, 0.0, 0.0);
            if (splitResult != null) {
                joinKind = "split";
                joinOverlap = splitResult.overlap;
                joinMatched = true;
                double splitRatio = frame.getHeight() > 0
                        ? (double) splitResult.overlap / frame.getHeight() : 0;
                if (splitRatio < 0.30) {
                    CaptureSession.scrollStepFactor = Math.max(loopStepFloor,
                            CaptureSession.scrollStepFactor * 0.7);
                } else {
                    CaptureSession.scrollStepFactor = Math.min(loopStepCap,
                            CaptureSession.scrollStepFactor * 1.2);
                }
                r = splitResult;
            } else {
            Stitcher.OverlapInfo oi = Stitcher.findVerticalOverlap(
                    loopStitched, frame, expectedOverlap, 0.0);
            if (!oi.matched && !loopRealizedSteps.isEmpty()) {
                // v1.39.42: session-step prior. The matcher
                // already searches +/-40% around the nominal
                // overlap and still failed, so the nominal
                // gesture distance is the weakest evidence left
                // on the table; this capture's own realized
                // steps (same page, same fling dynamics, seconds
                // apart) are far stronger. Median of the last
                // three matched steps, overlap clamped to the
                // plausible band. matched stays false: the
                // step-shrink feedback and the first-join guard
                // keep treating this join as uncertified.
                int n = loopRealizedSteps.size();
                java.util.ArrayList<Integer> recent = new java.util.ArrayList<>(
                        loopRealizedSteps.subList(Math.max(0, n - 3), n));
                java.util.Collections.sort(recent);
                int medStep = recent.get(recent.size() / 2);
                int prior = frame.getHeight() - medStep;
                prior = Math.max(Math.max(24, (int) (frame.getHeight() * 0.05)),
                        Math.min((int) (frame.getHeight() * 0.95), prior));
                // v1.39.47 introduced prior verification (+-6
                // whole-zone median, gate 30); v1.39.50 replaces
                // it with the AGREEMENT SCAN (Stitcher
                // .verifyStepPrior). Captures 70/71 showed the
                // median probe's two blind spots on one join:
                // the realized step came up ~185px short, so the
                // truth sat ~750px from the prior (a +-6 window
                // cannot reach it), and two playing videos
                // dominated the overlap zone, pushing the median
                // to 57-78 even at the TRUE alignment. The scan
                // searches every plausible step for the offset
                // whose STATIC rows agree - the statistic a
                // video cannot fake and a gap cannot produce.
                // Calibration (ProbeVerify): accepts at the true
                // overlap on captures 70/71 (0.33), 43 (0.31) and
                // 67 (0.57); capture 65's true gap peaks at 0.08
                // anywhere. Gate 0.20.
                int[] ver = Stitcher.verifyStepPrior(loopStitched, frame, prior, medStep);
                int foundOv = ver[0];
                double bestAgree = ver[1] / 1000.0;
                int scanBestOv = ver[2];
                double medAtFound = foundOv > 0
                        ? Stitcher.overlapZoneMedianDiff(loopStitched, frame, foundOv) : -1;
                if (capLog != null) capLog.append("  unmatched join: step-prior overlap ")
                        .append(oi.overlap).append(" -> ").append(prior)
                        .append(" (median step ").append(medStep)
                        .append("), agreement scan: best=")
                        .append(String.format(java.util.Locale.US, "%.3f", bestAgree))
                        .append(" at ").append(scanBestOv)
                        .append(foundOv > 0 ? ", zone median diff at found="
                                + String.format(java.util.Locale.US, "%.1f", medAtFound) : "")
                        .append("\n");
                if (foundOv > 0) {
                    oi = new Stitcher.OverlapInfo(foundOv, false);
                } else {
                    // Content gap (or an outlier step the prior
                    // cannot represent): stop here and SAVE the
                    // certified canvas instead of fabricating
                    // the join - and instead of discarding the
                    // whole capture the way the old abort did
                    // (capture 65 lost a certified 2782px canvas
                    // that way). Honest truncation beats a
                    // seamless lie.
                    gapStop = true;
                    loopStopReason = "content-gap(partial saved)";
                    CaptureSession.lastError = "页面内容跳变，中间一段没有截到，已保存前面截到的部分";
                    if (capLog != null) capLog.append("  step-prior REJECTED (best agreement ")
                            .append(String.format(java.util.Locale.US, "%.3f", bestAgree))
                            .append(" < 0.20): content gap - stopping, certified canvas will be saved\n");
                    frame.recycle();
                    return;
                }
            }
            // First-join confidence guard. A 45px effective overlap
            // cannot certify a 1405px frame: the matcher accepted
            // overlap 72, implied a 1333px jump, and the saved image
            // lost the "青萍" row. The conservative first step makes
            // this rare; if it still happens, fail visibly instead
            // of saving a silently broken long image.
            // v1.39.47: the guard protects the FIRST JOIN, so key
            // it on the join counters, not the capture's frame
            // count. The old loopFrames<=4 form also covered the
            // second join during the deferred flush (loopFrames
            // is still small then), where a verified step-prior
            // join is legitimate - capture 65's flush hit exactly
            // that collision.
            if (loopJoinsMatched == 0 && loopJoinsUnmatched == 0) {
                int minFirst = Math.max(120, (int) (frame.getHeight() * 0.18));
                if (!oi.matched || oi.overlap < minFirst) {
                    abortCapture = true;
                    CaptureSession.lastError = "首帧重叠不足（页面可能正在刷新），已停止，请重试";
                    frame.recycle();
                    return;
                }
            }
            // Boundary duplicate guard: near the page bottom the
            // overlap is large and one item-block can be matched one
            // row too high. If the new frame's first block is
            // identical to the canvas tail, include it in the
            // overlap instead of appending a duplicate.
            int useOverlap = oi.overlap;
            // Keep this boundary-only: the normal conservative step
            // now leaves ~64% overlap, and running the block guard
            // on every join would risk false corrections. The
            // observed duplicate was the page-bottom join at 71%.
            // v1.39.45: the gate drops 0.67 -> 0.50. Capture 52's
            // aliased join read 0.649 of frame height and slipped
            // 30 px under the gate while carrying a 452 px
            // duplicate block; the guard's own acceptance tests
            // (near-identical rows AND internal edge energy) are
            // what keep it honest, and the offline sweep with the
            // wider gate + raised ceiling fired 0 on all 25
            // healthy joins of captures 49/50/52.
            if (oi.matched && loopFrames > 4 && oi.overlap > frame.getHeight() * 0.50) {
                dupBlock = Stitcher.duplicateBlockHeight(loopStitched, frame, oi.overlap);
                if (dupBlock > 0 && oi.overlap + dupBlock < frame.getHeight() - 24) {
                    useOverlap = oi.overlap + dupBlock;
                }
            }
            // Stitch-rate feedback: too little overlap (or no
            // confident match) means we are scrolling too
            // far per step for this page, so shrink the next
            // step (never below loopStepFloor, which the
            // floating-cut coverage rule may have raised);
            // a healthy overlap lets it grow back.
            double overlapRatio = frame.getHeight() > 0
                    ? (double) useOverlap / frame.getHeight() : 0;
            if (!oi.matched || overlapRatio < 0.30) {
                CaptureSession.scrollStepFactor = Math.max(loopStepFloor,
                        CaptureSession.scrollStepFactor * 0.7);
            } else {
                CaptureSession.scrollStepFactor = Math.min(loopStepCap,
                        CaptureSession.scrollStepFactor * 1.2);
            }
            if (oi.matched) {
                loopRealizedSteps.add(frame.getHeight() - useOverlap);
            }
            r = Stitcher.stitchVerticalWithOverlap(loopStitched, frame, useOverlap, oi.matched,
                    loopChromeBottom);
                joinKind = "single";
                joinOverlap = useOverlap;
                joinMatched = oi.matched;
            }
        }
        Bitmap old = loopStitched;
        Bitmap oldPrev = loopPreviousFrame;
        loopStitched = r.bitmap;
        // The seam placement may have moved the splice up inside
        // the overlap; the result reports the EFFECTIVE overlap,
        // and the mask fill's row mapping must use it (the
        // matcher overlap above stays the value the step guard
        // and the join log judge the scroll by).
        int stitchOverlap = r.overlap;
        // v1.39.28: the fill's canvas anchor must be the
        // NOMINAL overlap (joinOverlap - the value the stitch
        // was called with), NOT the seam. The canvas grows by
        // frameH - joinOverlap no matter where the seam cuts,
        // and a frame row y lands at canvas row
        // (oldH - joinOverlap + y). Anchoring on the seam
        // instead shifted every pending registration and fill
        // source row by (joinOverlap - seam) whenever the seam
        // moved: capture 30's 493 px seam move made the fills
        // paste shifted bands over the result (Hayes saw the
        // fishing ad and the drama header duplicated). Proven
        // offline by an A/B simulation of this exact pending/
        // fill arithmetic on capture 30's frames: with the seam
        // anchor a distinctive band is overwritten/lost, with
        // the nominal anchor the fills are exact no-ops where
        // the content already agrees.
        // Deferred fill for mid-screen fixed elements (v1.30):
        // document rows appended while hidden behind a confirmed
        // fixed element are restored from THIS frame, where the
        // same document rows sit clear of it; rows this frame
        // appends from behind the element become new pendings.
        // Single matched joins only - a split or unmatched join
        // has no trustworthy row mapping, and its occluded rows
        // simply keep the element (the pre-v1.30 behaviour).
        if (!loopFixedRects.isEmpty() && "single".equals(joinKind) && joinMatched) {
            int frameTop = old.getHeight() - joinOverlap;
            int fillFilled = 0, fillBand = 0, fillHeld = 0, fillStale = 0, fillReg = 0;
            java.util.Iterator<int[]> itp = loopPendings.iterator();
            while (itp.hasNext()) {
                int[] pd = itp.next();
                int[] rect = loopFixedRects.get(pd[1]);
                int srcRow = pd[0] - frameTop;
                if (srcRow < 0) {
                    fillStale++;
                    itp.remove();
                    continue;
                }
                if (srcRow < loopChromeBottom) {
                    // v1.39.42: never restore a document row
                    // from a frame's provably-static top band.
                    // Those rows are screen-fixed chrome, not
                    // document content; copying them is exactly
                    // how header slices were pasted into
                    // document positions on capture 44, where a
                    // fixed rect's pending rows mapped back to
                    // source rows inside the (under-cropped)
                    // header band of the next frame.
                    fillBand++;
                    itp.remove();
                    continue;
                }
                if (srcRow >= rect[4] && srcRow < rect[5]) {
                    fillHeld++;
                    if (++pd[2] > 3) itp.remove();
                    continue;
                }
                if (srcRow < frame.getHeight()) {
                    Stitcher.fillRowSegment(loopStitched, pd[0], frame, srcRow, rect[0], rect[2]);
                    fillFilled++;
                }
                itp.remove();
            }
            for (int ri = 0; ri < loopFixedRects.size(); ri++) {
                int[] rect = loopFixedRects.get(ri);
                int r0 = Math.max(stitchOverlap, rect[1]);
                int r1 = Math.min(rect[3], frame.getHeight());
                for (int srow = r0; srow < r1; srow++) {
                    loopPendings.add(new int[]{frameTop + srow, ri, 0});
                    fillReg++;
                }
            }
            if (capLog != null) {
                capLog.append("  fill frame ").append(loopFrames)
                        .append(": filled=").append(fillFilled)
                        .append(" staticBandDropped=").append(fillBand)
                        .append(" stillCovered=").append(fillHeld)
                        .append(" stale=").append(fillStale)
                        .append(" registered=").append(fillReg)
                        .append(" pendings=").append(loopPendings.size())
                        .append("\n");
            }
        }
        if (old != frame) old.recycle();
        // Previous content frame was only needed for the diff
        // above; recycle it now instead of leaking one full
        // frame per iteration (old code leaked it).
        if (oldPrev != null && oldPrev != old && oldPrev != frame && oldPrev != loopStitched) {
            oldPrev.recycle();
        }
        loopPreviousFrame = frame;
        // Zero-scroll guard (v1.35): a matched vertical join whose
        // realized step is a small fraction of the gesture just
        // performed means the page did not actually scroll - the
        // accessibility connection is dead, or a layer is eating
        // the gestures. Capture 6 (2026-11-04) proved the cost of
        // not noticing: the service never connected after an app
        // update, the document drifted ~20px per "scroll", the
        // loop stitched near-identical frames into a torn image,
        // floating detection hallucinated an 854px cut from the
        // barely-moved pairs, and the garbage was saved as a
        // successful capture. Two consecutive collapsed joins
        // abort the capture (the finally block then skips the
        // save). A genuine page bottom cannot trip this: after a
        // bottom step no further join is stitched, because the
        // scroll itself reports exhaustion first.
        // Track the last intended vs realized scroll distance for the
        // premature-end guard in scrollEndOrManualWait().
        lastIntendedStep = CaptureSession.lastScrollDistancePx;
        lastRealizedStep = (joinOverlap >= 0) ? frame.getHeight() - joinOverlap : 0;
        if (CaptureSession.lastDirection == 0 && joinMatched && joinOverlap >= 0
                && CaptureSession.lastScrollDistancePx >= 200) {
            int realizedStep = frame.getHeight() - joinOverlap;
            if (realizedStep < Math.max(40, (int) (CaptureSession.lastScrollDistancePx * 0.2))) {
                if (++loopZeroStreak >= 2) {
                    abortCapture = true;
                    CaptureSession.lastError = "页面没有滚动（无障碍可能未连接）：请到无障碍设置把 ScrollShot 关掉再打开一次";
                }
            } else {
                loopZeroStreak = 0;
            }
        }
        setOverlayText("已截 " + loopFrames + " 帧");
        updateNotification("已截 " + loopFrames + " 帧，正在拼接…");
        loopJoins++;
        if (joinMatched) loopJoinsMatched++; else loopJoinsUnmatched++;
        if (capLog != null) {
            capLog.append("join frame ").append(loopFrames)
                    .append(": kind=").append(joinKind)
                    .append(" overlap=").append(joinOverlap)
                    .append(" matched=").append(joinMatched)
                    .append(" eff=").append(stitchOverlap)
                    .append(" dup=").append(dupBlock)
                    .append(" intended=").append(CaptureSession.lastScrollDistancePx)
                    .append(" step=").append(joinOverlap >= 0 ? frame.getHeight() - joinOverlap : -1)
                    .append(" canvasH=").append(loopStitched == null ? -1 : loopStitched.getHeight())
                    .append("\n");
        }
        // Memory guard for very long pages: a single in-memory
        // bitmap of 1272 x 40000 is ~200 MB and can OOM the
        // app mid-capture. Stop cleanly at 28000px and save
        // what we have instead of crashing and losing it all.
        // (Full streaming-to-disk stitching is the structural
        // fix for unlimited height; it is not in this build.)
        if (loopStitched != null && loopStitched.getHeight() >= 28000) {
            setOverlayText("已达长图高度上限，正在保存…");
            stopRequested = true;
        }
    }

    private void cleanupAndStop() {
        removeOverlay();
        stopForeground(true);
        stopSelf();
    }

    // Watches for the volume-down stop signal while a capture
    // runs. The accessibility key filter sets
    // CaptureSession.volumeStopRequested when it can consume
    // the key; on devices that route volume keys to the audio
    // policy first, the key is not consumed - but then the
    // volume itself drops, and that drop is the fallback
    // signal. Polls every 250 ms while the loop runs.
    // Note (v1.39.22): the media-session volume route tried
    // in v1.39.20 is REMOVED. On Hayes's Honor it never
    // delivered a single adjustment callback and the volume
    // did not even drop (the press was absorbed by the
    // phantom remote session), while the volume UI gained a
    // SECOND panel - the cast-icon pill in capture 23's
    // frame 6 is that phantom session - wider and frosted,
    // which also defeated the edge cover test on that frame.
    // The route's goal (no popup at all) is unattainable on
    // this ROM; the single-panel chain (volume-drop watcher,
    // confirmed panel-cover stop, verified retakes) stands.

    // Suspect confirmation for the panel-cover stop
    // (v1.39.21). The delta cover test alone cannot tell a
    // system volume panel from a light photo region - their
    // static statistics are twins (capture 22) - but they
    // differ in TIME: the panel fades or expires within a
    // couple of seconds, page content is pixel-stable. Probe
    // the same screen (no scrolling) after ~1.1 s: band gone
    // or drifting = transient = CONFIRM. Still stable: a
    // photo-level band (< 228 mean; real pill bands measured
    // 233-245) is content = REJECT at once; a system-light
    // band gets one longer probe (~2 s more) to outlast the
    // panel's opaque phase before the final verdict. Probes
    // are never stitched and cost nothing when no suspect
    // arises. Verdict bias: an unverifiable probe (grab
    // failure) confirms - a missed stop press is worse than
    // an early stop.
    // v1.39.36: is the volume pill window present right now?
    // The backdrop-independent companion to the pixel panel
    // tests: the frosted pill over a photo defeats every pixel
    // verifier (capture 40's retaken final frame still held the
    // pill's lower button and it was stitched), but the pill is
    // a window regardless of what it floats over.
    private boolean pillWindowPresent() {
        ScrollAccessibilityService s = CaptureSession.accessibilityService;
        return s != null && s.pollWindows();
    }

    private boolean confirmPanelSuspect(Bitmap prevThumb, Bitmap curThumb) {
        panelSuspectRetake = false;
        float[] band = Stitcher.panelCoverBand(prevThumb, curThumb);
        double meanLevel = band != null ? band[2] : -1;
        boolean winNow = pillWindowPresent();
        if (capLog != null) {
            capLog.append("panel suspect evidence: bandRows=")
                    .append(band != null ? (int) band[0] : -1).append("/")
                    .append(band != null ? (int) band[1] : -1)
                    .append(" mean=").append(String.format(java.util.Locale.US, "%.0f", meanLevel))
                    .append(" pillWindow=").append(winNow)
                    .append(" vol=").append(captureStartVolume).append("->")
                    .append(currentMusicVolume()).append("\n");
        }
        // v1.39.45 attribution: when a banner-class overlay
        // window (a heads-up notification) is up and the pill
        // window is NOT, the band is the banner's doing -
        // reject at once, without the ~2.3 s of probes capture
        // 51 paid five times before the sixth suspect's failed
        // probe grab stopped the run outright.
        if (!winNow && ScrollAccessibilityService.bannerLikePresent) {
            if (capLog != null) {
                capLog.append("panel suspect attributed to a non-pill overlay window - rejected without probes\n");
            }
            return false;
        }
        // v1.39.31: a pill-level band confirms IMMEDIATELY,
        // without the temporal probes. Measured bands: every
        // real volume pill on a light page reads 233-245
        // (captures 17/18/19) and 237 (capture 34, three
        // times); the brightest false photo band reads 224.7
        // (capture 22), and frosted-over-photo bands - true
        // (capture 30) and false (captures 26/29) alike - all
        // read 189-198 and keep the probe path below. In
        // capture 34 the probes vetoed two genuine 237 bands
        // as "stable": a panel kept on screen (the user,
        // seeing no stop, pressed again during the ~2.3 s
        // probe window) is temporally stable, so for this
        // level class the level itself is the decisive
        // evidence and waiting only manufactures a veto.
        // v1.39.43: the fast lane now ALSO requires the pill
        // WINDOW. Capture 47 is the counterexample that retires
        // the level-only rule: a feed's pale loading-placeholder
        // cards produced a 237 band - identical to a real pill's
        // - and the capture stopped itself mid-page with no key
        // pressed (the window poll seconds earlier showed no
        // pill window, and the volume never changed). Capture
        // 34's lesson stands - a held pill defeats the temporal
        // probes - but since v1.39.35 the window layer is the
        // backdrop-independent witness, and it arbitrates the
        // fast lane: pill-level band + pill window = confirm at
        // once; pill-level band alone goes through the probes
        // like everything else.
        if (meanLevel >= 232 && winNow) {
            if (capLog != null) {
                capLog.append("panel suspect fast-confirmed: band mean ")
                        .append(String.format(java.util.Locale.US, "%.0f", meanLevel))
                        .append(" (pill level) + pill window present\n");
            }
            loopStopReason = "panel-cover(window-verified)";
            return true;
        }
        if (meanLevel >= 232 && capLog != null) {
            capLog.append("panel suspect: pill-level band but NO pill window - probing\n");
        }
        sleep(1100);
        Bitmap p1 = captureFrameWithRetry();
        if (p1 == null) {
            // v1.39.45: a failed probe grab is first retried
            // after a display kick (capture 51's false stop
            // came from declaring 'unverifiable' on a single
            // transient grab failure).
            kickDisplay();
            sleep(600);
            p1 = captureFrameWithRetry();
        }
        if (p1 == null) {
            // Still unverifiable - and unverifiable is NOT a
            // stop any more (v1.39.45: pixel evidence never
            // stops the capture alone; stops belong to the
            // pill window and the volume). Reject the suspect
            // and have the caller retake the frame once.
            if (capLog != null) capLog.append("panel suspect: probe grab failed twice - unverifiable, NOT stopping\n");
            panelSuspectRetake = true;
            return false;
        }
        Bitmap p1t = thumb320(p1);
        p1.recycle();
        if (p1t == null) {
            if (capLog != null) capLog.append("panel suspect: probe thumb failed - unverifiable, NOT stopping\n");
            panelSuspectRetake = true;
            return false;
        }
        double[] st = Stitcher.panelBandStability(curThumb, p1t);
        p1t.recycle();
        if (st[0] > 0 && (st[1] < 0.4 || st[2] > 5.0)) {
            // v1.39.45: a transient probe verdict confirms ONLY
            // with the pill window as witness (a living page
            // settling or refreshing produces the same numbers -
            // capture 57's list page). Without the window the
            // suspect frame is retaken and the capture rolls on.
            if (pillWindowPresent()) {
                if (capLog != null) capLog.append("panel suspect confirmed by probe 1 + pill window: persist=")
                        .append(String.format(java.util.Locale.US, "%.2f", st[1]))
                        .append(" change=").append(String.format(java.util.Locale.US, "%.1f", st[2])).append("\n");
                loopStopReason = "panel-cover(probe-transient+window)";
                return true;
            }
            if (capLog != null) capLog.append("panel suspect: probe 1 transient but NO pill window - page change, not a stop: persist=")
                    .append(String.format(java.util.Locale.US, "%.2f", st[1]))
                    .append(" change=").append(String.format(java.util.Locale.US, "%.1f", st[2])).append("\n");
            panelSuspectRetake = true;
            return false;
        }
        if (meanLevel >= 0 && meanLevel < 228) {
            if (capLog != null) capLog.append("panel suspect rejected: photo-level band, stable\n");
            return false;
        }
        // v1.39.27: 2000 -> 1200 ms. Probe 1 lands at ~1.1 s
        // and this probe at ~2.3 s total, still past the
        // panel's opaque phase (a real panel has faded by
        // then and reads as transient); a photo band is as
        // static at 2.3 s as at 3.1 s, so the reject power is
        // unchanged - only the stop latency shrinks.
        sleep(1200);
        Bitmap p2 = captureFrameWithRetry();
        if (p2 == null) {
            kickDisplay();
            sleep(600);
            p2 = captureFrameWithRetry();
        }
        if (p2 == null) {
            if (capLog != null) capLog.append("panel suspect: probe 2 grab failed twice - unverifiable, NOT stopping\n");
            panelSuspectRetake = true;
            return false;
        }
        Bitmap p2t = thumb320(p2);
        p2.recycle();
        if (p2t == null) {
            if (capLog != null) capLog.append("panel suspect: probe 2 thumb failed - unverifiable, NOT stopping\n");
            panelSuspectRetake = true;
            return false;
        }
        st = Stitcher.panelBandStability(curThumb, p2t);
        p2t.recycle();
        boolean transient2 = st[0] > 0 && (st[1] < 0.4 || st[2] > 5.0);
        if (capLog != null) capLog.append(transient2
                ? "panel suspect probe 2 transient: persist="
                        + String.format(java.util.Locale.US, "%.2f", st[1])
                        + " change=" + String.format(java.util.Locale.US, "%.1f", st[2]) + "\n"
                : "panel suspect rejected after probe 2: persist="
                        + String.format(java.util.Locale.US, "%.2f", st[1])
                        + " change=" + String.format(java.util.Locale.US, "%.1f", st[2]) + "\n");
        // v1.39.45: same window-arbitration as probe 1.
        if (transient2 && pillWindowPresent()) {
            loopStopReason = "panel-cover(probe-transient+window)";
            return true;
        }
        if (transient2) {
            if (capLog != null) capLog.append("panel suspect: probe 2 transient but NO pill window - not stopping\n");
            panelSuspectRetake = true;
        }
        return false;
    }

    private Bitmap thumb320(Bitmap b) {
        try {
            return Bitmap.createScaledBitmap(b, 320,
                    Math.max(1, (int) Math.round(320.0 * b.getHeight() / b.getWidth())), true);
        } catch (Exception e) {
            return null;
        }
    }

    /** Current STREAM_MUSIC volume, or -1 when unreadable. */
    private int currentMusicVolume() {
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            return am != null ? am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void startStopWatcher() {
        final android.media.AudioManager am =
                (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        final int[] lastVol = new int[]{
                am != null ? am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) : -1};
        captureStartVolume = lastVol[0];
        Thread t = new Thread(() -> {
            while (loopRunning) {
                if (CaptureSession.volumeStopRequested) {
                    stopRequested = true;
                    break;
                }
                if (am != null) {
                    int v = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                    if (lastVol[0] >= 0 && v < lastVol[0]) {
                        if (capLog != null) capLog.append("watcher: volume ")
                                .append(lastVol[0]).append("->").append(v).append("\n");
                        loopStopReason = "volume-change(watcher)";
                        volumeChangeStop = true;
                        stopRequested = true;
                        break;
                    }
                    lastVol[0] = v;
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "scrollshot-stopwatch");
        t.setDaemon(true);
        t.start();
    }

    private boolean scrollOnce() {
        ScrollAccessibilityService svc = CaptureSession.accessibilityService;
        if (svc == null) return false;
        // Let the injected gesture pass through a dragged overlay. This
        // only changes the window's TOUCHABLE flag for ~1 s; scroll
        // distance, timing and stitching are not touched.
        setOverlayTouchable(false);
        mainThreadDelayed(() -> setOverlayTouchable(true), 1000);
        boolean ok = svc.scrollNextBlocking(4000);
        if (ok) loopAnyScrollOk = true;
        if (capLog != null) {
            if (svc.lastScrollTimedOut) {
                capLog.append("scroll: TIMEOUT - attempt did not report back within 4s\n");
            }
            drainScrollLog(svc);
        }
        return ok;
    }

    // Move the accessibility service's per-attempt scroll lines
    // into the capture log.
    private void drainScrollLog(ScrollAccessibilityService svc) {
        if (svc == null || capLog == null) return;
        for (String line : svc.drainAttemptLog()) {
            capLog.append(line).append("\n");
        }
    }

    // A captured frame shows no MOVEMENT: either nothing changed,
    // or a minority of the screen changed in place (a list
    // re-sorting itself - Stitcher.rowMedianDifference stays near
    // zero because the majority of rows are identical at zero
    // shift, however large the mean difference gets). First time:
    // escalate the current node's scroll strategy (injected
    // gesture -> the node's own scroll action; some apps' lists
    // ignore strokes but scroll when the view itself is asked).
    // Second consecutive time: the node is done.
    private void noteNoMovement(double diff, double median) {
        ScrollAccessibilityService svc = CaptureSession.accessibilityService;
        boolean exhaust = ++loopUnchangedStreak >= 2;
        if (exhaust) {
            loopUnchangedStreak = 0;
            if (svc != null) svc.markCurrentExhausted();
        } else if (svc != null) {
            svc.escalateCurrentNode();
        }
        if (capLog != null) {
            capLog.append("  nomove: diff=").append(String.format("%.2f", diff))
                    .append(" median=").append(String.format("%.2f", median))
                    .append(exhaust ? " -> node exhausted\n" : " -> escalate strategy\n");
        }
    }

    // v1.39.41: kick the session's ONE virtual display so the
    // mirror pipeline emits fresh frames at capture start.
    // v1.39.40 tried releasing and recreating the display per
    // capture; the platform forbids it - one MediaProjection
    // instance may create exactly ONE VirtualDisplay, and the
    // second createVirtualDisplay threw SecurityException on
    // device ("Don't take multiple captures by invoking
    // MediaProjection#createVirtualDisplay multiple times on the
    // same instance"), leaving the session with no display at
    // all. The underlying problem stands: a later capture can
    // find the reader queue dry (the previous capture drained
    // it and a static screen repaints nothing), so the first
    // frame starves ("grab failed at frame 1: no-image x5",
    // capture 45). Resizing the existing display forces the
    // mirror to re-render into the reader - the platform-legal
    // way to provoke the frames a fresh display would have
    // emitted on creation.
    // The largest static-top sample that at least one OTHER
    // pair corroborates within 24px, or 0 when no two samples
    // agree. A real fixed header measures the same at every
    // scroll offset; a single pair's read can collapse when an
    // animated element inside the header (a rotating search
    // hot-word) changes between its two frames.
    private static int corroboratedTop(java.util.List<Integer> samples) {
        int best = 0;
        for (int i = 0; i < samples.size(); i++) {
            for (int j = 0; j < samples.size(); j++) {
                if (i != j && Math.abs(samples.get(i) - samples.get(j)) <= 24) {
                    best = Math.max(best, Math.max(samples.get(i), samples.get(j)));
                }
            }
        }
        return best;
    }

    private void kickDisplay() {
        if (virtualDisplay == null) return;
        try {
            virtualDisplay.resize(screenWidth, screenHeight + 1, screenDensity);
            virtualDisplay.resize(screenWidth, screenHeight, screenDensity);
            loopKickCount++;
            if (capLog != null) capLog.append("display kicked for this capture\n");
        } catch (Exception e) {
            if (capLog != null) capLog.append("display kick failed: ").append(String.valueOf(e)).append("\n");
        }
    }

    private Bitmap captureFrame() {
        if (imageReader == null) return null;
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return null;
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;
            Bitmap bmp = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buffer);
            if (rowPadding != 0) {
                Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight);
                bmp.recycle();
                return cropped;
            }
            return bmp;
        } catch (Exception e) {
            return null;
        } finally {
            if (image != null) image.close();
        }
    }

    private Bitmap cropBars(Bitmap frame) {
        int top = Math.min(statusBarHeight, frame.getHeight() / 4);
        if (top > 0) {
            Bitmap strip = copyStrip(frame, 0, top);
            if (strip != null) {
                if (pendingTopStrip != null) pendingTopStrip.recycle();
                pendingTopStrip = strip;
            }
        }
        int bottom = frame.getHeight() - Math.min(navBarHeight, frame.getHeight() / 4);
        if (top <= 0 && bottom >= frame.getHeight()) return frame;
        if (bottom <= top) return frame;
        Bitmap out = Bitmap.createBitmap(frame, 0, top, frame.getWidth(), bottom - top);
        frame.recycle();
        return out;
    }

    private void openResult(String path) {
        Intent intent = new Intent(this, ResultActivity.class);
        intent.putExtra(ResultActivity.EXTRA_PATH, path);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        // Also update the notification so the user can tap back to it.
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent main = new Intent(this, ResultActivity.class);
        main.putExtra(ResultActivity.EXTRA_PATH, path);
        PendingIntent pi = PendingIntent.getActivity(this, 1, main,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ScrollShot 完成")
                .setContentText("长图已生成，点击预览并保存")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_ID + 1, notif);
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    private void mainThread(Runnable r) {
        new Handler(getMainLooper()).post(r);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        removeOverlay();
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();
        if (captureThread != null) captureThread.quitSafely();
        CaptureSession.captureService = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
