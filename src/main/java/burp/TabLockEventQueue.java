package burp;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** All mutable state belongs to the Swing event dispatch thread. */
final class TabLockEventQueue extends EventQueue {
    private final Window suiteWindow;
    private final List<Band> bands = new ArrayList<>();
    private Component excludedPanel;
    private boolean active = true;
    private boolean locked = true;
    private boolean picking;
    private boolean swallowPick;
    private MouseEvent press;
    private boolean dragged;
    private long matchedPresses;
    private long blockedGestures;
    private String selectionMessage = "兼容标签栏：未选取";

    TabLockEventQueue(Window suiteWindow) { this.suiteWindow = suiteWindow; }
    void setExcludedPanel(Component panel) { excludedPanel = panel; }
    boolean isLocked() { return locked; }
    boolean isPicking() { return picking; }
    long matchedPresses() { return matchedPresses; }
    long blockedGestures() { return blockedGestures; }
    String selectionMessage() { return selectionMessage; }

    void setLocked(boolean value) {
        locked = value;
        press = null;
        dragged = false;
        cancelPick();
    }
    void beginPick() { picking = true; }
    void cancelPick() {
        picking = false;
        selectionMessage = "兼容标签栏：已选取 " + bands.size() + " 行";
    }
    void clearBands() { bands.clear(); cancelPick(); }

    @Override
    protected void dispatchEvent(AWTEvent event) {
        if (!active) {
            super.dispatchEvent(event);
            return;
        }
        if (event instanceof KeyEvent key && picking && key.getKeyCode() == KeyEvent.VK_ESCAPE) {
            cancelPick();
            key.consume();
            return;
        }
        // Repaints, InvocationEvents, timers and focus changes MUST NOT reset
        // the current mouse gesture. This was the failure in versions 1/2.
        if (!(event instanceof MouseEvent mouse)) {
            super.dispatchEvent(event);
            return;
        }
        if (mouse.getID() == MouseEvent.MOUSE_PRESSED && mouse.getButton() == MouseEvent.BUTTON1) {
            MouseEvent hit = resolveTarget(mouse);
            swallowPick = false;
            press = null;
            dragged = false;
            if (picking && isInScope(hit.getComponent()) && selectBand(hit)) {
                picking = false;
                swallowPick = true;
                mouse.consume();
                return;
            }
            if (locked && isProtected(hit)) {
                press = mouse;
                matchedPresses++;
            }
        }
        if (mouse.getID() == MouseEvent.MOUSE_DRAGGED && (swallowPick || press != null)) {
            if (!swallowPick && !dragged) {
                dragged = true;
                blockedGestures++;
            }
            mouse.consume();
            return;
        }
        if (mouse.getID() == MouseEvent.MOUSE_RELEASED && mouse.getButton() == MouseEvent.BUTTON1) {
            if (swallowPick) {
                mouse.consume();
                // Keep this flag through trailing MOUSE_CLICKED so selection
                // cannot activate a tab's close button.
                return;
            }
            MouseEvent start = press;
            boolean wasDragged = dragged;
            press = null;
            dragged = false;
            if (start != null && wasDragged) {
                // Release-based docking handlers must also see a stationary
                // click, not a drop outside the tab strip.
                MouseEvent release = new MouseEvent(start.getComponent(), MouseEvent.MOUSE_RELEASED,
                        mouse.getWhen(), mouse.getModifiersEx(), start.getX(), start.getY(),
                        start.getXOnScreen(), start.getYOnScreen(), 1, false, MouseEvent.BUTTON1);
                super.dispatchEvent(release);
                return;
            }
        }
        if (mouse.getID() == MouseEvent.MOUSE_CLICKED && swallowPick) {
            swallowPick = false;
            mouse.consume();
            return;
        }
        super.dispatchEvent(event);
    }

