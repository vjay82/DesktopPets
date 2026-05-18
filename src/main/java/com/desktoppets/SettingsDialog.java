package com.desktoppets;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

/**
 * Modal Swing dialog letting the user check which pets should be running,
 * resize them (16..128 px), and tune how active they are (0..200 %).
 */
public final class SettingsDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    // Bird is intentionally NOT in this list: it's now a "visitor" species
    // spawned ad-hoc by {@link BirdVisitor} near a resident pet and
    // self-disposes after a short stay, so the user can't toggle it as a
    // regular pet.
    private static final String[] AVAILABLE = {"ducky", "cat", "dog"};

    public SettingsDialog(JFrame owner, PetSupervisor supervisor) {
        super(owner, "Desktop Pets — Settings", true);
        applyNimbus();
        setLayout(new BorderLayout(8, 8));

        // Remember pre-dialog values so Cancel can revert any live-preview
        // changes made via the sliders. Without this, dragging a slider
        // applies immediately and Cancel leaves the change in place.
        final int origSize = supervisor.getPetSize();
        final double origActivity = supervisor.getActivityLevel();

        Map<String, JCheckBox> boxes = new LinkedHashMap<>();
        List<String> current = Config.readPets();

        JPanel content = new JPanel();
        content.setLayout(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));

        JPanel petsPanel = new JPanel(new GridLayout(0, 1));
        petsPanel.add(new JLabel("Active pets:"));
        for (String name : AVAILABLE) {
            JCheckBox cb = new JCheckBox(capitalize(name), current.contains(name));
            boxes.put(name, cb);
            petsPanel.add(cb);
        }
        content.add(petsPanel, BorderLayout.NORTH);

        JPanel slidersPanel = new JPanel(new GridLayout(0, 1, 0, 4));
        slidersPanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        int curSize = supervisor.getPetSize();
        JLabel sizeLabel = new JLabel("Pet size: " + curSize + " px");
        JSlider sizeSlider = new JSlider(16, 128, curSize);
        sizeSlider.setMajorTickSpacing(16);
        sizeSlider.setMinorTickSpacing(8);
        sizeSlider.setPaintTicks(true);
        sizeSlider.addChangeListener(_ -> {
            int v = sizeSlider.getValue();
            sizeLabel.setText("Pet size: " + v + " px");
            if (!sizeSlider.getValueIsAdjusting()) {
                supervisor.setPetSize(v);
            }
        });
        slidersPanel.add(sizeLabel);
        slidersPanel.add(sizeSlider);

        int curActivityPct = (int) Math.round(supervisor.getActivityLevel() * 100);
        JLabel activityLabel = new JLabel("Activity: " + curActivityPct + " %");
        JSlider activitySlider = new JSlider(0, 200, curActivityPct);
        activitySlider.setMajorTickSpacing(50);
        activitySlider.setMinorTickSpacing(10);
        activitySlider.setPaintTicks(true);
        activitySlider.addChangeListener(_ -> {
            int v = activitySlider.getValue();
            activityLabel.setText("Activity: " + v + " %");
            if (!activitySlider.getValueIsAdjusting()) {
                supervisor.setActivityLevel(v / 100.0);
            }
        });
        slidersPanel.add(activityLabel);
        slidersPanel.add(activitySlider);

        content.add(slidersPanel, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(_ -> {
            List<String> selected = new ArrayList<>();
            for (Map.Entry<String, JCheckBox> en : boxes.entrySet()) {
                if (en.getValue().isSelected()) {
                    selected.add(en.getKey());
                }
            }
            Config.writePets(selected);
            Config.writePetSize(sizeSlider.getValue());
            Config.writeActivity(activitySlider.getValue() / 100.0);
            supervisor.setPetSize(sizeSlider.getValue());
            supervisor.setActivityLevel(activitySlider.getValue() / 100.0);
            supervisor.reconcile(selected);
            dispose();
        });
        Runnable revert = () -> {
            // Revert live-preview slider changes; checkbox state is not
            // applied until OK so it needs no revert.
            supervisor.setPetSize(origSize);
            supervisor.setActivityLevel(origActivity);
        };
        cancel.addActionListener(_ -> { revert.run(); dispose(); });

        // Closing via the window's X button bypasses the Cancel handler.
        // Hook windowClosing so the revert still runs in that case.
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                revert.run();
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);
        add(buttons, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /**
     * Switch the global Swing LAF to Nimbus before this dialog's widgets are
     * built so they pick it up. No-op if Nimbus isn't installed or is already
     * the current LAF. The tray icon and the borderless / transparent pet
     * frames don't depend on the LAF, so leaving Nimbus in place after the
     * dialog closes is harmless.
     */
    private static void applyNimbus() {
        String current = UIManager.getLookAndFeel() != null
                ? UIManager.getLookAndFeel().getClass().getName() : "";
        if (current.endsWith(".NimbusLookAndFeel")) {
            return;
        }
        for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
            if ("Nimbus".equals(info.getName())) {
                try {
                    UIManager.setLookAndFeel(info.getClassName());
                } catch (ClassNotFoundException | InstantiationException
                        | IllegalAccessException | UnsupportedLookAndFeelException e) {
                    Log.warn("settings", "could not apply Nimbus LAF: " + e);
                }
                return;
            }
        }
    }
}
