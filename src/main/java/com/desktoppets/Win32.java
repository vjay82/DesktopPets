package com.desktoppets;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal Win32 bindings used by the behavior engine to find the foreground
 * window and Explorer taskbar(s) (including secondary monitors).
 *
 * <p>Implemented with the stable Foreign Function &amp; Memory API (Java 22+).
 * On non-Windows platforms every method short-circuits to {@code null}.
 */
public final class Win32 {

    /** Auto-hide taskbars protrude only a few px; treat anything under this as hidden. */
    private static final int AUTOHIDE_THRESHOLD_PX = 5;

    /** Minimum size of a window to count as a potential perch. Filters out
     *  tray balloons, autohide bars, etc. */
    private static final int MIN_PERCH_WIDTH_PX = 80;
    private static final int MIN_PERCH_HEIGHT_PX = 20;

    /** Pet JFrames are always square and capped at 256 px. Any topmost
     *  window matching that shape is overwhelmingly likely to be one of
     *  our own pets, so we skip them — the alternative is pets trying to
     *  perch on each other (or themselves). */
    private static final int MAX_PET_FRAME_PX = 280;

    /** {@code GetWindowLongPtr} index for the extended style. */
    private static final int GWL_EXSTYLE = -20;
    /** Extended style bit indicating "always-on-top". */
    private static final long WS_EX_TOPMOST = 0x00000008L;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    /**
     * Per-process DPI scale factor used to convert Win32 <b>physical</b>-pixel
     * rectangles (returned by {@code GetWindowRect}) into the <b>logical</b>
     * pixels Swing / AWT use for {@code frame.setLocation}. On 4K @ 200%
     * scaling, physical y=1000 must become logical y=500 — otherwise pets
     * that try to perch on a window end up at twice the window's actual y.
     *
     * <p>We use the primary monitor's scale uniformly; on multi-monitor setups
     * with mixed DPI the secondary monitors are slightly off, but the common
     * case (single scale across all monitors) is correct.
     */
    private static final double DPI_SCALE_X;
    private static final double DPI_SCALE_Y;
    static {
        double sx = 1.0;
        double sy = 1.0;
        if (WINDOWS) {
            try {
                AffineTransform tx = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice()
                        .getDefaultConfiguration()
                        .getDefaultTransform();
                sx = tx.getScaleX();
                sy = tx.getScaleY();
                if (sx <= 0) sx = 1.0;
                if (sy <= 0) sy = 1.0;
            } catch (Throwable t) {
                // headless / GraphicsEnvironment unavailable — keep 1.0
            }
        }
        DPI_SCALE_X = sx;
        DPI_SCALE_Y = sy;
    }

    private static final Arena ARENA = WINDOWS ? Arena.ofShared() : null;
    private static final SymbolLookup USER32 =
            WINDOWS ? SymbolLookup.libraryLookup("user32", ARENA) : null;
    private static final Linker LINKER = WINDOWS ? Linker.nativeLinker() : null;

