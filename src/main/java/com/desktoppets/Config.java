package com.desktoppets;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads and writes {@code config.txt}. Supports both formats:
 * <ul>
 *   <li><b>New:</b> key=value lines: {@code pets=ducky,cat,bird},
 *       {@code pet.size=64}, {@code pet.activity=1.0}, plus optional
 *       {@code pos.<name>=x,y} lines for remembered positions.</li>
 *   <li><b>Legacy:</b> one pet name per line after an
 *       {@code Enter pets after this line:} marker.</li>
 * </ul>
 * Writes are atomic ({@code Files.move(..., ATOMIC_MOVE)}).
 */
public final class Config {

    public static final Path PATH = Paths.get("config.txt");
    private static final String PETS_KEY     = "pets";
    private static final String SIZE_KEY     = "pet.size";
    private static final String ACTIVITY_KEY = "pet.activity";
    private static final String POS_PREFIX   = "pos.";
    private static final String MON_PREFIX   = "mon.";
    private static final String MARKER       = "Enter pets after this line:";

    public static final int    DEFAULT_SIZE     = 64;
    /**
     * Default activity multiplier. 0.7 strikes a balance: the pet idles
     * most of the time but visibly wanders, has zoomies and disappears /
     * reappears every few minutes. Lower it for a calmer companion; raise
     * it for a hyperactive one.
     */
    public static final double DEFAULT_ACTIVITY = 0.7;

    /** Serialises read-modify-write cycles when multiple pets persist concurrently. */
    private static final ReentrantLock LOCK = new ReentrantLock();

    private Config() {
    }

    public static List<String> readPets() {
        Properties p = readProperties();
        String csv = p.getProperty(PETS_KEY);
        if (csv != null) {
            return parseCsv(csv);
        }
        return readLegacyPets();
    }

    public static void writePets(List<String> pets) {
        LOCK.lock();
        try {
            Properties p = readProperties();
            Set<String> ordered = new LinkedHashSet<>();
            for (String pet : pets) {
                ordered.add(pet.toLowerCase(Locale.ROOT));
            }
            p.setProperty(PETS_KEY, String.join(",", ordered));
            writeAtomically(p);
        } finally {
            LOCK.unlock();
        }
    }