    // Native events can enter EventQueue with a heavyweight window as source,
    // BEFORE AWT retargets them to Swing children. Hit-test those events here.
    // Keep the original event for normal dispatch and the matching release.
    private static MouseEvent resolveTarget(MouseEvent event) {
        Component source = event.getComponent();
        if (!(source instanceof Container container)) return event;
        Component target = SwingUtilities.getDeepestComponentAt(container, event.getX(), event.getY());
        if (target == null || target == source) return event;
        Point point = SwingUtilities.convertPoint(source, event.getPoint(), target);
        return new MouseEvent(target, event.getID(), event.getWhen(), event.getModifiersEx(),
                point.x, point.y, event.getXOnScreen(), event.getYOnScreen(),
                event.getClickCount(), event.isPopupTrigger(), event.getButton());
    }

    private boolean isInScope(Component source) {
        if (source == null) return false;
        if (excludedPanel != null && SwingUtilities.isDescendingFrom(source, excludedPanel)) return false;
        // Null window is only used for isolated headless test components.
        if (suiteWindow == null) return true;
        Window window = SwingUtilities.getWindowAncestor(source);
        while (window != null) {
            if (window == suiteWindow) return true;
            window = window.getOwner();
        }
        return false;
    }

    private boolean isProtected(MouseEvent event) {
        Component source = event.getComponent();
        if (!isInScope(source)) return false;
        for (Component current = source; current != null; current = current.getParent()) {
            if (current instanceof JTabbedPane tabs) {
                Point point = SwingUtilities.convertPoint(source, event.getPoint(), tabs);
                if (tabs.indexAtLocation(point.x, point.y) >= 0) return true;
            }
        }
        bands.removeIf(band -> band.content.get() == null);
        for (Band band : bands) if (band.contains(source, event.getPoint())) return true;
        return false;
    }

    private boolean selectBand(MouseEvent event) {
        JRootPane root = SwingUtilities.getRootPane(event.getComponent());
        if (root == null) return false;
        Container content = root.getContentPane();
        Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), content);
        if (!content.contains(point)) return false;
        Component source = event.getComponent();
        int fontHeight = source.getFont() == null ? 18 : source.getFontMetrics(source.getFont()).getHeight();
        int height = Math.max(24, fontHeight + 12);
        int top = point.y - height / 2;
        // Use real tab geometry where available. Otherwise the user explicitly
        // chooses a horizontal row; no class names or screen DPI are guessed.
        for (Component current = source; current != null; current = current.getParent()) {
            if (current instanceof JTabbedPane tabs) {
                Point local = SwingUtilities.convertPoint(source, event.getPoint(), tabs);
                int index = tabs.indexAtLocation(local.x, local.y);
                if (index >= 0) {
                    Rectangle bounds = tabs.getBoundsAt(index);
                    top = SwingUtilities.convertPoint(tabs, bounds.getLocation(), content).y;
                    height = bounds.height;
                    break;
                }
            }
        }
        int y = Math.max(0, top);
        bands.removeIf(band -> band.content.get() == content && Math.abs(band.y - y) < 8);
        bands.add(new Band(content, y, height));
        selectionMessage = "兼容标签栏：已选取 " + bands.size() + " 行";
        return true;
    }

    void uninstall() {
        active = false;
        press = null;
        bands.clear();
        excludedPanel = null;
        // Never pop another extension's queue. If covered, remain inert.
        if (Toolkit.getDefaultToolkit().getSystemEventQueue() == this) pop();
    }

    private static final class Band {
        private final WeakReference<Container> content;
        private final int y;
        private final int height;
        Band(Container content, int y, int height) {
            this.content = new WeakReference<>(content);
            this.y = y;
            this.height = height;
        }
        boolean contains(Component source, Point point) {
            Container container = content.get();
            if (container == null || !SwingUtilities.isDescendingFrom(source, container)) return false;
            Point local = SwingUtilities.convertPoint(source, point, container);
            return local.x >= 0 && local.x < container.getWidth() && local.y >= y && local.y < y + height;
        }
    }
}
