package com.scrollshot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scrolls the foreground app for ScrollShot.
 *
 * Multi-level strategy:
 *  - Walk the accessibility tree and collect every visible scrollable node.
 *  - Sort smallest-area first (= deepest / innermost container first).
 *  - Try to scroll the innermost node with a slow swipe (no fling, so the
 *    distance is predictable and stitching stays smooth).
 *  - If a node no longer changes the screen, mark it exhausted and move to
 *    the next larger (parent) node. When every node is exhausted, capture ends.
 *  - Each node is tried vertically first, then horizontally, before giving up.
 */
public class ScrollAccessibilityService extends AccessibilityService {

    public interface ScrollCallback {
        void onResult(boolean scrolled);
    }

    private static boolean isPagerClass(String cn) {
        return "androidx.viewpager.widget.ViewPager".equals(cn)
                || "android.support.v4.view.ViewPager".equals(cn);
    }


    private static class NodeCandidate {
        Rect bounds;
        String key;
        String className;
        AccessibilityNodeInfo info;
        int area;
        int depth;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> exhausted = new HashSet<>();
    private volatile NodeCandidate currentNode = null;
    private volatile int currentAttempt = 0; // 0 = vertical, 1 = horizontal
    // How many times the CURRENT direction has been retried after a
    // cancellation. A single cancelled gesture is often transient (the
    // view was mid-layout, a layer intercepted the stroke, the system
    // dropped it), so we retry the same direction before concluding the
    // node cannot scroll that way. Only after both directions have been
    // attempted (with one retry each) is the node marked exhausted.
    private int directionRetries = 0;
    // True when the last scroll attempt found scrollable containers but
    // EVERY one of them was exhausted - i.e. the page (all levels of
    // containers) genuinely cannot scroll any further. CaptureService
    // uses this to finish normally at the end of a page instead of
    // dropping into a "scroll failed, waiting for manual scroll" state.
    private volatile boolean lastTryAllExhausted = false;
    // How the current node is being scrolled: 0 = slow injected
    // gesture, 1 = fast fling gesture, 2 = the node's own
    // ACTION_SCROLL_FORWARD. Some apps' lists (WeChat's
    // conversation list is the known case) accept an injected
    // stroke, report it completed, and do not move at all.
    // CaptureService measures real movement from the frames and
    // escalates via escalateCurrentNode() when a "successful"
    // scroll did not move.
    private volatile int currentStrategy = 0;
    // One-line description of the most recent scroll attempt (node
    // class, bounds, strategy, outcome), for the diagnostics log.
    public volatile String lastAttemptDebug = "";
    // Every scroll attempt leaves its lines here (chosen node,
    // strategy, outcome - including the early exits: no root, no
    // candidates, all exhausted). CaptureService drains this into
    // the diagnostics log after each attempt; lines from an
    // attempt that reports back late are drained with the next
    // one or at capture end, so no attempt stays invisible.
    public final java.util.List<String> attemptLog =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    // Set when the latest scrollNextBlocking timed out waiting
    // for its attempt to report back.
    public volatile boolean lastScrollTimedOut = false;
    // Blind scrolling: when the target app exposes NO
    // accessibility-scrollable node at all (WeChat's conversation
    // list is the proven case - its tree contains no scrollable
    // node, so node-based scrolling never even dispatches),
    // fall back to swiping over the window itself. Gestures are
    // pure coordinates; they reach whatever view sits under the
    // stroke. blindStrategy: 0 = slow drag, 1 = fast fling;
    // spent strategies set blindExhausted.
    private volatile boolean blindActive = false;
    private volatile int blindStrategy = 0;
    private volatile boolean blindExhausted = false;
    private Rect fallbackBounds = null;
    private String lastLoggedNodeKey = "";

    public java.util.List<String> drainAttemptLog() {
        synchronized (attemptLog) {
            java.util.List<String> out = new java.util.ArrayList<>(attemptLog);
            attemptLog.clear();
            return out;
        }
    }

    public boolean isScrollExhausted() {
        return lastTryAllExhausted;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        CaptureSession.accessibilityService = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        setServiceInfo(info);
    }

    // v1.39.35: the volume pill as a WINDOW. On photo backdrops
    // the pill turns into frosted glass and every pixel test goes
    // blind (capture 39: pills sat in three feed frames, nothing
    // fired), but the pill is a system window no matter what is
    // beneath it. pollWindows() scans the window stack for a
    // compact right-side system window and reports it; the latest
    // non-application window signature is kept for the capture
    // log, so a miss is diagnosable from the log alone.
    public static volatile String windowSignature = "";