    public static int readPetSize() {
        String v = readProperties().getProperty(SIZE_KEY);
        if (v == null) return DEFAULT_SIZE;
        try {
            return Math.max(16, Math.min(256, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_SIZE;
        }
    }

    public static void writePetSize(int size) {
        LOCK.lock();
        try {
            Properties p = readProperties();
            p.setProperty(SIZE_KEY, Integer.toString(size));
            writeAtomically(p);
        } finally {
            LOCK.unlock();
        }
    }

    public static double readActivity() {
        String v = readProperties().getProperty(ACTIVITY_KEY);
        if (v == null) return DEFAULT_ACTIVITY;
        try {
            double d = Double.parseDouble(v.trim());
            return Math.max(0.0, Math.min(2.0, d));
        } catch (NumberFormatException e) {
            return DEFAULT_ACTIVITY;
        }
    }

    public static void writeActivity(double level) {
        LOCK.lock();
        try {
            Properties p = readProperties();
            p.setProperty(ACTIVITY_KEY, String.format(Locale.ROOT, "%.2f", level));
            writeAtomically(p);
        } finally {
            LOCK.unlock();
        }
    }

    public static Point readPosition(String petName) {
        Properties p = readProperties();
        String v = p.getProperty(POS_PREFIX + petName.toLowerCase(Locale.ROOT));
        if (v == null) {
            return null;
        }
        String[] parts = v.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new Point(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void writePosition(String petName, Point pos) {
        LOCK.lock();
        try {
            Properties p = readProperties();
            String key = POS_PREFIX + petName.toLowerCase(Locale.ROOT);
            String value = pos.x + "," + pos.y;
            if (value.equals(p.getProperty(key))) {
                return; // no-op
            }
            p.setProperty(key, value);
            writeAtomically(p);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Reads the remembered monitor bounds for {@code petName}, or null if
     * absent or malformed. Bound monitors are persisted alongside
     * positions so that on restart we can re-bind the pet to the SAME
     * physical monitor it was on — important when monitors have different
     * DPI or when one is offset from origin (a saved position alone is
     * ambiguous if two monitors overlap in coordinate space).
     */
    public static Rectangle readMonitor(String petName) {
        Properties p = readProperties();
        String v = p.getProperty(MON_PREFIX + petName.toLowerCase(Locale.ROOT));
        if (v == null) {
            return null;
        }
        String[] parts = v.split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new Rectangle(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()),
                    Integer.parseInt(parts[3].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void writeMonitor(String petName, Rectangle mon) {
        LOCK.lock();
        try {
            Properties p = readProperties();
            String key = MON_PREFIX + petName.toLowerCase(Locale.ROOT);
            if (mon == null) {
                if (p.remove(key) == null) {
                    return;
                }
            } else {
                String value = mon.x + "," + mon.y + "," + mon.width + "," + mon.height;
                if (value.equals(p.getProperty(key))) {
                    return;
                }
                p.setProperty(key, value);
            }
            writeAtomically(p);
        } finally {
            LOCK.unlock();
        }
    }

    // ---------------- internals ----------------

    private static Properties readProperties() {
        Properties p = new Properties();
        if (!Files.exists(PATH)) {
            return p;
        }
        try {
            List<String> lines = Files.readAllLines(PATH);
            for (String raw : lines) {
                String line = raw.trim();
                int eq = line.indexOf('=');
                if (eq <= 0 || line.startsWith("#")) {
                    continue;
                }
                p.setProperty(line.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                              line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            // ignore — treat as empty
        }
        return p;
    }

    private static List<String> readLegacyPets() {
        if (!Files.exists(PATH)) {
            return new ArrayList<>(List.of("ducky"));
        }
        List<String> pets = new ArrayList<>();
        try {
            boolean seen = false;
            for (String raw : Files.readAllLines(PATH)) {
                String line = raw.trim();
                if (!seen) {
                    if (line.equalsIgnoreCase(MARKER)) {
                        seen = true;
                    }
                    continue;
                }
                if (!line.isEmpty() && !line.contains("=")) {
                    pets.add(line.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return pets.isEmpty() ? new ArrayList<>(List.of("ducky")) : pets;
    }

    private static void writeAtomically(Properties props) {
        // Stable, human-readable ordering: pets, size, activity, positions, then anything else.
        Map<String, String> sorted = new LinkedHashMap<>();
        for (String k : new String[] {PETS_KEY, SIZE_KEY, ACTIVITY_KEY}) {
            if (props.containsKey(k)) {
                sorted.put(k, props.getProperty(k));
            }
        }
        props.stringPropertyNames().stream()
                .filter(k -> k.startsWith(POS_PREFIX))
                .sorted()
                .forEach(k -> sorted.put(k, props.getProperty(k)));
        props.stringPropertyNames().stream()
                .filter(k -> k.startsWith(MON_PREFIX))
                .sorted()
                .forEach(k -> sorted.put(k, props.getProperty(k)));
        for (String k : props.stringPropertyNames()) {
            sorted.putIfAbsent(k, props.getProperty(k));
        }

        List<String> out = new ArrayList<>();
        out.add("# DESKTOP PETS CONFIG");
        out.add("# Pets: Ducky, Cat, Bird (edit via tray Settings).");
        out.add("");
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            out.add(e.getKey() + "=" + e.getValue());
        }
        try {
            Path tmp = Paths.get(PATH.toString() + ".tmp");
            Files.write(tmp, out);
            try {
                Files.move(tmp, PATH,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not save config: " + e.getMessage(), e);
        }
    }

    private static List<String> parseCsv(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : Arrays.asList(csv.split(","))) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
