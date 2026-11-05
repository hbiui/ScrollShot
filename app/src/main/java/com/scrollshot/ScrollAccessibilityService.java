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

    private static class NodeCandidate {
        Rect bounds;
        String key;
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op: we only read the tree on demand when CaptureService asks us to scroll.
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
        }
        currentNode = null;
        currentAttempt = 0;
        directionRetries = 0;
    }

    public void resetScrollState() {
        exhausted.clear();
        currentNode = null;
        currentAttempt = 0;
        directionRetries = 0;
        lastTryAllExhausted = false;
    }

    /**
     * Try one scroll step. Async: the callback fires on the main thread
     * when the gesture completes, is cancelled, or no node is left.
     */
    public void scrollNext(ScrollCallback callback) {
        mainHandler.post(() -> tryScroll(callback));
    }

    private void tryScroll(ScrollCallback callback) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            callback.onResult(false);
            return;
        }
        List<NodeCandidate> candidates = new ArrayList<>();
        collectScrollable(root, 0, candidates);
        root.recycle();
        // Smallest first (innermost), then deepest.
        Collections.sort(candidates, (a, b) -> {
            if (a.area != b.area) return Integer.compare(a.area, b.area);
            return Integer.compare(b.depth, a.depth);
        });
        // Prefer continuing with the current node if it is still present.
        if (currentNode != null) {
            for (NodeCandidate c : candidates) {
                if (c.key.equals(currentNode.key)) {
                    lastTryAllExhausted = false;
                    directionRetries = 0;
                    doGesture(c, callback);
                    return;
                }
            }
            // Current node disappeared (page changed) -> forget it.
            currentNode = null;
            currentAttempt = 0;
            directionRetries = 0;
        }
        for (NodeCandidate c : candidates) {
            if (!exhausted.contains(c.key)) {
                currentNode = c;
                currentAttempt = 0;
                directionRetries = 0;
                lastTryAllExhausted = false;
                doGesture(c, callback);
                return;
            }
        }
        // Nothing left to try: exhausted only if there WERE scrollable
        // containers and all of them are used up. An empty candidate
        // list means this app exposes no scrollable nodes at all (the
        // manual-mode case), which is not "end of page".
        lastTryAllExhausted = !candidates.isEmpty();
        callback.onResult(false);
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
        double from = 0.5 + factor / 2.0;
        double to = 0.5 - factor / 2.0;
        if (currentAttempt == 0) {
            // Vertical: slow swipe up.
            int startY = r.top + (int) (r.height() * from);
            int endY = r.top + (int) (r.height() * to);
            path.moveTo(cx, startY);
            path.lineTo(cx, endY);
            CaptureSession.lastScrollDistancePx = Math.abs(endY - startY);
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
                new GestureDescription.StrokeDescription(path, 0, 900);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                directionRetries = 0;
                callback.onResult(true);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                handleGestureFailure(node, callback);
            }
        }, null);
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
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private void collectScrollable(AccessibilityNodeInfo node, int depth, List<NodeCandidate> out) {
        if (node == null) return;
        try {
            if (node.isVisibleToUser() && node.isScrollable()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() >= 200 && bounds.height() >= 200) {
                    String cls = node.getClassName() != null ? node.getClassName().toString() : "";
                    // Skip our own overlay / system UI containers.
                    if (!cls.contains("ScrollShot")) {
                        NodeCandidate c = new NodeCandidate();
                        c.bounds = bounds;
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
