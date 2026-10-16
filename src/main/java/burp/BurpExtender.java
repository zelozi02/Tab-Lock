package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** UI and filter lifecycle are serialized on the Swing EDT. */
public final class BurpExtender implements BurpExtension {
    private final AtomicBoolean unloaded = new AtomicBoolean();
    private TabLockEventQueue queue;
    private Timer refresh;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Tab Lock");
        api.extension().registerUnloadingHandler(() -> {
            unloaded.set(true);
            SwingUtilities.invokeLater(() -> {
                if (refresh != null) refresh.stop();
                if (queue != null) queue.uninstall();
            });
        });
        SwingUtilities.invokeLater(() -> {
            if (unloaded.get()) return;
            try {
                JPanel panel = new JPanel();
                panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
                panel.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
                Window frame = api.userInterface().swingUtils().suiteFrame();
                if (frame == null) throw new IllegalStateException("Burp main window is unavailable");
                queue = new TabLockEventQueue(frame);
                queue.setExcludedPanel(panel);

                JLabel title = new JLabel("Tab Lock — 标签防误拖");
                JCheckBox enabled = new JCheckBox("开启标签锁定", true);
                JLabel status = new JLabel("状态：初始化中");
                JLabel activity = new JLabel("命中标签按下：0    已拦截拖动：0");
                JLabel selection = new JLabel("兼容标签栏：未选取");
                JButton pick = new JButton("选取 / 添加标签栏");
                JButton cancel = new JButton("取消选取");
                JButton clear = new JButton("清除选取");
                JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                buttons.add(pick);
                buttons.add(Box.createHorizontalStrut(12));
                buttons.add(cancel);
                buttons.add(Box.createHorizontalStrut(12));
                buttons.add(clear);
                buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));

                enabled.addActionListener(e -> queue.setLocked(enabled.isSelected()));
                pick.addActionListener(e -> {
                    queue.beginPick();
                    selection.setText("请单击需要锁定的标签标题；本次点击只用于选取。按 Esc 可取消。");
                });
                cancel.addActionListener(e -> queue.cancelPick());
                clear.addActionListener(e -> queue.clearBands());

                add(panel, title, 16);
                add(panel, new JLabel("自动保护标准标签栏；单击切换、右键菜单保持可用。"), 8);
                add(panel, enabled, 12);
                add(panel, status, 6);
                add(panel, activity, 20);
                add(panel, new JLabel("若自动识别未命中：点击下方按钮，再单击实际要保护的标签标题。"), 6);
                add(panel, new JLabel("兼容模式会保护选中标题所在的整行。窗口布局变化后请重新选取。"), 12);
                add(panel, buttons, 8);
                add(panel, selection, 16);
                add(panel, new JLabel("关闭开关立即恢复拖动。兼容区域仅在本次加载期间保存。"), 8);
                panel.add(Box.createVerticalGlue());
                api.userInterface().applyThemeToComponent(panel);
                title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 5));

                api.userInterface().registerSuiteTab("Tab Lock", panel);
                Toolkit.getDefaultToolkit().getSystemEventQueue().push(queue);
                refresh = new Timer(300, e -> {
                    boolean top = Toolkit.getDefaultToolkit().getSystemEventQueue() == queue;
                    status.setText(!top ? "状态：事件队列已变化；若拦截无效请重新加载插件"
                            : queue.isLocked() ? "状态：已开启" : "状态：已关闭");
                    activity.setText("命中标签按下：" + queue.matchedPresses()
                            + "    已拦截拖动：" + queue.blockedGestures());
                    if (!queue.isPicking()) selection.setText(queue.selectionMessage());
                    cancel.setEnabled(queue.isPicking());
                });
                refresh.setInitialDelay(0);
                refresh.start();
                api.logging().logToOutput("Tab Lock 3.0 loaded. Open Tab Lock for controls and interception counters.");
            } catch (RuntimeException | LinkageError error) {
                if (queue != null) queue.uninstall();
                api.logging().logToError("Tab Lock 3.0 initialization failed: " + error);
            }
        });
    }

    private static void add(JPanel panel, JComponent component, int gap) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(component);
        panel.add(Box.createVerticalStrut(gap));
    }
}
