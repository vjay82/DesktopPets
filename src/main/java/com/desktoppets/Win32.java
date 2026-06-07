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

    /** {@code MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST)} — the monitor a
     *  window sits on, used to look up that monitor's work area. */
    private static final MethodHandle MONITOR_FROM_WINDOW = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("MonitorFromWindow").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
            : null;

    /** {@code GetMonitorInfoA} — fills a {@code MONITORINFO} (full + work-area
     *  rects) for an {@code HMONITOR}. */
    private static final MethodHandle GET_MONITOR_INFO = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("GetMonitorInfoA").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            : null;

    private static final int MONITOR_DEFAULTTONEAREST = 0x00000002;

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

    /** {@code SetWinEventHook} — installs an out-of-context accessibility event
     *  hook so the occlusion is recomputed only when a window actually moves /
     *  resizes / appears, instead of polling at a fixed rate. */
    private static final MethodHandle SET_WIN_EVENT_HOOK = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("SetWinEventHook").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT))
            : null;

    /** {@code UnhookWinEvent} — removes a hook installed above (on shutdown). */
    private static final MethodHandle UNHOOK_WIN_EVENT = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("UnhookWinEvent").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
            : null;

    /** {@code GetMessageW} — the hook thread's message pump; out-of-context
     *  WinEvent callbacks are delivered while this call is blocking. */
    private static final MethodHandle GET_MESSAGE = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("GetMessageW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
            : null;

    private static final MethodHandle TRANSLATE_MESSAGE = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("TranslateMessage").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
            : null;

    private static final MethodHandle DISPATCH_MESSAGE = WINDOWS
            ? LINKER.downcallHandle(
                    USER32.find("DispatchMessageW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS))
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

    // ---- SetWinEventHook: event-driven occlusion watch (replaces polling) ----
    /** {@code WINEVENT_OUTOFCONTEXT} — deliver callbacks on our own hook thread
     *  (no DLL injection); requires that thread to pump a message loop. */
    private static final int WINEVENT_OUTOFCONTEXT   = 0x0000;
    /** {@code WINEVENT_SKIPOWNPROCESS} — never fire for our own pet windows. */
    private static final int WINEVENT_SKIPOWNPROCESS = 0x0002;
    /** First system event we watch: the foreground window changed. */
    private static final int EVENT_SYSTEM_FOREGROUND  = 0x0003;
    /** Last system event we watch: a window finished un-minimising. The range
     *  in between also covers move/size start+end and minimise start. */
    private static final int EVENT_SYSTEM_MINIMIZEEND = 0x0017;
    /** First object event we watch: a window became visible. */
    private static final int EVENT_OBJECT_SHOW           = 0x8002;
    /** Last object event we watch: a window moved / resized. The range also
     *  covers hide / reorder / state-change (maximise & restore). */
    private static final int EVENT_OBJECT_LOCATIONCHANGE = 0x800B;

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

    private static final MethodHandle WIN_EVENT_PROC_HANDLE;
    static {
        if (WINDOWS) {
            try {
                WIN_EVENT_PROC_HANDLE = MethodHandles.lookup().findStatic(Win32.class, "winEventProc",
                        MethodType.methodType(void.class, MemorySegment.class, int.class,
                                MemorySegment.class, int.class, int.class, int.class, int.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        } else {
            WIN_EVENT_PROC_HANDLE = null;
        }
    }

    private static final MemorySegment WIN_EVENT_PROC_STUB = WINDOWS
            ? LINKER.upcallStub(WIN_EVENT_PROC_HANDLE,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                    ARENA)
            : null;

    /** Notified on the hook thread whenever a non-own top-level window changes
     *  in a way that can affect the occlusion (see {@link #startOcclusionWatch}). */
    private static volatile Runnable WINDOW_CHANGE_LISTENER;
    private static final AtomicBoolean WIN_EVENT_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean WIN_EVENT_FAILURE_LOGGED = new AtomicBoolean(false);

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
        // Work area (monitor minus taskbar) of the stage's monitor, physical px.
        // A window filling this is "effectively maximised" and must not be
        // carved (it would erase every pet) even though IsZoomed is false and
        // it stops short of the monitor's true bottom. Defaults to the full
        // stage rect until resolved.
        int wLeft, wTop, wRight, wBottom;
        boolean workAreaKnown;
        final List<int[]> holes = new ArrayList<>(); // {l,t,r,b} in window coords
        // Physical-px rects of "pets float on top" windows (maximised /
        // full-monitor / work-area-filling) seen SO FAR during the single
        // front-to-back EnumWindows pass — i.e. windows IN FRONT of whatever is
        // being looked at now. A partial window fully inside one of these is
        // invisible (hidden behind it), so it must NOT carve: doing so would cut
        // the pets out in a region the user actually sees the front window, where
        // the pets are supposed to float on top. See holeEnumProc.
        final List<int[]> frontBlockers = new ArrayList<>(); // {l,t,r,b} physical px

        void reset(long hwnd, int l, int t, int r, int b) {
            this.stageHwnd = hwnd;
            this.sLeft = l;
            this.sTop = t;
            this.sRight = r;
            this.sBottom = b;
            int[] wa = workAreaForWindow(hwnd);
            if (wa != null) {
                this.wLeft = wa[0];
                this.wTop = wa[1];
                this.wRight = wa[2];
                this.wBottom = wa[3];
                this.workAreaKnown = true;
            } else {
                this.wLeft = l;
                this.wTop = t;
                this.wRight = r;
                this.wBottom = b;
                this.workAreaKnown = false;
            }
            this.holes.clear();
            this.frontBlockers.clear();
        }
    }

    /** Reusable per-thread 40-byte {@code MONITORINFO} for {@link #workAreaForWindow}. */
    private static final ThreadLocal<MemorySegment> MONITORINFO_BUF = WINDOWS
            ? ThreadLocal.withInitial(() -> ARENA.allocate(40))
            : null;

    /** Work-area rect ({@code {left,top,right,bottom}}, physical px) of the
     *  monitor the given window is on, or null if it can't be resolved. The
     *  work area excludes the taskbar — exactly the region the pets occupy. */
    private static int[] workAreaForWindow(long hwnd) {
        if (!WINDOWS || MONITOR_FROM_WINDOW == null || GET_MONITOR_INFO == null || hwnd == 0L) {
            return null;
        }
        try {
            MemorySegment hMon = (MemorySegment) MONITOR_FROM_WINDOW.invoke(
                    MemorySegment.ofAddress(hwnd), MONITOR_DEFAULTTONEAREST);
            if (hMon == null || hMon.address() == 0) {
                return null;
            }
            MemorySegment mi = MONITORINFO_BUF.get();
            mi.set(ValueLayout.JAVA_INT, 0L, 40); // cbSize
            if ((int) GET_MONITOR_INFO.invoke(hMon, mi) == 0) {
                return null;
            }
            // MONITORINFO: cbSize(0), rcMonitor(4..19), rcWork(20..35), dwFlags(36)
            return new int[] {
                    mi.get(ValueLayout.JAVA_INT, 20L),
                    mi.get(ValueLayout.JAVA_INT, 24L),
                    mi.get(ValueLayout.JAVA_INT, 28L),
                    mi.get(ValueLayout.JAVA_INT, 32L),
            };
        } catch (Throwable t) {
            return null;
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

    /** Reusable per-thread 16-byte {@code RECT} out-param for
     *  {@link #physicalRectRaw}. The occlusion pass calls that helper for every
     *  top-level window ~30×/sec; a per-call {@code Arena.ofConfined()} there
     *  churned thousands of arena create/close + segment allocations per second.
     *  The pass is single-threaded, and the rect is copied into an {@code int[]}
     *  before the helper returns, so one reusable segment per thread is safe. */
    private static final ThreadLocal<MemorySegment> RECT_OUT = WINDOWS
            ? ThreadLocal.withInitial(() -> ARENA.allocate(16))
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
            // Cheap geometry first: the vast majority of top-level windows do
            // not overlap THIS monitor's stage (they are on another monitor,
            // minimised at -32000, or otherwise off-screen). Reject those up
            // front — before the costlier per-window queries below (owning PID,
            // class name, cloaked state) — so those only ever run for the handful
            // of windows actually covering the stage.
            int[] pr = physicalRectRaw(hwnd);
            if (pr == null) {
                return 1;
            }
            int l = Math.max(pr[0], c.sLeft);
            int t = Math.max(pr[1], c.sTop);
            int r = Math.min(pr[2], c.sRight);
            int b = Math.min(pr[3], c.sBottom);
            if (r <= l || b <= t) {
                return 1; // doesn't overlap this monitor's stage
            }
            if (isOwnProcessWindow(hwnd) || OWN_HWNDS.contains(hwnd.address())) {
                return 1; // never carve out our own stage / ball / tree / dialog
            }
            if (isShellBarClass(hwnd)) {
                return 1; // the taskbar: pets stand ON it, never carve it out
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
            //
            // "Fills the monitor" means EITHER the full monitor rect OR the
            // monitor's WORK AREA (monitor minus taskbar). The work-area case
            // matters because some apps (e.g. Microsoft Edge / Chromium) sit at
            // exactly (0,0)-(workW,workH) without being IsZoomed and without
            // reaching the monitor's true bottom — so they were mis-classified
            // as partial and carved out every pet standing on the taskbar
            // ("all pets cut off"). They cover the entire pet floor, so treat
            // them like a maximised window: don't carve, let pets float on top.
            //
            // Such a window is ALSO a "front blocker": EnumWindows runs
            // front-to-back, so any partial window enumerated AFTER this one is
            // behind it. Record its rect so a partial window fully hidden behind
            // it is not carved (see below).
            if (isMaximized(hwnd) || coversWholeStage(pr, c) || coversWorkArea(pr, c)) {
                c.frontBlockers.add(new int[] { pr[0], pr[1], pr[2], pr[3] });
                return 1;
            }
            if (isCloaked(hwnd)) {
                return 1; // minimised UWP / other virtual desktop — not really visible
            }
            // This is a partial, free-floating window. But if it is fully hidden
            // behind a "pets float on top" window IN FRONT of it (a maximised /
            // work-area window recorded above), it is invisible — carving it
            // would cut the pets out where the user sees that front window and
            // the pets are supposed to float on top. The real bug this fixes:
            // pets standing in front of maximised VS Code were sliced to "only
            // feet" by an Outlook / time-tracker / terminal window sitting
            // behind VS Code. Skip those.
            if (isHiddenBehindFrontBlocker(pr, c)) {
                return 1;
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

    /** TRUE iff {@code pr} (physical px) covers essentially the entire WORK
     *  AREA of the stage's monitor — the monitor minus the taskbar, i.e. the
     *  region the pets actually stand in. A window doing this (e.g. a
     *  work-area-filling but not-{@code IsZoomed} Edge/Chromium window) would
     *  hide every pet, so it must not be carved even though it stops short of
     *  the monitor's true bottom. Falls back to false when the work area could
     *  not be resolved (then {@link #coversWholeStage} is the only guard).
     *
     *  <p>The left/top/right edges use the tight {@code M}=2px margin; the
     *  BOTTOM edge is matched loosely (the window need only reach DOWN TO the
     *  work-area bottom, i.e. the taskbar top — it may legitimately stop a few
     *  px above the taskbar). Anchoring at top-left and spanning the full work
     *  width is what distinguishes "maximised-like" from a large free-floating
     *  window that leaves part of the screen uncovered. */
    private static boolean coversWorkArea(int[] pr, HoleCollector c) {
        if (!c.workAreaKnown) {
            return false;
        }
        final int M = 2;
        // Allow the window bottom to fall a little short of the work-area bottom
        // (some maximised-equivalent windows stop ~taskbar-height above it).
        int bottomSlack = Math.max(8, (c.wBottom - c.wTop) / 12);
        return pr[0] <= c.wLeft + M && pr[1] <= c.wTop + M
                && pr[2] >= c.wRight - M && pr[3] >= c.wBottom - bottomSlack;
    }

    /** TRUE iff {@code pr} (physical px) is essentially fully contained in one
     *  of the "pets float on top" windows ({@link HoleCollector#frontBlockers})
     *  enumerated IN FRONT of it — i.e. the window is hidden behind a maximised /
     *  work-area window and is not actually visible. Carving such a window would
     *  cut the pets out where the user sees the front window (and the pets are
     *  meant to float on top there), so the occlusion pass skips it. A small
     *  margin is allowed on every edge so frame-overhang / rounding doesn't
     *  defeat the containment test. */
    private static boolean isHiddenBehindFrontBlocker(int[] pr, HoleCollector c) {
        final int M = 2;
        List<int[]> blockers = c.frontBlockers;
        for (int i = 0; i < blockers.size(); i++) {
            int[] bl = blockers.get(i);
            if (bl[0] <= pr[0] + M && bl[1] <= pr[1] + M
                    && bl[2] >= pr[2] - M && bl[3] >= pr[3] - M) {
                return true;
            }
        }
        return false;
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

    /** Raw {@code GetWindowRect} (physical px) as {@code {left,top,right,bottom}}, or null.
     *  Uses the reusable per-thread {@link #RECT_OUT} segment (no per-call arena). */
    private static int[] physicalRectRaw(MemorySegment hwnd) {
        try {
            MemorySegment rect = RECT_OUT.get();
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

    /** Each occluder rectangle is shrunk by this many LOGICAL pixels on every
     *  edge (top, bottom, left, right) before it is cleared from the pet canvas.
     *  So the pets are not clipped exactly at the window border but overlap it
     *  slightly — the cut-out is 5px smaller than the window on each side. */
    private static final int OCCLUDER_INSET_PX = 5;

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
     * outward) so the caller never deals with DPI, then shrunk by
     * {@link #OCCLUDER_INSET_PX} on every edge so the pets overlap each window's
     * border slightly instead of being clipped exactly at it.
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
            List<int[]> out = new ArrayList<>(holes.size());
            for (int i = 0; i < holes.size(); i++) {
                int[] hr = holes.get(i); // {left,top,right,bottom} physical, stage-relative
                int x = (int) Math.floor(hr[0] / DPI_SCALE_X) + OCCLUDER_INSET_PX;
                int y = (int) Math.floor(hr[1] / DPI_SCALE_Y) + OCCLUDER_INSET_PX;
                int x2 = (int) Math.ceil(hr[2] / DPI_SCALE_X) - OCCLUDER_INSET_PX;
                int y2 = (int) Math.ceil(hr[3] / DPI_SCALE_Y) - OCCLUDER_INSET_PX;
                if (x2 <= x || y2 <= y) {
                    continue; // window narrower/shorter than the inset — nothing left to cut
                }
                out.add(new int[] { x, y, x2 - x, y2 - y });
            }
            if (out.isEmpty()) {
                return EMPTY_RECTS;
            }
            return out.toArray(new int[0][]);
        } catch (Throwable t) {
            if (OCCLUSION_FAILURE_LOGGED.compareAndSet(false, true)) {
                Log.warn("win32", "collectOccluders failed: " + t);
            }
            return EMPTY_RECTS;
        }
    }

    /**
     * Start watching for window changes that can affect the pet occlusion
     * (move, resize, maximise / restore, show / hide, foreground switch) and run
     * {@code onChange} whenever one happens. This replaces fixed-rate polling: a
     * dedicated daemon thread installs two out-of-context {@code SetWinEventHook}
     * accessibility hooks and pumps a message loop, so the callback fires only
     * when a window actually changes — the occlusion recompute becomes
     * event-driven and costs nothing while the desktop is idle.
     *
     * <p>{@code onChange} runs on the hook thread and MUST be cheap: it should
     * only schedule a coalesced recompute, never call {@link #collectOccluders}
     * inline (that would stall the message loop). The hooks are installed with
     * {@code WINEVENT_SKIPOWNPROCESS}, so our own pet windows never trigger it.
     * Started at most once; subsequent calls just refresh the listener. No-op
     * off Windows.
     */
    public static void startOcclusionWatch(Runnable onChange) {
        if (!WINDOWS) {
            return;
        }
        WINDOW_CHANGE_LISTENER = onChange;
        if (!WIN_EVENT_STARTED.compareAndSet(false, true)) {
            return; // hook thread already running
        }
        Thread t = new Thread(Win32::runWinEventLoop, "pets-winevent-hook");
        t.setDaemon(true);
        t.start();
    }

    /** Hook-thread body: install the WinEvent hooks, then pump the message loop
     *  that delivers their out-of-context callbacks. Runs for the life of the
     *  process; the hooks are removed if the loop ever exits. */
    private static void runWinEventLoop() {
        MemorySegment hookA = MemorySegment.NULL;
        MemorySegment hookB = MemorySegment.NULL;
        try {
            int flags = WINEVENT_OUTOFCONTEXT | WINEVENT_SKIPOWNPROCESS;
            hookA = (MemorySegment) SET_WIN_EVENT_HOOK.invoke(
                    EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_MINIMIZEEND,
                    MemorySegment.NULL, WIN_EVENT_PROC_STUB, 0, 0, flags);
            hookB = (MemorySegment) SET_WIN_EVENT_HOOK.invoke(
                    EVENT_OBJECT_SHOW, EVENT_OBJECT_LOCATIONCHANGE,
                    MemorySegment.NULL, WIN_EVENT_PROC_STUB, 0, 0, flags);
            try (Arena a = Arena.ofConfined()) {
                MemorySegment msg = a.allocate(64); // MSG (48 bytes on x64; padded)
                int r;
                while ((r = (int) GET_MESSAGE.invoke(msg, MemorySegment.NULL, 0, 0)) != 0) {
                    if (r == -1) {
                        break; // GetMessage error
                    }
                    TRANSLATE_MESSAGE.invoke(msg);
                    DISPATCH_MESSAGE.invoke(msg);
                }
            }
        } catch (Throwable t) {
            if (WIN_EVENT_FAILURE_LOGGED.compareAndSet(false, true)) {
                Log.warn("win32", "WinEvent occlusion watch failed: " + t);
            }
        } finally {
            try {
                if (hookA != null && hookA.address() != 0) {
                    UNHOOK_WIN_EVENT.invoke(hookA);
                }
                if (hookB != null && hookB.address() != 0) {
                    UNHOOK_WIN_EVENT.invoke(hookB);
                }
            } catch (Throwable ignored) {
                // shutting down — ignore
            }
        }
    }

    /**
     * {@code WINEVENTPROC} callback (out-of-context), invoked on the hook thread
     * for every accessibility event in the hooked ranges. We only care about
     * top-level window objects ({@code OBJID_WINDOW} / {@code CHILDID_SELF}) —
     * caret, cursor and other child-object events (which fire constantly) are
     * filtered out — and merely notify the listener; the listener coalesces
     * these into a throttled recompute. {@code WINEVENT_SKIPOWNPROCESS} already
     * drops events from our own pet windows.
     */
    @SuppressWarnings("unused") // called via upcall stub
    private static void winEventProc(MemorySegment hWinEventHook, int event, MemorySegment hwnd,
            int idObject, int idChild, int idEventThread, int dwmsEventTime) {
        try {
            if (idObject != 0 || idChild != 0) {
                return; // not the window itself (OBJID_WINDOW=0, CHILDID_SELF=0)
            }
            if (hwnd == null || hwnd.address() == 0) {
                return;
            }
            Runnable l = WINDOW_CHANGE_LISTENER;
            if (l != null) {
                l.run();
            }
        } catch (Throwable ignored) {
            // a misbehaving listener must never break the message loop
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
