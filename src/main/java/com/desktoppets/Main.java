package com.desktoppets;

import java.util.Locale;

import javax.swing.SwingUtilities;

/**
 * Entry point. Installs the tray icon, then starts the pets listed in
 * {@code config.txt}. There are no more sprite resources to validate — all
 * graphics are drawn procedurally by {@link Doodle}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String triggerEvent = resolveTriggerEvent(args);
        Log.info("main", "Desktop Pets starting (procedural graphics)");
        // Begin sampling the cursor in the background so HUNT_CURSOR
        // (and any other motion-aware activity) has an accurate recent
        // history independent of which activity each pet is currently
        // running.
        World.startCursorSampler();
        // Reconcile the per-monitor rendering state (Swing stages / DComp
        // monitor map + host window) when a screen is attached / removed or the
        // resolution changes while pets are running.
        DisplayWatcher.start();
        // Stage windows are click-through (so they don't trap user input);
        // hover/click on pets is delivered by polling the cursor + button
        // state in PetMouse and dispatching to whichever pet is under the
        // pointer at press time.
        PetMouse.start();
        PetSupervisor supervisor = new PetSupervisor();
        SwingUtilities.invokeLater(() -> new TrayApp(supervisor).install());
        supervisor.reconcile(Config.readPets());
        // Central special-events scheduler: one daemon timer drives every rare
        // cosmetic event (wandering bird, cross-species solo-pet visitor, the
        // UFO spectacle, and the airplane fly-by). Each event self-gates on
        // one active (visible) resident pet, so nothing appears on an empty
        // desktop. The timer thread itself is started on the first pet's
        // activation and stopped after the last pet leaves (see
        // startWhenResidentsPresent below). New events: implement
        // SpecialEvents.Event and register() it here.
        SpecialEvents specialEvents = new SpecialEvents(supervisor);
        specialEvents.register(BirdVisitor.event());
        specialEvents.register(PetVisitor.event());
        specialEvents.register(UfoVisitor.event());
        specialEvents.register(AirplaneVisitor.event());
        specialEvents.register(MouseVisitor.event());
        specialEvents.register(RainCloudVisitor.event());
        specialEvents.register(DroneVisitor.event());
        specialEvents.register(CardboardBoxVisitor.event());
        specialEvents.register(LaserPointerVisitor.event());
        // Residents were just reconciled above, so this starts the timer
        // immediately when config.txt lists any pets; with an empty config it
        // stays idle until the user adds a pet from the tray.
        specialEvents.startWhenResidentsPresent();
        // A special event can be triggered immediately from the command line
        // for testing — generically by its SpecialEvents id
        // (`--event airplane-visitor`) or via a short alias (`--airplane`,
        // `--ufo`, `--bird`, `--pet`). The trigger waits briefly for an active
        // resident to witness it, then fires once, bypassing the random
        // probability gate. See SpecialEvents.triggerNow.
        if (triggerEvent != null) {
            specialEvents.triggerNow(triggerEvent);
        }
    }

    /** True iff any of {@code names} appears in {@code args} (case-insensitive). */
    private static boolean hasFlag(String[] args, String... names) {
        for (String arg : args) {
            for (String name : names) {
                if (name.equalsIgnoreCase(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Resolve a special event to trigger immediately from the command line, or
     * {@code null} if none was requested. Supports the generic
     * {@code --event <id>} / {@code --event=<id>} form (where {@code id} is the
     * event's {@link SpecialEvents} id, e.g. {@code airplane-visitor}) plus the
     * short aliases {@code --ufo}, {@code --airplane}, {@code --bird} and
     * {@code --pet}.
     */
    private static String resolveTriggerEvent(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String lower = args[i].toLowerCase(Locale.ROOT);
            if (lower.equals("--event") || lower.equals("-event")) {
                return i + 1 < args.length ? aliasToEventId(args[i + 1]) : null;
            }
            if (lower.startsWith("--event=") || lower.startsWith("-event=")) {
                return aliasToEventId(args[i].substring(args[i].indexOf('=') + 1));
            }
        }
        // Bare short aliases as a convenience.
        if (hasFlag(args, "--ufo", "-ufo", "ufo")) {
            return "ufo-visitor";
        }
        if (hasFlag(args, "--airplane", "-airplane", "airplane", "--plane")) {
            return "airplane-visitor";
        }
        if (hasFlag(args, "--bird", "-bird", "bird")) {
            return "bird-visitor";
        }
        if (hasFlag(args, "--pet", "-pet", "pet", "--visitor")) {
            return "pet-visitor";
        }
        if (hasFlag(args, "--mouse", "-mouse", "mouse")) {
            return "mouse-visitor";
        }
        if (hasFlag(args, "--cloud", "-cloud", "cloud", "--rain", "rain")) {
            return "rain-cloud";
        }
        if (hasFlag(args, "--drone", "-drone", "drone")) {
            return "drone-delivery";
        }
        if (hasFlag(args, "--box", "-box", "box")) {
            return "cardboard-box";
        }
        if (hasFlag(args, "--laser", "-laser", "laser")) {
            return "laser-pointer";
        }
        return null;
    }

    /** Map a short alias (or pass through a full {@link SpecialEvents} id). */
    private static String aliasToEventId(String raw) {
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "ufo":
                return "ufo-visitor";
            case "airplane":
            case "plane":
                return "airplane-visitor";
            case "bird":
                return "bird-visitor";
            case "pet":
            case "visitor":
                return "pet-visitor";
            case "mouse":
                return "mouse-visitor";
            case "cloud":
            case "rain":
                return "rain-cloud";
            case "drone":
                return "drone-delivery";
            case "box":
                return "cardboard-box";
            case "laser":
                return "laser-pointer";
            default:
                return raw; // assume it's already a full id
        }
    }
}
