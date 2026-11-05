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
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ScrollShot 滚动截屏")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(mainPi)
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

    private boolean startProjection(int resultCode, Intent data) {
        try {
            if (data == null) {
                CaptureSession.lastError = "录屏授权数据为空（未拿到系统返回的 Intent）";
                Toast.makeText(this, "录屏授权数据为空，请重试", Toast.LENGTH_LONG).show();
                return false;
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
        overlayStart.setOnClickListener(v -> startCaptureLoop());

        overlayStop = makeOverlayButton(R.drawable.ic_overlay_stop, 0xFFFF453A);
        overlayStop.setContentDescription("停止");
        overlayStop.setVisibility(View.GONE);
        overlayStop.setOnClickListener(v -> stopRequested = true);

        overlayQuit = makeOverlayButton(R.drawable.ic_overlay_close, 0xFF8E8E93);
        overlayQuit.setContentDescription("退出");
        overlayQuit.setOnClickListener(v -> {
            quitRequested = true;
            stopRequested = true;
            if (!loopRunning) cleanupAndStop();
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
            for (int y = 0; y < h; y += Math.max(1, h / 20)) {
                for (int x = 0; x < w; x += Math.max(1, w / 20)) {
                    total++;
                    if (bmp.getPixel(x, y) == first) same++;
                }
            }
            return total > 0 && (double) same / total > 0.98;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Bitmap captureFrameWithRetry() {
        for (int i = 0; i < 5; i++) {
            mainThread(() -> hideOverlay());
            sleep(200);
            Bitmap frame = captureFrame();
            mainThread(() -> showOverlayAgain());
            if (frame == null) {
                sleep(300);
                continue;
            }
            frame = cropBars(frame);
            if (isBlankFrame(frame)) {
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
            return frame;
        }
        return null;
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
                base.recycle();
                base = frame;
                if (diff >= 2.0) return true;
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
        return frame;
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
            return captureFrameWithRetry();
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
                    if (n.startsWith("frame") || n.equals("detection.txt")) f.delete();
                }
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
        loopZeroStreak = 0;
        loopUnchangedStreak = 0;
        lastRealizedStep = 0;
        lastIntendedStep = 0;
        loopChromeBottom = 0;
        loopFixedRects.clear();
        loopPendings.clear();
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
        int savedStickyBottom = 0;
        int maxTopSeen = 0;
        StringBuilder debugLog = new StringBuilder();
        capLog = debugLog;
        debugLog.append("START svcConnected=")
                .append(CaptureSession.accessibilityService != null)
                .append(" factor=").append(CaptureSession.scrollStepFactor)
                .append("\n");
        diagEnabled = diagnosticsEnabled(this);
        prepareDebugOutput();
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
            while (!stopRequested && loopFrames < MAX_FRAMES) {
                Bitmap raw = (loopFrames == 0) ? captureFrameWithRetry() : captureStableFrame();
                if (raw == null) {
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
                        if (diff < 2.0) {
                            raw.recycle();
                            if (++loopUnchangedStreak >= 2) {
                                loopUnchangedStreak = 0;
                                if (CaptureSession.accessibilityService != null) {
                                    CaptureSession.accessibilityService.markCurrentExhausted();
                                }
                            }
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
                            bottomCut = Math.max(useSBot, raw.getHeight() - useTop);
                        } else {
                            stickyTop = savedStickyTop;
                            stickyBottom = savedStickyBottom;
                            bottomCut = stickyBottom;
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
                        }
                        deferredRaws.clear();
                        if (stopRequested || abortCapture) {
                            // Memory guard fired while stitching the
                            // held frames, or the zero-scroll guard
                            // aborted; the current raw is unstitched.
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
                        if (diff < 2.0) {
                            if (frame != raw) raw.recycle();
                            if (frame != loopPreviousFrame) frame.recycle();
                            if (++loopUnchangedStreak >= 2) {
                                loopUnchangedStreak = 0;
                                if (CaptureSession.accessibilityService != null) {
                                    CaptureSession.accessibilityService.markCurrentExhausted();
                                }
                            }
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
                if (stopRequested || abortCapture) break;
                boolean scrolled = scrollOnce();
                if (!scrolled) {
                    if (!scrollEndOrManualWait()) break;
                }
                sleep(250);
            }
        } catch (Exception e) {
            CaptureSession.lastError = e.getMessage();
        } finally {
            loopRunning = false;
            if (debugLog.length() > 0) {
                debugLog.append("END: stickyTop=").append(stickyTop)
                        .append(" stickyBottom=").append(stickyBottom)
                        .append(" bottomCut=").append(bottomCut)
                        .append(" frames=").append(loopFrames).append("\n");
                dumpDebugLog(debugLog.toString());
            }
            if (!abortCapture && !deferredRaws.isEmpty() && loopStitched != null && loopPreviousFrame != null) {
                // The capture ended (Stop, error, frame cap) before
                // bar/floating detection settled: stitch the held
                // frames with the first attempt's sticky values
                // instead of dropping them. v1.20 recycled them here,
                // so stopping during the first few scrolls saved an
                // image containing only the first frame even though
                // the page had already scrolled several times.
                stickyDetected = true;
                stickyTop = savedStickyTop;
                stickyBottom = savedStickyBottom;
                bottomCut = stickyBottom;
                loopChromeBottom = Math.max(0, maxTopSeen - savedStickyTop);
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
                }
                deferredRaws.clear();
            } else {
                for (Bitmap held : deferredRaws) held.recycle();
                deferredRaws.clear();
            }
            if (abortCapture) {
                String reason = CaptureSession.lastError != null ? CaptureSession.lastError
                        : "已停止：页面没有滚动";
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
            // First-join confidence guard. A 45px effective overlap
            // cannot certify a 1405px frame: the matcher accepted
            // overlap 72, implied a 1333px jump, and the saved image
            // lost the "青萍" row. The conservative first step makes
            // this rare; if it still happens, fail visibly instead
            // of saving a silently broken long image.
            if (loopFrames <= 4) {
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
            if (oi.matched && loopFrames > 4 && oi.overlap > frame.getHeight() * 0.67) {
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
        // Deferred fill for mid-screen fixed elements (v1.30):
        // document rows appended while hidden behind a confirmed
        // fixed element are restored from THIS frame, where the
        // same document rows sit clear of it; rows this frame
        // appends from behind the element become new pendings.
        // Single matched joins only - a split or unmatched join
        // has no trustworthy row mapping, and its occluded rows
        // simply keep the element (the pre-v1.30 behaviour).
        if (!loopFixedRects.isEmpty() && "single".equals(joinKind) && joinMatched) {
            int frameTop = old.getHeight() - stitchOverlap;
            java.util.Iterator<int[]> itp = loopPendings.iterator();
            while (itp.hasNext()) {
                int[] pd = itp.next();
                int[] rect = loopFixedRects.get(pd[1]);
                int srcRow = pd[0] - frameTop;
                if (srcRow < 0) {
                    itp.remove();
                    continue;
                }
                if (srcRow >= rect[4] && srcRow < rect[5]) {
                    if (++pd[2] > 3) itp.remove();
                    continue;
                }
                if (srcRow < frame.getHeight()) {
                    Stitcher.fillRowSegment(loopStitched, pd[0], frame, srcRow, rect[0], rect[2]);
                }
                itp.remove();
            }
            for (int ri = 0; ri < loopFixedRects.size(); ri++) {
                int[] rect = loopFixedRects.get(ri);
                int r0 = Math.max(stitchOverlap, rect[1]);
                int r1 = Math.min(rect[3], frame.getHeight());
                for (int srow = r0; srow < r1; srow++) {
                    loopPendings.add(new int[]{frameTop + srow, ri, 0});
                }
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

    private boolean scrollOnce() {
        ScrollAccessibilityService svc = CaptureSession.accessibilityService;
        if (svc == null) return false;
        // Let the injected gesture pass through a dragged overlay. This
        // only changes the window's TOUCHABLE flag for ~1 s; scroll
        // distance, timing and stitching are not touched.
        setOverlayTouchable(false);
        mainThreadDelayed(() -> setOverlayTouchable(true), 1000);
        return svc.scrollNextBlocking(4000);
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