    // v1.39.45: a wide non-system overlay window (a heads-up
    // notification banner) seen by the latest pollWindows().
    // CaptureService uses it to attribute a pixel panel
    // suspect to the banner instead of probing (capture 51:
    // five correct-but-slow probe rejections, then a false
    // stop when a probe grab failed).
    public static volatile boolean bannerLikePresent = false;

    /** True when the volume pill window is present NOW. */
    public boolean pollWindows() {
        boolean pill = false;
        boolean banner = false;
        try {
            StringBuilder sb = new StringBuilder();
            android.graphics.Rect b = new android.graphics.Rect();
            for (android.view.accessibility.AccessibilityWindowInfo win : getWindows()) {
                if (win.getType()
                        == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                win.getBoundsInScreen(b);
                String pkg = "";
                try {
                    android.view.accessibility.AccessibilityNodeInfo r = win.getRoot();
                    if (r != null && r.getPackageName() != null) {
                        pkg = r.getPackageName().toString();
                    }
                } catch (Exception ignored) { }
                sb.append('[').append(pkg).append(" t").append(win.getType())
                        .append(' ').append(b.left).append(',').append(b.top)
                        .append('-').append(b.right).append(',').append(b.bottom)
                        .append(']');
                // Pill geometry in device px: compact, hugging
                // the right edge, set down from the top - neither
                // the status bar (thin, full width) nor the shade
                // (full screen). Bounds are a property of the
                // system overlay, not of any page.
                // v1.39.45: geometry alone is NOT identity.
                // Capture 61: a WeChat voice-call floating
                // window (com.tencent.mm, 317x317, parked at the
                // right edge) matched every geometric bound and
                // stopped a capture at frame 1 as a fake pill.
                // The floating window is draggable, so no
                // geometry can exclude it; its PACKAGE can. The
                // volume pill is a SystemUI window - every real
                // sighting in the logs is com.android.systemui -
                // so the pill test now requires that package.
                boolean systemui = "com.android.systemui".equals(pkg);
                if (systemui
                        && b.width() >= 80 && b.width() <= 460
                        && b.height() >= 260 && b.left >= 700
                        && b.top >= 120) {
                    pill = true;
                }
                // A banner-class overlay: wide, content-height,
                // below the status bar, not SystemUI furniture
                // (status bar is top=0, nav bar is short, the
                // left-edge panel is narrow). The WeChat call
                // float (317 wide) does not qualify.
                if (!systemui && b.width() >= 600 && b.height() >= 120
                        && b.top >= 100) {
                    banner = true;
                }
            }
            windowSignature = sb.toString();
            bannerLikePresent = banner;
        } catch (Exception ignored) { }
        return pill;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Window-stack changes keep the pill sighting warm
        // between CaptureService's own polls.
        if (event != null
                && (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            pollWindows();
        }
    }

    @Override
    public void onInterrupt() {
        CaptureSession.capturing = false;
    }

    @Override
    public void onDestroy() {
        if (CaptureSession.accessibilityService == this) {
            CaptureSession.accessibilityService = null;
        }
        super.onDestroy();
    }

    /** Called by CaptureService when the latest frame did not change: the current node is done. */
    public void markCurrentExhausted() {
        if (currentNode != null) {
            exhausted.add(currentNode.key);
        } else if (blindActive) {
            blindExhausted = true;
        }
        currentNode = null;
        currentAttempt = 0;
        directionRetries = 0;
        currentStrategy = 0;
    }

    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        // Volume-down ends a running capture: the overlay is
        // hidden for the whole capture (v1.39.12), so a physical
        // key is the stop control. Intercept ONLY while a capture
        // is running - volume keys behave normally otherwise.
        // Consuming the event also keeps the volume (and its
        // on-screen panel) untouched. Requires
        // canRequestFilterKeyEvents in the service config.
        if (CaptureSession.capturing
                && event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                CaptureSession.volumeStopRequested = true;
            }
            return true;
        }
        return super.onKeyEvent(event);
    }

