package com.desktoppets.tools;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Standalone occlusion diagnostic. Enumerates every visible top-level window
 * and, for each, replays the EXACT decision that
 * {@code Win32.holeEnumProc} / {@code collectOccluders} makes when deciding
 * whether that window should carve a hole in the pet stage (i.e. make the pets
 * appear to hide behind it). The point is to find the window that is "cutting
 * off" the pets: any free-floating, NON-maximised window overlapping the
 * bottom of a monitor will carve every pet standing under it.
 *
 * <p>For each window it prints the verdict — {@code CARVE} (this window hides
 * pets where it overlaps) or {@code skip:<reason>} — plus the rectangle, how
 * much of its monitor it covers, whether it overlaps the bottom "floor band"
 * where pets stand, its class, owning image, and title. CARVE rows are listed
 * first, largest area first, so the prime suspect is at the top.
 *
 * <p>Run (from the project, JDK 26 on JAVA_HOME):
 * {@code mvn -q compile; java --enable-native-access=ALL-UNNAMED -cp target/classes com.desktoppets.tools.DumpOccluders}
 */
public final class DumpOccluders {

    // --- constants ---------------------------------------------------------
    private static final int  GWL_EXSTYLE = -20;
    private static final long WS_EX_TOPMOST = 0x00000008L;
    private static final int  PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final int  MONITOR_DEFAULTTONEAREST = 0x00000002;
    private static final int  DWMWA_CLOAKED = 14;
    /** Mirrors {@code Win32.coversWholeStage}: within this many px on every edge. */
    private static final int  COVERS_MARGIN = 2;
    /** Fraction of the monitor height (from the bottom) treated as the pet floor band. */
    private static final double FLOOR_BAND = 0.30;

    // --- FFM plumbing ------------------------------------------------------
    private static final Arena ARENA = Arena.ofShared();
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup USER32   = SymbolLookup.libraryLookup("user32",   ARENA);
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", ARENA);
    private static final SymbolLookup DWMAPI   = SymbolLookup.libraryLookup("dwmapi",   ARENA);

