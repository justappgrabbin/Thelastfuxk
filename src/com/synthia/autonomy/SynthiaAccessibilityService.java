package com.synthia.autonomy;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Local Android eyes + fingers.
 *
 * This translates the useful accessibility-first/ref model from Mobile MCP
 * directly into the Android residence. It does not require Node, ADB, a cloud
 * backend or an MCP server to act on the same device.
 */
public final class SynthiaAccessibilityService extends AccessibilityService {
    private static volatile SynthiaAccessibilityService instance;
    private final Map<String, AccessibilityNodeInfo> refs = new LinkedHashMap<>();
    private static final int MAX_ELEMENTS = 240;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 80;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Event capture is intentionally passive. Role-level hand decisions live
        // in the Synthia runtime; this service supplies witnessed device state.
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        clearRefs();
        super.onDestroy();
    }

    public static boolean isConnected() { return instance != null; }

    public static String statusJson() {
        return "{\"ok\":true,\"complete\":true,\"connected\":" + (instance != null) + "}";
    }

    public static String elementsJson() {
        SynthiaAccessibilityService service = instance;
        if (service == null) return json(false, "accessibility-not-enabled");
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return json(false, "no-active-accessibility-root");
        try { return service.buildElements(root); }
        finally { root.recycle(); }
    }

    private synchronized String buildElements(AccessibilityNodeInfo root) {
        clearRefs();
        StringBuilder out = new StringBuilder();
        out.append("{\"ok\":true,\"complete\":true,\"elements\":[");
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        int index = 0;
        boolean first = true;

        while (!queue.isEmpty() && index < MAX_ELEMENTS) {
            AccessibilityNodeInfo node = queue.removeFirst();
            index += 1;
            String ref = "@e" + index;
            refs.put(ref, AccessibilityNodeInfo.obtain(node));

            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!first) out.append(',');
            first = false;
            out.append('{')
                    .append("\"ref\":").append(q(ref)).append(',')
                    .append("\"text\":").append(q(node.isPassword() ? "[password hidden]" : cs(node.getText()))).append(',')
                    .append("\"description\":").append(q(node.isPassword() ? "" : cs(node.getContentDescription()))).append(',')
                    .append("\"class\":").append(q(cs(node.getClassName()))).append(',')
                    .append("\"package\":").append(q(cs(node.getPackageName()))).append(',')
                    .append("\"viewId\":").append(q(node.getViewIdResourceName())).append(',')
                    .append("\"clickable\":").append(node.isClickable()).append(',')
                    .append("\"editable\":").append(node.isEditable()).append(',')
                    .append("\"focused\":").append(node.isFocused()).append(',')
                    .append("\"enabled\":").append(node.isEnabled()).append(',')
                    .append("\"bounds\":{")
                    .append("\"left\":").append(bounds.left).append(',')
                    .append("\"top\":").append(bounds.top).append(',')
                    .append("\"right\":").append(bounds.right).append(',')
                    .append("\"bottom\":").append(bounds.bottom)
                    .append("}}");

            for (int child = 0; child < node.getChildCount(); child++) {
                AccessibilityNodeInfo childNode = node.getChild(child);
                if (childNode != null) queue.addLast(childNode);
            }
            node.recycle();
        }

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            node.recycle();
        }
        out.append("],\"count\":").append(index).append('}');
        return out.toString();
    }

    public static String clickRefJson(String ref) {
        SynthiaAccessibilityService service = instance;
        if (service == null) return json(false, "accessibility-not-enabled");
        return service.clickRef(ref == null ? "" : ref.trim());
    }

    private synchronized String clickRef(String ref) {
        AccessibilityNodeInfo node = refs.get(ref);
        if (node == null) return json(false, "unknown-element-ref", "ref", ref);
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        try {
            while (current != null) {
                if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return json(true, null, "ref", ref);
                }
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
        } finally {
            if (current != null) current.recycle();
        }
        return json(false, "element-not-clickable", "ref", ref);
    }

    public static String tapJson(int x, int y) {
        SynthiaAccessibilityService service = instance;
        if (service == null) return json(false, "accessibility-not-enabled");
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 80))
                .build();
        boolean accepted = service.dispatchGesture(gesture, null, null);
        return accepted ? json(true, null) : json(false, "gesture-rejected");
    }

    public static String swipeJson(int x1, int y1, int x2, int y2, long durationMs) {
        SynthiaAccessibilityService service = instance;
        if (service == null) return json(false, "accessibility-not-enabled");
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        long duration = Math.max(50L, Math.min(5000L, durationMs));
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                .build();
        boolean accepted = service.dispatchGesture(gesture, null, null);
        return accepted ? json(true, null) : json(false, "gesture-rejected");
    }

    public static String pressJson(String button) {
        SynthiaAccessibilityService service = instance;
        if (service == null) return json(false, "accessibility-not-enabled");
        String key = button == null ? "" : button.trim().toLowerCase(Locale.ROOT);
        final int action;
        switch (key) {
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "recents":
            case "recent": action = GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick-settings":
            case "quick_settings": action = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power": action = GLOBAL_ACTION_POWER_DIALOG; break;
            default: return json(false, "unsupported-global-action", "button", key);
        }
        return service.performGlobalAction(action)
                ? json(true, null, "button", key)
                : json(false, "global-action-rejected", "button", key);
    }

    public static String typeJson(String text) {
        SynthiaAccessibilityService service = instance;
        if (service == null) return json(false, "accessibility-not-enabled");
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return json(false, "no-active-accessibility-root");
        AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null || !target.isEditable()) {
            if (target != null) target.recycle();
            target = service.firstEditable(root);
        }
        if (target == null) return json(false, "no-editable-element-focused");
        try {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text == null ? "" : text);
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            return ok ? json(true, null) : json(false, "set-text-rejected");
        } finally {
            target.recycle();
        }
    }

    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isEditable() && node.isEnabled()) {
                while (!queue.isEmpty()) {
                    AccessibilityNodeInfo leftover = queue.removeFirst();
                    leftover.recycle();
                }
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
            node.recycle();
        }
        return null;
    }

    private synchronized void clearRefs() {
        for (AccessibilityNodeInfo node : refs.values()) {
            try { node.recycle(); } catch (Exception ignored) {}
        }
        refs.clear();
    }

    private static String cs(CharSequence value) { return value == null ? "" : value.toString(); }

    private static String q(String value) {
        String v = value == null ? "" : value;
        return "\"" + v
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "\"";
    }

    private static String json(boolean ok, String error, String... fields) {
        StringBuilder out = new StringBuilder("{\"ok\":").append(ok);
        out.append(",\"complete\":true");
        if (error != null) out.append(",\"error\":").append(q(error));
        for (int i = 0; i + 1 < fields.length; i += 2) {
            String key = fields[i];
            String value = fields[i + 1];
            out.append(',').append(q(key)).append(':').append(q(value));
        }
        return out.append('}').toString();
    }
}