    // Collapse the notification shade (used when a capture is
    // started from the notification's Start action: the shade
    // is still open at that moment). API 31+; returns false
    // where unsupported - the caller then relies on the
    // focus wait instead.
    public boolean dismissNotificationShade() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                return performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    // True when a normal application window (not the shade or
    // another system window) currently holds focus.
    public boolean isAppWindowFocused() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo win : getWindows()) {
                if (win.isFocused()
                        && win.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public void resetScrollState() {
        exhausted.clear();
        currentNode = null;
        currentAttempt = 0;
        directionRetries = 0;
        currentStrategy = 0;
        lastTryAllExhausted = false;
        blindActive = false;
        blindStrategy = 0;
        blindExhausted = false;
        fallbackBounds = null;
        lastLoggedNodeKey = "";
    }

    /**
     * CaptureService measured no movement after a scroll that
     * reported success: escalate the current node from injected
     * gestures to its own scroll action. Escalating a node already
     * on the action strategy (or on the horizontal attempt) gives
     * up on the node entirely.
     */
    public void escalateCurrentNode() {
        if (currentNode == null) {
            // Blind mode: measured no-movement advances the blind
            // strategy (slow drag -> fling -> spent).
            if (blindActive && !blindExhausted) {
                if (blindStrategy == 0) blindStrategy = 1;
                else blindExhausted = true;
            }
            return;
        }
        if (currentAttempt == 0 && currentStrategy < 2) {
            currentStrategy++;
        } else {
            markCurrentExhausted();
        }
    }

    /**
     * Try one scroll step. Async: the callback fires on the main thread
     * when the gesture completes, is cancelled, or no node is left.
     */
    public void scrollNext(ScrollCallback callback) {
        lastScrollTimedOut = false;
        mainHandler.post(() -> tryScroll(callback));
    }

    private void tryScroll(ScrollCallback callback) {
        List<NodeCandidate> candidates = new ArrayList<>();
        String via = "activeWindow";
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean rootWasNull = root == null;
        if (root != null) {
            collectScrollable(root, 0, candidates);
        }
        if (candidates.isEmpty()) {
            // The active window can be our own overlay (it takes
            // window focus when the user taps Start on it), which
            // leaves the target app's tree unreachable through
            // getRootInActiveWindow. Fall back to scanning the
            // application windows for the target's scrollable
            // nodes before concluding there are none.
            via = collectFromWindows(candidates);
        }
        if (candidates.isEmpty()) {
            // No scrollable node anywhere: try a blind swipe over
            // the window before giving up. The active root's own
            // bounds are the foreground window; the windows-scan
            // bounds are the fallback.
            Rect bounds = null;
            if (root != null) {
                bounds = new Rect();
                root.getBoundsInScreen(bounds);
            }
            if ((bounds == null || bounds.width() < 200 || bounds.height() < 200)
                    && fallbackBounds != null) {
                bounds = new Rect(fallbackBounds);
            }
            if (bounds == null || bounds.width() < 200 || bounds.height() < 200) {
                // Some apps (WeChat is the proven case) report
                // 0x0 bounds for their accessibility roots, which
                // would veto the blind swipe entirely: fall back
                // to the real display size - swiping over the
                // screen is what blind scrolling wants anyway.
                android.graphics.Point size = new android.graphics.Point();
                try {
                    android.view.WindowManager wm =
                            (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
                    if (wm != null) wm.getDefaultDisplay().getRealSize(size);
                } catch (Exception ignored) {
                }
                if (size.x >= 200 && size.y >= 200) {
                    bounds = new Rect(0, 0, size.x, size.y);
                }
            }
            if (!blindExhausted && bounds != null
                    && bounds.width() >= 200 && bounds.height() >= 200) {
                blindActive = true;
                lastTryAllExhausted = false;
                if (root != null) root.recycle();
                doBlindGesture(bounds, callback);
                return;
            }
            attemptLog.add("scroll attempt: no scrollable candidates (root="
                    + (rootWasNull ? "null" : "present") + ", via=" + via + ", blind="
                    + (blindExhausted ? "exhausted"
                            : bounds == null ? "no-bounds"
                            : bounds.width() + "x" + bounds.height()) + ")");
            lastTryAllExhausted = blindExhausted;
            if (root != null) root.recycle();
            callback.onResult(false);
            return;
        }
        // Tallest first (v1.39.26), then smallest area, then
        // deepest. This used to be smallest-area-first
        // (innermost container first), and capture 29 showed
        // the failure mode: the news feed exposes an inner
        // RecyclerView shelf only ~910 px tall whose on-screen
        // bounds MOVE as the outer page scrolls (nested
        // scrolling - a gesture on it scrolls both lists a
        // little). It won the smallest-area ordering, so the
        // capture spent its first frames taking ~130-300 px
        // steps through a shifting sub-window: detection
        // measured a wrong bottom cut (438 instead of ~752),
        // the floating refresh button's top sliver fell inside
        // the content region and was sliced into the result
        // repeatedly, and three seam placements moved
        // 391-817 px. The page's main scroller is the tall
        // candidate; take it first. Genuine multi-level pages
        // (an inner list inside a NON-scrolling outer page)
        // still reach the inner container: a gesture on the
        // outer candidate produces no movement, and the
        // existing no-movement escalation walks down the
        // candidate list to the inner one.
        Collections.sort(candidates, (a, b) -> {
            int ha = a.bounds.height(), hb = b.bounds.height();
            if (ha != hb) return Integer.compare(hb, ha);
            // v1.39.27: a support ViewPager is a HORIZONTAL-only
            // container by design; for a vertical scroll task it
            // is the least plausible scroller, so demote it
            // behind vertical containers of the same height
            // (capture 30: it tied the full-screen RecyclerView
            // at 2800 and won on depth, so every gesture used
            // pager bounds - it happened to work because the
            // stroke lands on the list, but the step family
            // inflated to ~1010 realized vs ~840). It stays in
            // the list: if it is the only candidate, the walk
            // still reaches it.
            boolean pa = isPagerClass(a.className), pb = isPagerClass(b.className);
            if (pa != pb) return pa ? 1 : -1;
            if (a.area != b.area) return Integer.compare(a.area, b.area);
            return Integer.compare(b.depth, a.depth);
        });
        // Prefer continuing with the current node if it is still present.
        NodeCandidate chosen = null;
        if (currentNode != null) {
            for (NodeCandidate c : candidates) {
                if (c.key.equals(currentNode.key)) {
                    chosen = c;
                    directionRetries = 0;
                    break;
                }
            }
            if (chosen == null) {
                // Current node disappeared (page changed) -> forget it.
                currentNode = null;
                currentAttempt = 0;
                directionRetries = 0;
                currentStrategy = 0;
            }
        }
        if (chosen == null) {
            for (NodeCandidate c : candidates) {
                if (!exhausted.contains(c.key)) {
                    chosen = c;
                    currentAttempt = 0;
                    directionRetries = 0;
                    currentStrategy = 0;
                    break;
                }
            }
        }
        if (chosen == null) {
            // Every scrollable container is used up: the page
            // (all levels) genuinely cannot scroll further.
            attemptLog.add("scroll attempt: all " + candidates.size()
                    + " candidates exhausted (via=" + via + ")");
            lastTryAllExhausted = true;
            if (root != null) root.recycle();
            callback.onResult(false);
            return;
        }
        lastTryAllExhausted = false;
        currentNode = chosen;
        if (!chosen.key.equals(lastLoggedNodeKey)) {
            lastLoggedNodeKey = chosen.key;
            StringBuilder sb = new StringBuilder("candidates via=").append(via).append(":");
            for (NodeCandidate c : candidates) {
                sb.append(" [").append(c.className).append(" ")
                        .append(c.bounds.toShortString()).append("]");
            }
            attemptLog.add(sb.toString());
        }
        if (currentStrategy == 2 && currentAttempt == 0) {
            // Strategy 2: ask the view to scroll itself. The
            // action runs on the fresh node before the root is
            // recycled; its step size is the view's own, so the
            // distance is unknown (0 -> CaptureService falls back
            // to a neutral expected overlap and the stitcher
            // measures the rest).
            boolean ok = false;
            try {
                ok = chosen.info != null && chosen.info.performAction(
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            } catch (Exception ignored) {
            }
            CaptureSession.lastScrollDistancePx = 0;
            CaptureSession.lastDirection = 0;
            lastAttemptDebug = "node=" + chosen.className + " " + chosen.bounds.toShortString()
                    + " strategy=action result=" + ok;
            attemptLog.add(lastAttemptDebug);
            if (root != null) root.recycle();
            if (ok) {
                callback.onResult(true);
            } else {
                // The view refused its own scroll action: this
                // node is done; move on to the next candidate.
                exhausted.add(chosen.key);
                currentNode = null;
                currentAttempt = 0;
                currentStrategy = 0;
                directionRetries = 0;
                tryScroll(callback);
            }
            return;
        }
        if (root != null) root.recycle();
        doGesture(chosen, callback);
    }

    // Fallback candidate source when the active window yields
    // nothing: the largest application window that is not our
    // own. Returns a short source label for the log.
    private String collectFromWindows(List<NodeCandidate> out) {
        try {
            List<android.view.accessibility.AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return "windows=null";
            AccessibilityNodeInfo bestRoot = null;
            long bestArea = -1;
            for (android.view.accessibility.AccessibilityWindowInfo w : windows) {
                if (w.getType()
                        != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence pkg = r.getPackageName();
                if (pkg != null && "com.scrollshot".equals(pkg.toString())) continue;
                Rect wb = new Rect();
                w.getBoundsInScreen(wb);
                long area = (long) wb.width() * wb.height();
                if (area > bestArea) {
                    bestArea = area;
                    bestRoot = r;
                }
            }
            if (bestRoot != null) {
                fallbackBounds = new Rect();
                bestRoot.getBoundsInScreen(fallbackBounds);
                collectScrollable(bestRoot, 0, out);
                CharSequence pkg = bestRoot.getPackageName();
                return "windows pkg=" + (pkg == null ? "?" : pkg.toString());
            }
        } catch (Exception ignored) {
        }
        return "windows=none";
    }

    // Node-free swipe over the whole window (see the field
    // comment at blindActive). Vertical only - the fallback
    // exists for lists that expose no scrollable node.
    private void doBlindGesture(Rect bounds, ScrollCallback callback) {
        boolean fling = blindStrategy == 1;
        double factor = CaptureSession.scrollStepFactor;
        if (factor < 0.15) factor = 0.15;
        if (factor > 0.50) factor = 0.50;
        if (fling) factor = Math.max(factor, 0.40);
        int cx = bounds.centerX();
        int startY = bounds.top + (int) (bounds.height() * (0.5 + factor / 2.0));
        int endY = bounds.top + (int) (bounds.height() * (0.5 - factor / 2.0));
        Path path = new Path();
        path.moveTo(cx, startY);
        path.lineTo(cx, endY);
        CaptureSession.lastScrollDistancePx = fling
                ? (int) (Math.abs(endY - startY) * 1.8)
                : Math.abs(endY - startY);
        CaptureSession.lastDirection = 0;
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(
                        path, 0, fling ? 260 : 900))
                .build();
        String label = "node=(blind) " + bounds.toShortString()
                + " strategy=" + (fling ? "fling" : "gesture");
        attemptLog.add(label + " dispatching");
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                attemptLog.add("  blind gesture completed");
                callback.onResult(true);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                attemptLog.add("  blind gesture cancelled");
                if (!fling) {
                    blindStrategy = 1;
                    doBlindGesture(bounds, callback);
                } else {
                    blindExhausted = true;
                    callback.onResult(false);
                }
            }
        }, null);
        if (!dispatched) {
            attemptLog.add(label + " dispatch REFUSED");
            if (!fling) {
                blindStrategy = 1;
                doBlindGesture(bounds, callback);
            } else {
                blindExhausted = true;
                callback.onResult(false);
            }
        }
    }

    private void doGesture(NodeCandidate node, ScrollCallback callback) {
        Rect r = node.bounds;
        Path path = new Path();
        int cx = r.centerX();
        int cy = r.centerY();
        // Step size is adaptive (see CaptureSession.scrollStepFactor):
        // centred swipe of factor * node size in 900ms. The old fixed
        // 55% / 650ms was fast enough to fling in many apps, so the
        // real scroll distance regularly overshot the gesture distance
        // and the stitch overlap estimate was wrong.
        double factor = CaptureSession.scrollStepFactor;
        if (factor < 0.15) factor = 0.15;
        if (factor > 0.50) factor = 0.50;
        // Strategy 1 (vertical only): a fast fling instead of the
        // slow drag. Some lists treat a slow centre-screen drag
        // as a pending long-press and never scroll, but fling
        // normally. The realised step overshoots the stroke, so
        // the nominal distance handed to the stitcher is inflated
        // to keep its plausibility window honest.
        boolean fling = currentStrategy == 1 && currentAttempt == 0;
        if (fling) factor = Math.max(factor, 0.40);
        double from = 0.5 + factor / 2.0;
        double to = 0.5 - factor / 2.0;
        if (currentAttempt == 0) {
            // Vertical: swipe up.
            int startY = r.top + (int) (r.height() * from);
            int endY = r.top + (int) (r.height() * to);
            path.moveTo(cx, startY);
            path.lineTo(cx, endY);
            CaptureSession.lastScrollDistancePx = fling
                    ? (int) (Math.abs(endY - startY) * 1.8)
                    : Math.abs(endY - startY);
            CaptureSession.lastDirection = 0;
        } else {
            // Horizontal: slow swipe left.
            int startX = r.left + (int) (r.width() * from);
            int endX = r.left + (int) (r.width() * to);
            path.moveTo(startX, cy);
            path.lineTo(endX, cy);
            CaptureSession.lastScrollDistancePx = Math.abs(endX - startX);
            CaptureSession.lastDirection = 1;
        }
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, fling ? 260 : 900);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        lastAttemptDebug = "node=" + node.className + " " + r.toShortString()
                + " strategy=" + (fling ? "fling" : "gesture")
                + " attempt=" + (currentAttempt == 0 ? "v" : "h");
        attemptLog.add(lastAttemptDebug + " dispatching");
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                lastAttemptDebug += " completed";
                attemptLog.add("  gesture completed");
                directionRetries = 0;
                callback.onResult(true);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                lastAttemptDebug += " cancelled";
                attemptLog.add("  gesture cancelled");
                handleGestureFailure(node, callback);
            }
        }, null);
        lastAttemptDebug += " dispatched=" + dispatched;
        if (!dispatched) {
            handleGestureFailure(node, callback);
        }
    }

    /**
     * A gesture was rejected or failed to dispatch. Retry the same
     * direction once (transient failures are common), then try the
     * other direction, and only when both directions are exhausted
     * mark the node done and move to the next candidate.
     */
    private void handleGestureFailure(NodeCandidate node, ScrollCallback callback) {
        if (directionRetries < 1) {
            directionRetries++;
            doGesture(node, callback);
            return;
        }
        directionRetries = 0;
        if (currentAttempt == 0) {
            currentAttempt = 1;
            doGesture(node, callback);
        } else {
            exhausted.add(node.key);
            currentNode = null;
            currentAttempt = 0;
            tryScroll(callback);
        }
    }

    /** Synchronous wrapper used only from CaptureService's background thread. */
    public boolean scrollNextBlocking(long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean result = new AtomicBoolean(false);
        scrollNext(scrolled -> {
            result.set(scrolled);
            latch.countDown();
        });
        boolean done = false;
        try {
            done = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lastScrollTimedOut = !done;
        return result.get();
    }

    private void collectScrollable(AccessibilityNodeInfo node, int depth, List<NodeCandidate> out) {
        if (node == null) return;
        try {
            if (node.isVisibleToUser() && node.isScrollable()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                // Height gate 550 (v1.39.23), not 200: a
                // VERTICAL scroll candidate must be tall enough
                // that the smallest scroll step (0.22 x height)
                // is a real swipe (>= ~120 px), never a tap.
                // Capture 24: the browser exposed its bottom
                // dock bar as a scrollable RecyclerView only
                // 234 px tall ([0,2566][1272,2800]); the
                // smallest-area-first ordering picked it, the
                // ~51 px "swipe" inside the dock acted as a tap
                // on its AI button, and the capture navigated
                // away from the page instead of scrolling it.
                // Genuine nested containers worth scrolling are
                // far taller than this; anything shorter falls
                // back to the next candidate or the blind swipe.
                if (bounds.width() >= 200 && bounds.height() >= 550) {
                    String cls = node.getClassName() != null ? node.getClassName().toString() : "";
                    // Skip our own overlay / system UI containers.
                    if (!cls.contains("ScrollShot")) {
                        NodeCandidate c = new NodeCandidate();
                        c.bounds = bounds;
                        c.className = cls;
                        c.info = node;
                        c.key = cls + "|" + bounds.toShortString();
                        c.area = bounds.width() * bounds.height();
                        c.depth = depth;
                        out.add(c);
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    collectScrollable(child, depth + 1, out);
                    // Note: we do not recycle children here; the tree is short-lived
                    // and the root recycle above is best-effort. This avoids
                    // use-after-recycle crashes on some OEM ROMs.
                }
            }
        } catch (Exception ignored) {
        }
    }
}