    private static final MethodHandle GET_FOREGROUND_WINDOW = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("GetForegroundWindow").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS))
            : null;

    private static final MethodHandle GET_WINDOW_RECT = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("GetWindowRect").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            : null;

    private static final MethodHandle FIND_WINDOW = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("FindWindowA").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            : null;

    private static final MethodHandle FIND_WINDOW_EX = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("FindWindowExA").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            : null;

    private static final MethodHandle IS_WINDOW_VISIBLE = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("IsWindowVisible").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
            : null;

    /** {@code IsZoomed} — TRUE iff the window is maximised. A maximised window
     *  fills the work area down to the taskbar (i.e. over the strip the pets
     *  stand on), so carving it would erase every pet; such windows are skipped
     *  by the occlusion pass (the pets float on top of them instead). */
    private static final MethodHandle IS_ZOOMED = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("IsZoomed").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
            : null;

    private static final MethodHandle ENUM_WINDOWS = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("EnumWindows").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
            : null;

    // 64-bit Windows: GetWindowLongPtrA is the canonical export. (32-bit
    // would need GetWindowLongA, but Java is effectively 64-bit on modern boxes.)
    private static final MethodHandle GET_WINDOW_LONG_PTR = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("GetWindowLongPtrA").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
            : null;

    private static final MethodHandle SET_WINDOW_POS = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("SetWindowPos").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT))
            : null;

    /** SetWindowLongPtrA (64-bit) — used to OR in WS_EX_LAYERED |
     *  WS_EX_TRANSPARENT so the stage window is click-through. */
    private static final MethodHandle SET_WINDOW_LONG_PTR = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("SetWindowLongPtrA").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG))
            : null;

    /** GetAsyncKeyState — used to detect left mouse button press edges for
     *  pet click dispatch (the stage window is click-through and can't
     *  receive mouse events of its own). */
    private static final MethodHandle GET_ASYNC_KEY_STATE = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("GetAsyncKeyState").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT))
            : null;

    /** GetWindowThreadProcessId — writes the owning process id of a window
     *  to an out-param (returns the thread id, which we ignore). Used by
     *  {@link #isOwnProcessWindow} to drop every window our own process owns
     *  from the perch / foreground enumeration. */
    private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("GetWindowThreadProcessId").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            : null;

    /** gdi32 region primitives (legacy SetWindowRgn path, retained). The live
     *  occlusion now clears rectangles at paint time — see
     *  {@link #collectOccluders}. */
    private static final SymbolLookup GDI32 =
            WINDOWS ? SymbolLookup.libraryLookup("gdi32", ARENA) : null;
    /** dwmapi — optional; only used to skip "cloaked" windows (minimised
     *  UWP apps, windows parked on another virtual desktop) so they don't
     *  punch a phantom hole in the canvas. Absent on very old Windows. */
    private static final SymbolLookup DWMAPI = loadOptional("dwmapi");

    private static SymbolLookup loadOptional(String lib) {
        if (!WINDOWS) {
            return null;
        }
        try {
            return SymbolLookup.libraryLookup(lib, ARENA);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final MethodHandle CREATE_RECT_RGN = WINDOWS
            ? LINKER.downcallHandle(
                    GDI32.find("CreateRectRgn").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
            : null;

    private static final MethodHandle COMBINE_RGN = WINDOWS
            ? LINKER.downcallHandle(
                    GDI32.find("CombineRgn").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
            : null;

    private static final MethodHandle DELETE_OBJECT = WINDOWS
            ? LINKER.downcallHandle(
                    GDI32.find("DeleteObject").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
            : null;

    private static final MethodHandle SET_WINDOW_RGN = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("SetWindowRgn").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
            : null;

    private static final MethodHandle GET_CLASS_NAME = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("GetClassNameA").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
            : null;

    private static final MethodHandle DWM_GET_WINDOW_ATTRIBUTE =
            (WINDOWS && DWMAPI != null)
            ? DWMAPI.find("DwmGetWindowAttribute")
                    .map(sym -> LINKER.downcallHandle(sym,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT)))
                    .orElse(null)
            : null;

    /** {@code RGN_DIFF} for {@code CombineRgn}: subtract region 2 from region 1. */
    private static final int RGN_DIFF = 4;
    /** {@code DWMWA_CLOAKED} index for {@code DwmGetWindowAttribute}. */
    private static final int DWMWA_CLOAKED = 14;

    /** WS_EX_LAYERED — needed for any per-pixel-alpha / transparent window. */
    private static final long WS_EX_LAYERED     = 0x00080000L;
    /** WS_EX_TRANSPARENT — clicks/mouse events pass through to whatever
     *  is underneath. Combined with WS_EX_LAYERED for true click-through. */
    private static final long WS_EX_TRANSPARENT = 0x00000020L;
    /** WS_EX_NOACTIVATE — window never takes activation/focus on click,
     *  so even if click-through is somehow defeated we don't steal focus. */
    private static final long WS_EX_NOACTIVATE  = 0x08000000L;
    /** VK_LBUTTON virtual key code for {@code GetAsyncKeyState}. */
    public static final int VK_LBUTTON = 0x01;

    private static final MethodHandle ENUM_PROC_HANDLE;
    static {
        if (WINDOWS) {
            try {
                ENUM_PROC_HANDLE = MethodHandles.lookup().findStatic(Win32.class, "enumProc",
                        MethodType.methodType(int.class, MemorySegment.class, long.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        } else {
            ENUM_PROC_HANDLE = null;
        }
    }

    private static final MemorySegment ENUM_PROC_STUB = WINDOWS
            ? LINKER.upcallStub(ENUM_PROC_HANDLE,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                    ARENA)
            : null;

    private static final MethodHandle HOLE_PROC_HANDLE;
    static {
        if (WINDOWS) {
            try {
                HOLE_PROC_HANDLE = MethodHandles.lookup().findStatic(Win32.class, "holeEnumProc",
                        MethodType.methodType(int.class, MemorySegment.class, long.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        } else {
            HOLE_PROC_HANDLE = null;
        }
    }

    private static final MemorySegment HOLE_PROC_STUB = WINDOWS
            ? LINKER.upcallStub(HOLE_PROC_HANDLE,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                    ARENA)
            : null;

    /** Per-call collector for the {@link #ENUM_PROC_STUB} callback. Behavior
     *  engines tick ~5×/sec, but the World cache (150 ms) means concurrent
     *  callers should be rare; we still guard with a ThreadLocal. */
    private static final ThreadLocal<TopmostCollector> COLLECTOR =
            ThreadLocal.withInitial(TopmostCollector::new);

    /** Tracks whether we've already logged a per-window enumProc failure;
     *  otherwise a broken handle would silently disable the entire perch
     *  system every tick with zero diagnostic output. */
    private static final AtomicBoolean ENUM_PROC_FAILURE_LOGGED = new AtomicBoolean(false);
    /** Same idea for top-level {@code EnumWindows} failures (which would
     *  return an empty perch list every tick). */
    private static final AtomicBoolean ENUM_WINDOWS_FAILURE_LOGGED = new AtomicBoolean(false);

    /**
     * PID of our own process. Any top-level window owned by this PID is one
     * of OURS — a {@link Stage} canvas, the {@link Ball}, the {@link Tree}
     * scenery, a settings dialog, the tray owner frame — and must never be
     * reported as a perch surface by {@link #topmostWindowRects} nor as the
     * foreground window by {@link #foregroundWindowRect()}. Otherwise the
     * full-monitor stage window (in particular) is seen as a window covering
     * the entire screen: it occludes every real surface beneath the pet and
     * drops the pet to the desktop floor behind the taskbar — the regression
     * the "one window" refactor introduced. Filtering by PID is robust to
     * monitor count, DPI, window size and creation timing, unlike the old
     * size-based heuristics, which only caught the square per-pet frames and
     * let the full-monitor stage through on non-primary monitors.
     */
    private static final long OUR_PID = ProcessHandle.current().pid();

    /** Reusable per-thread out-parameter (a single {@code DWORD}) for
     *  {@link #GET_WINDOW_THREAD_PROCESS_ID}, so {@link #isOwnProcessWindow}
     *  doesn't allocate on every enumerated window. */
    private static final ThreadLocal<MemorySegment> PID_OUT = WINDOWS
            ? ThreadLocal.withInitial(() -> ARENA.allocate(ValueLayout.JAVA_INT))
            : null;

    /**
     * Secondary guard: explicit HWNDs of our own {@link Stage} canvases,
     * registered at creation. Redundant with the {@link #OUR_PID} check
     * above, but kept as belt-and-suspenders for the one window whose leak
     * causes the worst symptom (a pet dropped behind the taskbar), in case
     * {@code GetWindowThreadProcessId} ever fails for a given window. Stage
     * windows live for the whole app lifetime, so their HWNDs are never
     * recycled and never need removing.
     */
    private static final Set<Long> OWN_HWNDS = ConcurrentHashMap.newKeySet();

    private Win32() {
    }

    public static boolean isAvailable() {
        return WINDOWS;
    }

    /**
     * Register {@code hwnd} as one of our own windows so it is excluded from
     * {@link #topmostWindowRects} (and therefore from pet floor / perch
     * detection). Called by {@link Stage} for each per-monitor canvas it
     * creates. No-op for {@code 0} or off Windows.
     */
    public static void registerOwnWindow(long hwnd) {
        if (hwnd != 0L) {
            OWN_HWNDS.add(hwnd);
        }
    }

    /**
     * {@code true} if {@code hwnd} belongs to our own process (see
     * {@link #OUR_PID}). Used to exclude every window we create from the
     * perch / floor finder and the foreground detector. Best-effort:
     * returns {@code false} on any FFM error or off Windows.
     */
    private static boolean isOwnProcessWindow(MemorySegment hwnd) {
        if (!WINDOWS || hwnd == null || hwnd.address() == 0) {
            return false;
        }
        try {
            MemorySegment out = PID_OUT.get();
            out.set(ValueLayout.JAVA_INT, 0L, 0);
            GET_WINDOW_THREAD_PROCESS_ID.invoke(hwnd, out);
            long pid = out.get(ValueLayout.JAVA_INT, 0L) & 0xFFFFFFFFL;
            return pid == OUR_PID;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Bounding rectangles of every visible top-level window in <b>z-order,
     * top-first</b> (the order Win32 {@code EnumWindows} enumerates), minus
     * the obvious noise (tiny tray popups, our own pet frames, minimised
     * off-screen stubs, and anything that fills the entire virtual desktop
     * so a fullscreen video doesn't trap pets). Pets may walk on top of any
     * of these windows — always-on-top, normal, or behind.
     */
    public static List<Rectangle> topmostWindowRects(int screenW, int screenH) {
        if (!WINDOWS) {
            return List.of();
        }
        TopmostCollector c = COLLECTOR.get();
        c.reset(screenW, screenH);
        try {
            ENUM_WINDOWS.invoke(ENUM_PROC_STUB, 0L);
        } catch (Throwable t) {
            if (ENUM_WINDOWS_FAILURE_LOGGED.compareAndSet(false, true)) {
                Log.warn("win32", "EnumWindows failed (perch list will be empty): " + t);
            }
            return List.of();
        }
        List<Rectangle> out = c.list;
        c.list = new ArrayList<>(); // detach so the next call doesn't clobber
        return Collections.unmodifiableList(out);
    }

    @SuppressWarnings("unused") // called via upcall stub
    private static int enumProc(MemorySegment hwnd, long lparam) {
        TopmostCollector c = COLLECTOR.get();
        try {
            if (isOwnProcessWindow(hwnd) || OWN_HWNDS.contains(hwnd.address())) {
                return 1; // any window our own process owns — never a perch surface
            }
            int vis = (int) IS_WINDOW_VISIBLE.invoke(hwnd);
            if (vis == 0) {
                return 1;
            }
            Rectangle r = rectOf(hwnd);
            if (r == null) {
                return 1;
            }
            if (r.width < MIN_PERCH_WIDTH_PX || r.height < MIN_PERCH_HEIGHT_PX) {
                return 1; // tiny popups, autohide bars, etc.
            }
            if (r.width == r.height && r.width <= MAX_PET_FRAME_PX) {
                return 1; // square ≤ 256 px — almost certainly one of our pet frames
            }
            if (r.width >= c.screenW - 4 && r.height >= c.screenH - 4) {
                return 1; // fullscreen — don't try to perch on a video player
            }
            if (r.y + r.height <= 0 || r.y >= c.screenH) {
                return 1; // entirely off-screen vertically
            }
            // Minimised windows live at roughly (-32000, -32000); reject
            // anything parked deep in the negative-coordinate quadrant.
            if (r.x < -10000 || r.y < -10000) {
                return 1;
            }
            c.list.add(r);
        } catch (Throwable t) {
            if (ENUM_PROC_FAILURE_LOGGED.compareAndSet(false, true)) {
                Log.warn("win32", "enumProc per-window failure (continuing): " + t);
            }
            // continue enumeration on per-window errors
        }
        return 1; // continue enumeration
    }

    /** Bounding rectangle of the current foreground window (in screen coords), or null. */
    public static Rectangle foregroundWindowRect() {
        if (!WINDOWS) {
            return null;
        }
        try {
            MemorySegment hwnd = (MemorySegment) GET_FOREGROUND_WINDOW.invoke();
            if (isOwnProcessWindow(hwnd)) {
                // Our own stage / ball / tree / dialog is in front — pets must
                // not treat it as "the user switched to an app" (greet /
                // startle) nor reason about its full-monitor rect.
                return null;
            }
            return rectOf(hwnd);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Best visible Explorer taskbar — primary (Shell_TrayWnd) preferred,
     * otherwise the first acceptable secondary monitor's bar. Returns null
     * if everything is hidden (auto-hide), off-screen, or absent.
     */
    public static Rectangle taskbarRect() {
        if (!WINDOWS) {
            return null;
        }
        Rectangle primary = findTaskbar("Shell_TrayWnd");
        if (primary != null) {
            return primary;
        }
        return findSecondaryTaskbar();
    }

    /**
     * Every visible Explorer shell bar (primary taskbar + all per-monitor
     * secondary taskbars). Used to subtract bar areas from each monitor's
     * bounds so the pets walk on the desktop work area instead of behind a
     * taskbar. Identified by Win32 class name ({@code Shell_TrayWnd} /
     * {@code Shell_SecondaryTrayWnd}); those classes are owned by
     * {@code explorer.exe} by definition, no per-window PID lookup needed.
     */
    public static List<Rectangle> shellBarRects() {
        if (!WINDOWS) {
            return List.of();
        }
        List<Rectangle> out = new ArrayList<>(4);
        Rectangle primary = findTaskbar("Shell_TrayWnd");
        if (primary != null) {
            out.add(primary);
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment name = a.allocateFrom("Shell_SecondaryTrayWnd");
            MemorySegment hwnd = MemorySegment.NULL;
            for (int i = 0; i < 16; i++) { // bounded scan; users rarely have >16 monitors
                hwnd = (MemorySegment) FIND_WINDOW_EX.invoke(
                        MemorySegment.NULL, hwnd, name, MemorySegment.NULL);
                if (hwnd == null || hwnd.address() == 0) {
                    break;
                }
                Rectangle r = acceptable(rectOf(hwnd));
                if (r != null) {
                    out.add(r);
                }
            }
        } catch (Throwable t) {
            if (SHELL_BAR_FAILURE_LOGGED.compareAndSet(false, true)) {
                Log.warn("win32", "Shell_SecondaryTrayWnd enumeration failed: " + t);
            }
            // Return whatever we have so far so the primary taskbar is
            // still honoured even if secondary lookup blew up.
        }
        return Collections.unmodifiableList(out);
    }

    private static final AtomicBoolean SHELL_BAR_FAILURE_LOGGED = new AtomicBoolean(false);

    private static Rectangle findTaskbar(String className) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment name = a.allocateFrom(className);
            MemorySegment hwnd =
                    (MemorySegment) FIND_WINDOW.invoke(name, MemorySegment.NULL);
            return acceptable(rectOf(hwnd));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Rectangle findSecondaryTaskbar() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment name = a.allocateFrom("Shell_SecondaryTrayWnd");
            MemorySegment hwnd = MemorySegment.NULL;
            for (int i = 0; i < 8; i++) { // bounded to avoid runaway loops
                hwnd = (MemorySegment) FIND_WINDOW_EX.invoke(
                        MemorySegment.NULL, hwnd, name, MemorySegment.NULL);
                if (hwnd == null || hwnd.address() == 0) {
                    return null;
                }
                Rectangle r = acceptable(rectOf(hwnd));
                if (r != null) {
                    return r;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Rectangle acceptable(Rectangle r) {
        if (r == null) {
            return null;
        }
        if (r.height < AUTOHIDE_THRESHOLD_PX || r.width < AUTOHIDE_THRESHOLD_PX) {
            return null;
        }
        return r;
    }

    /**
     * Look up a top-level window's HWND by exact title via {@code FindWindowA}.
     * Returns the raw HWND as a {@code long}, or {@code 0} if not found / not
     * on Windows. Used to obtain a stable HWND for a Swing JFrame whose title
     * we control (Swing doesn't expose the native handle directly).
     */
    public static long findWindowByTitle(String title) {
        if (!WINDOWS || title == null) {
            return 0L;
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment t = a.allocateFrom(title);
            MemorySegment hwnd =
                    (MemorySegment) FIND_WINDOW.invoke(MemorySegment.NULL, t);
            return hwnd == null ? 0L : hwnd.address();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Re-assert {@code HWND_TOPMOST} for the given HWND via {@code SetWindowPos}
     * with {@code SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE}. Idempotent and
     * cheap; safe to call every behavior tick to keep pet windows from being
     * demoted out of the topmost band by other apps.
     *
     * <p>Important: we first check {@code WS_EX_TOPMOST} on the window and
     * skip the {@code SetWindowPos} call if it's already set. Calling
     * {@code SetWindowPos(HWND_TOPMOST)} on a window that's already topmost
     * still re-orders it to the FRONT of the topmost band — so two pet
     * frames whose paths overlap will keep flipping each other to the back
     * each tick, producing a visible flicker. Only the rare actual demotion
     * (from another app) needs the SetWindowPos call.
     */
    public static void reassertTopmost(long hwnd) {
        if (!WINDOWS || hwnd == 0L) {
            return;
        }
        try {
            MemorySegment h = MemorySegment.ofAddress(hwnd);
            long exStyle = (long) GET_WINDOW_LONG_PTR.invoke(h, GWL_EXSTYLE);
            if ((exStyle & WS_EX_TOPMOST) != 0L) {
                // Already topmost — don't re-front it, that's what causes
                // the per-tick z-order war between overlapping pets.
                return;
            }
            // HWND_TOPMOST = -1 ; flags = SWP_NOMOVE(0x2)|SWP_NOSIZE(0x1)|SWP_NOACTIVATE(0x10) = 0x13
            SET_WINDOW_POS.invoke(h, MemorySegment.ofAddress(-1L), 0, 0, 0, 0, 0x13);
        } catch (Throwable t) {
            // best-effort; do not spam logs
        }
    }

    /**
     * Put {@code stageHwnd} at the FRONT of the topmost band:
     * {@code SetWindowPos(hwnd, HWND_TOPMOST, ..., SWP_NOMOVE | SWP_NOSIZE |
     * SWP_NOACTIVATE)}. The pet canvas then floats above every ordinary
     * (non-topmost) application window, so the pets are visible on the
     * desktop and on the taskbar. Windows that are themselves "on top of
     * the shell bar" (always-on-top apps, the Start menu, tray fly-outs)
     * are NOT covered by raising the canvas — instead they are cleared out
     * of the canvas per-rectangle at paint time (see {@link #collectOccluders}),
     * so the pets appear to hide behind them only where each window actually is.
     */
    public static void placeAtShellZOrder(long stageHwnd) {
        if (!WINDOWS || stageHwnd == 0L) {
            return;
        }
        try {
            MemorySegment stage = MemorySegment.ofAddress(stageHwnd);
            // HWND_TOPMOST = -1 ; flags = SWP_NOMOVE(0x2)|SWP_NOSIZE(0x1)|SWP_NOACTIVATE(0x10) = 0x13
            SET_WINDOW_POS.invoke(stage, MemorySegment.ofAddress(-1L), 0, 0, 0, 0, 0x13);
        } catch (Throwable t) {
            // best-effort; do not spam logs
        }
    }

    /**
     * Position {@code stageHwnd} immediately BELOW the Explorer shell bar
     * (taskbar) on the monitor whose LOGICAL bounds are {@code monitor},
     * while keeping it inside the topmost band (above ordinary application
     * windows). This lets the taskbar, Start menu and tray fly-outs — and
     * any other window that gets in front of the taskbar (a full-screen app
     * or an always-on-top window) — draw OVER the pets, instead of the pets
     * covering them. Ordinary (non-topmost) windows stay below the pets.
     *
     * <p>Implementation: {@code SetWindowPos(stage, taskbar, ...,
     * SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE)} inserts the stage directly
     * behind a window that is itself topmost (the taskbar). Per the Win32
     * Z-order rules a window placed behind another <i>topmost</i> window
     * stays topmost, so the stage keeps floating above normal windows but
     * gives up its "in front of the taskbar" slot. When no taskbar is found
     * on that monitor (e.g. a secondary display configured without one) the
     * stage is re-asserted to {@code HWND_TOPMOST} so it still floats above
     * normal windows there.
     */
    public static void placeBelowTaskbar(long stageHwnd, Rectangle monitor) {
        if (!WINDOWS || stageHwnd == 0L) {
            return;
        }
        try {
            long taskbar = shellBarHwndOnMonitor(monitor);
            MemorySegment stage = MemorySegment.ofAddress(stageHwnd);
            // hWndInsertAfter = the taskbar (sit just behind it) or
            // HWND_TOPMOST(-1) when this monitor has no shell bar.
            MemorySegment after = MemorySegment.ofAddress(taskbar != 0L ? taskbar : -1L);
            // flags = SWP_NOMOVE(0x2)|SWP_NOSIZE(0x1)|SWP_NOACTIVATE(0x10) = 0x13
            SET_WINDOW_POS.invoke(stage, after, 0, 0, 0, 0, 0x13);
        } catch (Throwable t) {
            // best-effort; do not spam logs
        }
    }

    /**
     * Native handle of the Explorer shell bar — primary
     * {@code Shell_TrayWnd} or a per-monitor {@code Shell_SecondaryTrayWnd}
     * — whose rectangle lies on the given LOGICAL {@code monitor} bounds,
     * or {@code 0} if none is found. Unlike {@link #taskbarRect()} this
     * keeps the native handle and does NOT reject auto-hidden bars: we
     * still want to sit below an auto-hide taskbar so it can slide out over
     * the pets.
     */
    private static long shellBarHwndOnMonitor(Rectangle monitor) {
        if (!WINDOWS) {
            return 0L;
        }
        try (Arena a = Arena.ofConfined()) {
            // Primary taskbar first.
            MemorySegment primaryName = a.allocateFrom("Shell_TrayWnd");
            MemorySegment primary =
                    (MemorySegment) FIND_WINDOW.invoke(primaryName, MemorySegment.NULL);
            long hit = barHwndIfOnMonitor(primary, monitor);
            if (hit != 0L) {
                return hit;
            }
            // Then the per-monitor secondary taskbars.
            MemorySegment secName = a.allocateFrom("Shell_SecondaryTrayWnd");
            MemorySegment hwnd = MemorySegment.NULL;
            for (int i = 0; i < 16; i++) { // bounded scan; >16 monitors is unheard of
                hwnd = (MemorySegment) FIND_WINDOW_EX.invoke(
                        MemorySegment.NULL, hwnd, secName, MemorySegment.NULL);
                if (hwnd == null || hwnd.address() == 0) {
                    break;
                }
                hit = barHwndIfOnMonitor(hwnd, monitor);
                if (hit != 0L) {
                    return hit;
                }
            }
        } catch (Throwable t) {
            // fall through to 0 (caller treats as "no taskbar on this monitor")
        }
        return 0L;
    }

    /**
     * Returns {@code hwnd.address()} when the window's (logical) rectangle
     * sits on {@code monitor}, else {@code 0}. A {@code null} monitor
     * matches any bar (permissive fallback).
     */
    private static long barHwndIfOnMonitor(MemorySegment hwnd, Rectangle monitor)
            throws Throwable {
        if (hwnd == null || hwnd.address() == 0) {
            return 0L;
        }
        Rectangle r = rectOf(hwnd); // physical -> logical
        if (r == null) {
            return 0L;
        }
        if (monitor == null
                || monitor.intersects(r)
                || monitor.contains(r.x + r.width / 2, r.y + r.height / 2)) {
            return hwnd.address();
        }
        return 0L;
    }

    /**
     * Turn the given top-level window into a click-through layered window:
     * OR {@code WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE} into
     * its extended style. The window remains visible but receives no mouse
     * events — every click/hover passes through to whatever is underneath.
     *
     * <p>Used by the {@link Stage} so the giant transparent canvas covering
     * the virtual desktop doesn't trap clicks meant for the user's
     * applications. Pet hover/click is then driven from the cursor poller
     * ({@link PetMouse}) instead of native window mouse events.
     */
    public static void makeClickThrough(long hwnd) {
        if (!WINDOWS || hwnd == 0L) {
            return;
        }
        try {
            MemorySegment h = MemorySegment.ofAddress(hwnd);
            long ex = (long) GET_WINDOW_LONG_PTR.invoke(h, GWL_EXSTYLE);
            long want = ex | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE;
            if (want != ex) {
                SET_WINDOW_LONG_PTR.invoke(h, GWL_EXSTYLE, want);
            }
        } catch (Throwable t) {
            Log.warn("win32", "makeClickThrough failed: " + t);
        }
    }

    /**
     * {@code true} iff the high-order bit of {@code GetAsyncKeyState(vk)} is
     * set, i.e. the key/mouse-button is currently down. Always returns
     * {@code false} on non-Windows or on FFM errors.
     */
    public static boolean isKeyDown(int vk) {
        if (!WINDOWS) {
            return false;
        }
        try {
            short s = (short) GET_ASYNC_KEY_STATE.invoke(vk);
            return (s & 0x8000) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- per-window occlusion (cut-outs) ----------------

    /** Stage-window-relative rectangles of the topmost windows to carve out
     *  of one stage, collected during a single {@link #HOLE_PROC_STUB}
     *  enumeration. */
    private static final class HoleCollector {
        long stageHwnd;
        int sLeft, sTop, sRight, sBottom; // stage rect, physical px
        final List<int[]> holes = new ArrayList<>(); // {l,t,r,b} in window coords

        void reset(long hwnd, int l, int t, int r, int b) {
            this.stageHwnd = hwnd;
            this.sLeft = l;
            this.sTop = t;
            this.sRight = r;
            this.sBottom = b;
            this.holes.clear();
        }
    }

    private static final ThreadLocal<HoleCollector> HOLE_COLLECTOR =
            ThreadLocal.withInitial(HoleCollector::new);

    /** Class-name scratch buffer for {@link #isShellBarClass}. */
    private static final ThreadLocal<MemorySegment> CLASS_BUF = WINDOWS
            ? ThreadLocal.withInitial(() -> ARENA.allocate(64))
            : null;

    /** 4-byte out-param for the {@code DWMWA_CLOAKED} query. */
    private static final ThreadLocal<MemorySegment> CLOAK_OUT = WINDOWS
            ? ThreadLocal.withInitial(() -> ARENA.allocate(ValueLayout.JAVA_INT))
            : null;

    private static final AtomicBoolean HOLE_PROC_FAILURE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean OCCLUSION_FAILURE_LOGGED = new AtomicBoolean(false);

    @SuppressWarnings("unused") // called via upcall stub
    private static int holeEnumProc(MemorySegment hwnd, long lparam) {
        HoleCollector c = HOLE_COLLECTOR.get();
        try {
            if (hwnd == null || hwnd.address() == 0 || hwnd.address() == c.stageHwnd) {
                return 1;
            }
            if ((int) IS_WINDOW_VISIBLE.invoke(hwnd) == 0) {
                return 1;
            }
            if (isOwnProcessWindow(hwnd) || OWN_HWNDS.contains(hwnd.address())) {
                return 1; // never carve out our own stage / ball / tree / dialog
            }
            if (isShellBarClass(hwnd)) {
                return 1; // the taskbar: pets stand ON it, never carve it out
            }
            if (isCloaked(hwnd)) {
                return 1; // minimised UWP / other virtual desktop — not really visible
            }
            int[] pr = physicalRectRaw(hwnd);
            if (pr == null) {
                return 1;
            }
            // Skip windows that fill (essentially) the whole monitor: a
            // maximised window, a full-screen / borderless window, or the
            // desktop wallpaper host (Progman / WorkerW). Carving any of those
            // would erase EVERY pet (the "all pets vanish behind maximised
            // VS Code" bug) — so instead the pets float on top of them. Only
            // partial, free-floating windows (a restored Notepad++, Explorer,
            // a dialog, the Start menu) occlude, giving the "hide behind the
            // window, but only where it actually is" effect. See the live
            // z-order diagnostic (diag/ZOrderDiag.java): the stage is always
            // front-most, so Z-order can't tell these apart — the window's
            // SIZE / maximised state is the reliable discriminator.
            if (isMaximized(hwnd) || coversWholeStage(pr, c)) {
                return 1;
            }
            int l = Math.max(pr[0], c.sLeft);
            int t = Math.max(pr[1], c.sTop);
            int r = Math.min(pr[2], c.sRight);
            int b = Math.min(pr[3], c.sBottom);
            if (r <= l || b <= t) {
                return 1; // doesn't overlap this monitor's stage
            }
            c.holes.add(new int[] { l - c.sLeft, t - c.sTop, r - c.sLeft, b - c.sTop });
        } catch (Throwable th) {
            if (HOLE_PROC_FAILURE_LOGGED.compareAndSet(false, true)) {
                Log.warn("win32", "holeEnumProc per-window failure (continuing): " + th);
            }
        }
        return 1;
    }

    /** TRUE iff the window is maximised ({@code IsZoomed}). */
    private static boolean isMaximized(MemorySegment hwnd) throws Throwable {
        return IS_ZOOMED != null && ((int) IS_ZOOMED.invoke(hwnd)) != 0;
    }

    /** TRUE iff {@code pr} (physical px) covers essentially the entire stage
     *  monitor (within a few px on every edge) — a full-screen / borderless
     *  window or the desktop wallpaper host. Such a window would hide all the
     *  pets, so it must not be carved. */
    private static boolean coversWholeStage(int[] pr, HoleCollector c) {
        final int M = 2;
        return pr[0] <= c.sLeft + M && pr[1] <= c.sTop + M
                && pr[2] >= c.sRight - M && pr[3] >= c.sBottom - M;
    }

    private static boolean isCloaked(MemorySegment hwnd) {
        if (DWM_GET_WINDOW_ATTRIBUTE == null) {
            return false;
        }
        try {
            MemorySegment out = CLOAK_OUT.get();
            out.set(ValueLayout.JAVA_INT, 0L, 0);
            int hr = (int) DWM_GET_WINDOW_ATTRIBUTE.invoke(hwnd, DWMWA_CLOAKED, out, 4);
            return hr == 0 && out.get(ValueLayout.JAVA_INT, 0L) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isShellBarClass(MemorySegment hwnd) {
        try {
            MemorySegment buf = CLASS_BUF.get();
            int n = (int) GET_CLASS_NAME.invoke(hwnd, buf, 64);
            if (n <= 0) {
                return false;
            }
            String cls = buf.getString(0);
            return "Shell_TrayWnd".equals(cls) || "Shell_SecondaryTrayWnd".equals(cls);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Raw {@code GetWindowRect} (physical px) as {@code {left,top,right,bottom}}, or null. */
    private static int[] physicalRectRaw(MemorySegment hwnd) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment rect = a.allocate(16);
            int ok = (int) GET_WINDOW_RECT.invoke(hwnd, rect);
            if (ok == 0) {
                return null;
            }
            return new int[] {
                    rect.get(ValueLayout.JAVA_INT, 0),
                    rect.get(ValueLayout.JAVA_INT, 4),
                    rect.get(ValueLayout.JAVA_INT, 8),
                    rect.get(ValueLayout.JAVA_INT, 12),
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /** Immutable empty result shared by {@link #collectOccluders}. */
    private static final int[][] EMPTY_RECTS = new int[0][];

    /**
     * Collect the rectangles of every free-floating window that overlaps the
     * given stage — i.e. the windows the pets should appear to hide behind.
     * Returned rectangles are in <b>logical</b> pixels relative to the stage's
     * top-left corner, ready to be cleared straight out of the translucent stage
     * canvas while painting. Returns an empty array when nothing occludes the
     * stage (or off Windows).
     *
     * <p><b>Which windows occlude.</b> Not the Z-order, and not the
     * {@code WS_EX_TOPMOST} flag: the pet stage is kept front-most (topmost), so
     * by Z-order it is always in front of every ordinary window and nothing
     * would ever occlude (and the taskbar's own Z-order is unreliable — Explorer
     * clears its always-on-top flag for full-screen / maximised apps, see
     * {@code SHAppBarMessage ABM_GETSTATE}). Instead a window occludes iff it is
     * a normal, <b>partial</b> window: visible, not ours, not the taskbar, not
     * cloaked, and — critically — NOT maximised and NOT covering the whole
     * monitor. Maximised / full-screen windows and the desktop wallpaper host
     * are skipped because carving them would erase every pet (that was the "all
     * pets vanish when VS Code is focused" bug); the pets float on top of those
     * instead. The discriminator was verified against a live window dump — see
     * {@code diag/ZOrderDiag.java}.
     *
     * <p>We clear pixels at paint time rather than calling {@code SetWindowRgn}
     * because the stage is a per-pixel-translucent (layered) window: its alpha
     * surface is uploaded via {@code UpdateLayeredWindow} and a window region is
     * not honoured reliably, whereas clearing the canvas alpha always is. The
     * physical rectangles are converted to logical pixels here (rounding
     * outward) so the caller never deals with DPI.
     */
    public static int[][] collectOccluders(long stageHwnd) {
        if (!WINDOWS || stageHwnd == 0L) {
            return EMPTY_RECTS;
        }
        try {
            int[] sr = physicalRectRaw(MemorySegment.ofAddress(stageHwnd));
            if (sr == null) {
                return EMPTY_RECTS;
            }
            HoleCollector c = HOLE_COLLECTOR.get();
            c.reset(stageHwnd, sr[0], sr[1], sr[2], sr[3]);
            ENUM_WINDOWS.invoke(HOLE_PROC_STUB, 0L);

            List<int[]> holes = c.holes;
            if (holes.isEmpty()) {
                return EMPTY_RECTS;
            }
            int[][] out = new int[holes.size()][];
            for (int i = 0; i < holes.size(); i++) {
                int[] hr = holes.get(i); // {left,top,right,bottom} physical, stage-relative
                int x = (int) Math.floor(hr[0] / DPI_SCALE_X);
                int y = (int) Math.floor(hr[1] / DPI_SCALE_Y);
                int x2 = (int) Math.ceil(hr[2] / DPI_SCALE_X);
                int y2 = (int) Math.ceil(hr[3] / DPI_SCALE_Y);
                out[i] = new int[] { x, y, x2 - x, y2 - y };
            }
            return out;
        } catch (Throwable t) {
            if (OCCLUSION_FAILURE_LOGGED.compareAndSet(false, true)) {
                Log.warn("win32", "collectOccluders failed: " + t);
            }
            return EMPTY_RECTS;
        }
    }

    private static Rectangle rectOf(MemorySegment hwnd) throws Throwable {
        if (hwnd == null || hwnd.address() == 0) {
            return null;
        }
        int visible = (int) IS_WINDOW_VISIBLE.invoke(hwnd);
        if (visible == 0) {
            return null;
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment rect = a.allocate(16); // RECT { LONG left, top, right, bottom }
            int ok = (int) GET_WINDOW_RECT.invoke(hwnd, rect);
            if (ok == 0) {
                return null;
            }
            int left = rect.get(ValueLayout.JAVA_INT, 0);
            int top = rect.get(ValueLayout.JAVA_INT, 4);
            int right = rect.get(ValueLayout.JAVA_INT, 8);
            int bottom = rect.get(ValueLayout.JAVA_INT, 12);
            return toLogical(new Rectangle(left, top, right - left, bottom - top));
        }
    }

    /**
     * Convert a physical-pixel rectangle (as returned by {@code GetWindowRect})
     * into Swing/AWT logical-pixel coordinates. On multi-monitor setups with
     * mixed DPI we look up which monitor physically contains the rectangle's
     * top-left and use that monitor's scale + logical origin so coordinates
     * map back into the correct AWT screen. Falls back to the primary
     * monitor's scale if no enclosing monitor is found.
     */
    private static Rectangle toLogical(Rectangle phy) {
        if (phy == null) {
            return null;
        }
        List<Dpi.MonitorScale> mons = Dpi.monitorScales();
        Dpi.MonitorScale hit = null;
        for (Dpi.MonitorScale m : mons) {
            if (m.physicallyContains(phy.x, phy.y)) {
                hit = m;
                break;
            }
        }
        double sx = hit != null ? hit.scaleX() : DPI_SCALE_X;
        double sy = hit != null ? hit.scaleY() : DPI_SCALE_Y;
        if (sx == 1.0 && sy == 1.0 && hit == null) {
            return phy;
        }
        if (hit != null) {
            // Express the physical point relative to this monitor's physical
            // origin, scale down, then add back its logical origin. This is
            // the only formulation that survives mixed-DPI monitors whose
            // logical and physical origins don't share a simple ratio.
            int relPx = phy.x - hit.physicalBounds().x;
            int relPy = phy.y - hit.physicalBounds().y;
            return new Rectangle(
                    hit.logicalBounds().x + (int) Math.round(relPx / sx),
                    hit.logicalBounds().y + (int) Math.round(relPy / sy),
                    (int) Math.round(phy.width  / sx),
                    (int) Math.round(phy.height / sy));
        }
        return new Rectangle(
                (int) Math.round(phy.x / sx),
                (int) Math.round(phy.y / sy),
                (int) Math.round(phy.width / sx),
                (int) Math.round(phy.height / sy));
    }

    private static final class TopmostCollector {
        int screenW;
        int screenH;
        List<Rectangle> list = new ArrayList<>();

        void reset(int w, int h) {
            this.screenW = w;
            this.screenH = h;
            this.list.clear();
        }
    }
}