    private static final MethodHandle ENUM_WINDOWS = LINKER.downcallHandle(
            USER32.find("EnumWindows").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle IS_WINDOW_VISIBLE = LINKER.downcallHandle(
            USER32.find("IsWindowVisible").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle IS_ZOOMED = LINKER.downcallHandle(
            USER32.find("IsZoomed").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle IS_ICONIC = LINKER.downcallHandle(
            USER32.find("IsIconic").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle GET_WINDOW_RECT = LINKER.downcallHandle(
            USER32.find("GetWindowRect").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle GET_CLASS_NAME = LINKER.downcallHandle(
            USER32.find("GetClassNameA").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle GET_WINDOW_TEXT = LINKER.downcallHandle(
            USER32.find("GetWindowTextA").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID = LINKER.downcallHandle(
            USER32.find("GetWindowThreadProcessId").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle GET_WINDOW_LONG_PTR = LINKER.downcallHandle(
            USER32.find("GetWindowLongPtrA").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle MONITOR_FROM_WINDOW = LINKER.downcallHandle(
            USER32.find("MonitorFromWindow").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle GET_MONITOR_INFO = LINKER.downcallHandle(
            USER32.find("GetMonitorInfoA").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle OPEN_PROCESS = LINKER.downcallHandle(
            KERNEL32.find("OpenProcess").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle CLOSE_HANDLE = LINKER.downcallHandle(
            KERNEL32.find("CloseHandle").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle QUERY_FULL_PROCESS_IMAGE_NAME = LINKER.downcallHandle(
            KERNEL32.find("QueryFullProcessImageNameA").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle DWM_GET_WINDOW_ATTRIBUTE = LINKER.downcallHandle(
            DWMAPI.find("DwmGetWindowAttribute").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private static final long SELF_PID = ProcessHandle.current().pid();
    private static final List<Row> ROWS = new ArrayList<>();

    private static final MemorySegment ENUM_STUB;
    static {
        try {
            MethodHandle h = MethodHandles.lookup().findStatic(
                    DumpOccluders.class, "enumProc",
                    MethodType.methodType(int.class, MemorySegment.class, long.class));
            ENUM_STUB = LINKER.upcallStub(h,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                    ARENA);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void main(String[] args) throws Throwable {
        ENUM_WINDOWS.invoke(ENUM_STUB, 0L);

        // Post-pass: for every CARVE window, decide whether a "pets float on
        // top" window (maximised / covers-monitor / covers-work-area) that is
        // IN FRONT of it (smaller z) fully covers it. If so, the carve happens
        // underneath a window where pets are supposed to float on top — the
        // carver is itself invisible there, so carving for it is a BUG (pets
        // get cut out in a region the user sees the FRONT window, not the
        // carver). EnumWindows enumerated front-to-back, so z is the z-order.
        for (Row c : ROWS) {
            if (!c.carves) {
                continue;
            }
            for (Row f : ROWS) {
                if (f.floatOnTop && f.z < c.z && coversRect(f, c)) {
                    c.hiddenByFront = true;
                    c.hiddenBy = basename(f.image) + " (" + f.verdict.replace("skip:", "") + ")";
                    break;
                }
            }
        }
        // Mirror the engine: a carver fully hidden behind a front float-on-top
        // window is suppressed (verdict skip:hidden-behind), so the summary
        // reflects what actually gets carved after the fix.
        for (Row c : ROWS) {
            if (c.carves && c.hiddenByFront) {
                c.carves = false;
                c.verdict = "skip:hidden-behind";
            }
        }

        // CARVE rows first, then by descending area (biggest suspect at the top).
        ROWS.sort(Comparator
                .comparing((Row r) -> !r.carves)
                .thenComparing(r -> -(long) r.w * r.h));

        System.out.println("Self PID (this diagnostic) = " + SELF_PID
                + "   (pet app runs in a DIFFERENT java.exe; its full-monitor"
                + " translucent stage shows as skip:covers-monitor)");
        System.out.println("Floor band = bottom " + (int) (FLOOR_BAND * 100)
                + "% of each monitor (where pets stand).  Z = z-order (0 = frontmost).");
        System.out.println("=".repeat(160));
        System.out.printf("%-22s %-4s %-7s %-22s %-7s %-6s %-22s %-15s %s%n",
                "VERDICT", "Z", "PID", "RECT(x,y,w,h)", "%MON", "FLOOR", "CLASS", "HIDDEN-BEHIND", "TITLE");
        System.out.println("-".repeat(160));
        int carvers = 0;
        int floorCarvers = 0;
        int hiddenCarvers = 0;
        for (Row r : ROWS) {
            if (r.carves) {
                carvers++;
                if (r.overlapsFloor) floorCarvers++;
            }
            if (r.hiddenByFront) {
                hiddenCarvers++;
            }
            System.out.printf("%-22s %-4d %-7d %-22s %-7s %-6s %-22s %-15s %s%n",
                    r.verdict, r.z, r.pid,
                    "(" + r.x + "," + r.y + "," + r.w + "," + r.h + ")",
                    r.pctOfMonitor + "%",
                    r.overlapsFloor ? "YES" : "-",
                    trunc(r.cls, 22),
                    r.hiddenByFront ? trunc(r.hiddenBy, 15) : "-",
                    trunc(r.title, 50));
        }
        System.out.println("-".repeat(160));
        System.out.println("CARVE windows (after hidden-behind suppression): " + carvers
                + "   |   overlapping floor band: " + floorCarvers
                + "   |   suppressed as HIDDEN behind a front float-on-top window: " + hiddenCarvers);
        if (hiddenCarvers > 0) {
            System.out.println();
            System.out.println(">>> The skip:hidden-behind rows were carving pets in a region the user");
            System.out.println(">>> actually sees a maximised/work-area window (pets float on top there).");
            System.out.println(">>> The engine now suppresses those (Win32.isHiddenBehindFrontBlocker).");
        } else if (floorCarvers > 0) {
            System.out.println();
            System.out.println(">>> The CARVE rows flagged FLOOR=YES are what cut off your pets.");
        }
    }

    /** TRUE iff window {@code f} (physical px) covers essentially all of window
     *  {@code c} — within a small margin on every edge. */
    private static boolean coversRect(Row f, Row c) {
        final int M = 2;
        return f.x <= c.x + M && f.y <= c.y + M
                && f.x + f.w >= c.x + c.w - M && f.y + f.h >= c.y + c.h - M;
    }

    @SuppressWarnings("unused") // upcall stub
    private static int enumProc(MemorySegment hwnd, long lparam) {
        try {
            if ((int) IS_WINDOW_VISIBLE.invoke(hwnd) == 0) {
                return 1;
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment rect = a.allocate(MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_INT));
                if ((int) GET_WINDOW_RECT.invoke(hwnd, rect) == 0) {
                    return 1;
                }
                int l = rect.getAtIndex(ValueLayout.JAVA_INT, 0);
                int t = rect.getAtIndex(ValueLayout.JAVA_INT, 1);
                int r = rect.getAtIndex(ValueLayout.JAVA_INT, 2);
                int b = rect.getAtIndex(ValueLayout.JAVA_INT, 3);
                int w = r - l;
                int h = b - t;

                String cls   = readString(a, GET_CLASS_NAME, hwnd, 256);
                String title = readString(a, GET_WINDOW_TEXT, hwnd, 512);

                MemorySegment pidBuf = a.allocate(ValueLayout.JAVA_INT);
                GET_WINDOW_THREAD_PROCESS_ID.invoke(hwnd, pidBuf);
                int pid = pidBuf.get(ValueLayout.JAVA_INT, 0);
                String image = processImage(pid);

                long ex = (long) GET_WINDOW_LONG_PTR.invoke(hwnd, GWL_EXSTYLE);
                boolean topmost   = (ex & WS_EX_TOPMOST) != 0;
                boolean iconic    = ((int) IS_ICONIC.invoke(hwnd)) != 0;
                boolean maximized = ((int) IS_ZOOMED.invoke(hwnd)) != 0;
                boolean cloaked   = isCloaked(a, hwnd);

                // Monitor this window sits on (physical px), like each per-monitor stage.
                // mon = {monL,monT,monR,monB, workL,workT,workR,workB}
                int[] mon = monitorRect(a, hwnd);

                Row row = new Row();
                row.hwnd = hwnd.address();
                row.pid = pid; row.x = l; row.y = t; row.w = w; row.h = h;
                row.cls = cls; row.title = title; row.image = image; row.topmost = topmost;

                // --- replay holeEnumProc's decision ---------------------------
                boolean offMonitor = mon == null
                        || Math.min(r, mon[2]) <= Math.max(l, mon[0])
                        || Math.min(b, mon[3]) <= Math.max(t, mon[1]);
                boolean shellBar = "Shell_TrayWnd".equals(cls) || "Shell_SecondaryTrayWnd".equals(cls);
                boolean coversMon = mon != null
                        && l <= mon[0] + COVERS_MARGIN && t <= mon[1] + COVERS_MARGIN
                        && r >= mon[2] - COVERS_MARGIN && b >= mon[3] - COVERS_MARGIN;
                // Mirrors Win32.coversWorkArea: fills the monitor's work area
                // (monitor minus taskbar), bottom edge matched loosely.
                boolean coversWork = false;
                if (mon != null) {
                    int bottomSlack = Math.max(8, (mon[7] - mon[5]) / 12);
                    coversWork = l <= mon[4] + COVERS_MARGIN && t <= mon[5] + COVERS_MARGIN
                            && r >= mon[6] - COVERS_MARGIN && b >= mon[7] - bottomSlack;
                }
                boolean self = pid == SELF_PID;

                String verdict;
                boolean carves = false;
                if (iconic) {
                    verdict = "skip:minimized";
                } else if (offMonitor) {
                    verdict = "skip:off-monitor";
                } else if (self) {
                    verdict = "skip:own(diag)";
                } else if (shellBar) {
                    verdict = "skip:taskbar";
                } else if (maximized) {
                    verdict = "skip:maximized";
                } else if (coversMon) {
                    verdict = "skip:covers-monitor";
                } else if (coversWork) {
                    verdict = "skip:covers-work-area";
                } else if (cloaked) {
                    verdict = "skip:cloaked";
                } else {
                    verdict = "CARVE";
                    carves = true;
                }
                row.verdict = verdict;
                row.carves = carves;
                row.mon = mon;
                row.floatOnTop = maximized || coversMon || coversWork;

                if (mon != null) {
                    long monArea = (long) (mon[2] - mon[0]) * (mon[3] - mon[1]);
                    row.pctOfMonitor = monArea > 0
                            ? (int) Math.round(100.0 * w * h / monArea) : 0;
                    int floorTop = (int) (mon[3] - (mon[3] - mon[1]) * FLOOR_BAND);
                    row.overlapsFloor = carves && b > floorTop && r > mon[0] && l < mon[2];
                }
                row.z = ROWS.size();
                ROWS.add(row);
            }
        } catch (Throwable t) {
            // keep enumerating
        }
        return 1;
    }

    /** {@code {monL,monT,monR,monB, workL,workT,workR,workB}} (physical px) of
     *  the monitor nearest the window, or null. The work-area rect excludes the
     *  taskbar. */
    private static int[] monitorRect(Arena a, MemorySegment hwnd) {
        try {
            MemorySegment hMon = (MemorySegment) MONITOR_FROM_WINDOW.invoke(hwnd, MONITOR_DEFAULTTONEAREST);
            if (hMon == null || hMon.address() == 0) {
                return null;
            }
            MemorySegment mi = a.allocate(40);
            mi.set(ValueLayout.JAVA_INT, 0, 40); // cbSize
            if ((int) GET_MONITOR_INFO.invoke(hMon, mi) == 0) {
                return null;
            }
            // MONITORINFO: cbSize(0), rcMonitor(4..19), rcWork(20..35), dwFlags(36)
            return new int[] {
                    mi.get(ValueLayout.JAVA_INT, 4),
                    mi.get(ValueLayout.JAVA_INT, 8),
                    mi.get(ValueLayout.JAVA_INT, 12),
                    mi.get(ValueLayout.JAVA_INT, 16),
                    mi.get(ValueLayout.JAVA_INT, 20),
                    mi.get(ValueLayout.JAVA_INT, 24),
                    mi.get(ValueLayout.JAVA_INT, 28),
                    mi.get(ValueLayout.JAVA_INT, 32),
            };
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isCloaked(Arena a, MemorySegment hwnd) {
        try {
            MemorySegment out = a.allocate(ValueLayout.JAVA_INT);
            out.set(ValueLayout.JAVA_INT, 0, 0);
            int hr = (int) DWM_GET_WINDOW_ATTRIBUTE.invoke(hwnd, DWMWA_CLOAKED, out, 4);
            return hr == 0 && out.get(ValueLayout.JAVA_INT, 0) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readString(Arena a, MethodHandle fn, MemorySegment hwnd, int cap) throws Throwable {
        MemorySegment buf = a.allocate(cap);
        int n = (int) fn.invoke(hwnd, buf, cap);
        return n > 0 ? buf.reinterpret(n + 1).getString(0, StandardCharsets.US_ASCII) : "";
    }

    private static String processImage(int pid) {
        try {
            MemorySegment h = (MemorySegment) OPEN_PROCESS.invoke(
                    PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if (h == null || h.address() == 0) {
                return null;
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment buf = a.allocate(1024);
                MemorySegment sizeRef = a.allocate(ValueLayout.JAVA_INT);
                sizeRef.set(ValueLayout.JAVA_INT, 0, 1024);
                int ok = (int) QUERY_FULL_PROCESS_IMAGE_NAME.invoke(h, 0, buf, sizeRef);
                CLOSE_HANDLE.invoke(h);
                if (ok == 0) {
                    return null;
                }
                int len = sizeRef.get(ValueLayout.JAVA_INT, 0);
                return buf.reinterpret(len + 1).getString(0, StandardCharsets.US_ASCII);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String basename(String path) {
        if (path == null || path.isEmpty()) {
            return "?";
        }
        int i = path.lastIndexOf('\\');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static String trunc(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static final class Row {
        long hwnd;
        int pid;
        int x, y, w, h;
        int z; // enumeration order: 0 = frontmost (EnumWindows is front-to-back)
        int[] mon; // {monL,monT,monR,monB, workL,workT,workR,workB} or null
        int pctOfMonitor;
        boolean overlapsFloor;
        boolean carves;
        boolean floatOnTop; // maximised / covers-monitor / covers-work-area (pets float on top)
        boolean topmost;
        boolean hiddenByFront; // a floatOnTop window IN FRONT fully covers this carver
        String hiddenBy;
        String verdict;
        String cls;
        String title;
        String image;
    }

    private DumpOccluders() {}
}
