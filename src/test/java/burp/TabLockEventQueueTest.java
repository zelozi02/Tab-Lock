package burp;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Runs regressions through both direct dispatch and a real pushed EventQueue. */
public final class TabLockEventQueueTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(TabLockEventQueueTest::testDirect);
        testRealQueue();
        System.out.println("Tab Lock 3.0: " + checks + " checks passed (including real EventQueue interleaving).");
    }

    private static void testDirect() {
        CountingTabs tabs = new CountingTabs();
        tabs.addTab("Proxy", new JPanel());
        tabs.addTab("Repeater", new JPanel());
        tabs.setSize(500, 300);
        tabs.doLayout();
        Rectangle bounds = tabs.getBoundsAt(1);
        int x = bounds.x + bounds.width / 2;
        int y = bounds.y + bounds.height / 2;
        TabLockEventQueue queue = new TabLockEventQueue(null);

        queue.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_PRESSED, x, y));
        check(tabs.getSelectedIndex() == 1, "normal press still selects a tab");
        queue.dispatchEvent(new InvocationEvent(tabs, () -> {}));
        queue.dispatchEvent(new PaintEvent(tabs, PaintEvent.UPDATE, new Rectangle(0, 0, 500, 300)));
        queue.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_DRAGGED, x + 100, 200));
        queue.dispatchEvent(new InvocationEvent(tabs, () -> {}));
        queue.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_DRAGGED, x + 110, 220));
        queue.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_RELEASED, x + 110, 220));
        check(tabs.drags == 0, "non-mouse events must not erase tab press");
        check(queue.blockedGestures() == 1, "multiple drag events count as one gesture");
        check(tabs.release.equals(new Point(x, y)), "release-based docking sees original coordinates");

        gesture(queue, tabs, 250, 200);
        check(tabs.drags == 1, "content drag passes");
        queue.setLocked(false);
        gesture(queue, tabs, x, y);
        check(tabs.drags == 2, "switch off allows tab drag");
        queue.setLocked(true);
        gesture(queue, tabs, x, y);
        check(tabs.drags == 2, "switch on protects again");
        queue.dispatchEvent(new MouseEvent(tabs, MouseEvent.MOUSE_PRESSED, 1, 0, x, y, 1, true, MouseEvent.BUTTON3));
        queue.dispatchEvent(new MouseEvent(tabs, MouseEvent.MOUSE_RELEASED, 2, 0, x, y, 1, true, MouseEvent.BUTTON3));
        check(tabs.rightClicks == 1, "right-click menu events pass");

        testCustomRow();
        JPanel parent = new JPanel(null);
        parent.setSize(700, 500);
        tabs.setBounds(40, 70, 500, 300);
        parent.add(tabs);
        long before = queue.blockedGestures();
        gesture(queue, parent, 40 + x, 70 + y);
        check(queue.blockedGestures() == before + 1, "ancestor-sourced mouse events hit-test the actual tab");
        queue.uninstall();
        gesture(queue, tabs, x, y);
        check(tabs.drags == 3, "unload restores pass-through");
    }

    private static void testCustomRow() {
        JRootPane root = new JRootPane();
        JPanel content = new JPanel(null);
        CountingPanel custom = new CountingPanel();
        custom.setBounds(0, 0, 600, 400);
        content.add(custom);
        root.setContentPane(content);
        root.setSize(600, 400);
        root.doLayout();
        TabLockEventQueue queue = new TabLockEventQueue(null);

        queue.beginPick();
        queue.dispatchEvent(mouse(custom, MouseEvent.MOUSE_PRESSED, 100, 30));
        queue.dispatchEvent(mouse(custom, MouseEvent.MOUSE_RELEASED, 100, 30));
        queue.dispatchEvent(mouse(custom, MouseEvent.MOUSE_CLICKED, 100, 30));
        check(!queue.isPicking() && custom.presses == 0 && custom.clicks == 0, "calibration click never reaches Burp");
        gesture(queue, custom, 300, 30);
        check(custom.drags == 0 && queue.blockedGestures() == 1, "custom row protects another title on same row");
        gesture(queue, custom, 300, 100);
        check(custom.drags == 1, "outside chosen row passes");
        queue.clearBands();
        gesture(queue, custom, 300, 30);
        check(custom.drags == 2, "clearing calibration restores row");
        queue.beginPick();
        queue.cancelPick();
        gesture(queue, custom, 300, 30);
        check(custom.drags == 3, "cancel does not capture a later click");
    }

    private static void testRealQueue() throws Exception {
        TabLockEventQueue queue = new TabLockEventQueue(null);
        CountingTabs tabs = new CountingTabs();
        SwingUtilities.invokeAndWait(() -> {
            tabs.addTab("Proxy", new JPanel());
            tabs.setSize(500, 300);
            tabs.doLayout();
            Toolkit.getDefaultToolkit().getSystemEventQueue().push(queue);
        });
        try {
            // Barrier InvocationEvents are genuine queued events between press
            // and drag, just like Swing timer/repaint work in the application.
            queue.postEvent(mouse(tabs, MouseEvent.MOUSE_PRESSED, 20, 12));
            SwingUtilities.invokeAndWait(() -> {});
            queue.postEvent(mouse(tabs, MouseEvent.MOUSE_DRAGGED, 200, 150));
            SwingUtilities.invokeAndWait(() -> {});
            queue.postEvent(mouse(tabs, MouseEvent.MOUSE_RELEASED, 200, 150));
            SwingUtilities.invokeAndWait(() -> {
                check(tabs.drags == 0 && queue.blockedGestures() == 1, "real pushed queue survives interleaved InvocationEvents");
                queue.setLocked(false);
            });
            queue.postEvent(mouse(tabs, MouseEvent.MOUSE_PRESSED, 20, 12));
            SwingUtilities.invokeAndWait(() -> {});
            queue.postEvent(mouse(tabs, MouseEvent.MOUSE_DRAGGED, 200, 150));
            queue.postEvent(mouse(tabs, MouseEvent.MOUSE_RELEASED, 200, 150));
            SwingUtilities.invokeAndWait(() -> check(tabs.drags == 1, "real pushed queue switch off passes"));
        } finally {
            SwingUtilities.invokeAndWait(queue::uninstall);
        }
    }

    private static void gesture(TabLockEventQueue queue, Component source, int x, int y) {
        queue.dispatchEvent(mouse(source, MouseEvent.MOUSE_PRESSED, x, y));
        queue.dispatchEvent(new InvocationEvent(source, () -> {}));
        queue.dispatchEvent(mouse(source, MouseEvent.MOUSE_DRAGGED, x + 100, y + 100));
        queue.dispatchEvent(mouse(source, MouseEvent.MOUSE_RELEASED, x + 100, y + 100));
    }

    private static MouseEvent mouse(Component source, int id, int x, int y) {
        boolean drag = id == MouseEvent.MOUSE_DRAGGED;
        return new MouseEvent(source, id, System.currentTimeMillis(),
                drag ? MouseEvent.BUTTON1_DOWN_MASK : 0, x, y, x, y,
                1, false, drag ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static final class CountingTabs extends JTabbedPane {
        int drags;
        int rightClicks;
        Point release;
        CountingTabs() { enableEvents(AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK); }
        @Override protected void processMouseMotionEvent(MouseEvent event) {
            if (event.getID() == MouseEvent.MOUSE_DRAGGED) drags++;
            super.processMouseMotionEvent(event);
        }
        @Override protected void processMouseEvent(MouseEvent event) {
            if (event.getID() == MouseEvent.MOUSE_RELEASED) release = event.getPoint();
            if (event.getID() == MouseEvent.MOUSE_PRESSED && event.getButton() == MouseEvent.BUTTON3) rightClicks++;
            super.processMouseEvent(event);
        }
    }

    private static final class CountingPanel extends JPanel {
        int drags;
        int presses;
        int clicks;
        CountingPanel() { enableEvents(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK); }
        @Override protected void processMouseMotionEvent(MouseEvent event) {
            if (event.getID() == MouseEvent.MOUSE_DRAGGED) drags++;
        }
        @Override protected void processMouseEvent(MouseEvent event) {
            if (event.getID() == MouseEvent.MOUSE_PRESSED) presses++;
            if (event.getID() == MouseEvent.MOUSE_CLICKED) clicks++;
        }
    }
}
