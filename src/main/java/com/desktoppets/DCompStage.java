package com.desktoppets;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hardware-composited pet stage built on <b>DirectComposition</b> (DComp) via
 * the stable Foreign Function &amp; Memory API (JDK 22+).
 *
 * <p>Motivation: the Swing {@link Stage} hosts every pet on a full-monitor,
 * per-pixel-translucent ({@code WS_EX_LAYERED} / {@code UpdateLayeredWindow})
 * window. The Desktop Window Manager (DWM) must re-blend that screen-sized ARGB
 * surface over the entire desktop every animation frame, so GPU cost scales
 * with screen resolution regardless of how few / small the pets are.
 *
 * <p>DirectComposition inverts that: a single host window created with
 * {@code WS_EX_NOREDIRECTIONBITMAP} contributes <i>no</i> pixels of its own.
 * Each pet becomes a small {@code IDCompositionVisual} whose content is a
 * pet-sized {@code IDCompositionSurface}. The DWM composites only those small
 * visuals as part of its existing pass, so cost scales with total pet area, and
 * moving a pet is a metadata-only {@code SetOffsetX/Y} — no pixels are
 * re-uploaded.
 *
 * <p><b>Threading.</b> DirectComposition objects are not thread-safe and are
 * affine to the thread that created the device. All native work therefore runs
 * on a single dedicated <i>owner thread</i> that also owns the host window and
 * pumps its message loop. Public methods marshal onto that thread via a task
 * queue; visual-tree mutations are batched and made visible by {@link #commit()}.
 *
 * <p><b>Scope / status.</b> This is the native foundation plus a self-contained
 * {@link #main(String[]) smoke test}. It is completely independent of the Swing
 * {@link Stage} — nothing here runs unless explicitly started — so the existing
 * rendering path is untouched. Occlusion (pets hiding behind windows),
 * per-monitor DPI mapping and wiring real pet bitmaps in place of the Swing
 * labels are follow-up stages.
 *
 * <p>Every native failure is caught and turned into {@link #isReady()} ==
 * {@code false}; the caller is expected to fall back to the Swing {@link Stage}.
 */
public final class DCompStage {

    // ────────────────────────────────────────────────────────────────────
    //  Platform / linker
    // ────────────────────────────────────────────────────────────────────

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    private static final Linker LINKER = WINDOWS ? Linker.nativeLinker() : null;
    /** Process-lifetime arena for symbol lookups and the window-class / WndProc stubs. */
    private static final Arena GLOBAL = WINDOWS ? Arena.ofShared() : null;

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    private static final ValueLayout.OfShort I16 = ValueLayout.JAVA_SHORT;
    private static final ValueLayout.OfByte I8 = ValueLayout.JAVA_BYTE;
    private static final java.lang.foreign.AddressLayout PTR = ValueLayout.ADDRESS;

    private static SymbolLookup lib(String name) {
        try {
            return SymbolLookup.libraryLookup(name, GLOBAL);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final SymbolLookup OLE32 = WINDOWS ? lib("ole32") : null;
    private static final SymbolLookup D3D11 = WINDOWS ? lib("d3d11") : null;
    private static final SymbolLookup DCOMP = WINDOWS ? lib("dcomp") : null;
    private static final SymbolLookup USER32 = WINDOWS ? lib("user32") : null;
    private static final SymbolLookup KERNEL32 = WINDOWS ? lib("kernel32") : null;

    private static MethodHandle dc(SymbolLookup lib, String name, FunctionDescriptor fd) {
        if (lib == null) {
            return null;
        }
        return lib.find(name).map(sym -> LINKER.downcallHandle(sym, fd)).orElse(null);
    }

    // ── DLL function exports ─────────────────────────────────────────────
    private static final MethodHandle CoInitializeEx = dc(OLE32, "CoInitializeEx",
            FunctionDescriptor.of(I32, PTR, I32));
    private static final MethodHandle D3D11CreateDevice = dc(D3D11, "D3D11CreateDevice",
            FunctionDescriptor.of(I32, PTR, I32, PTR, I32, PTR, I32, I32, PTR, PTR, PTR));
    private static final MethodHandle DCompositionCreateDevice = dc(DCOMP, "DCompositionCreateDevice",
            FunctionDescriptor.of(I32, PTR, PTR, PTR));
    private static final MethodHandle GetModuleHandleW = dc(KERNEL32, "GetModuleHandleW",
            FunctionDescriptor.of(PTR, PTR));
    private static final MethodHandle RegisterClassExW = dc(USER32, "RegisterClassExW",
            FunctionDescriptor.of(I16, PTR));
    private static final MethodHandle CreateWindowExW = dc(USER32, "CreateWindowExW",
            FunctionDescriptor.of(PTR, I32, PTR, PTR, I32, I32, I32, I32, I32, PTR, PTR, PTR, PTR));
    private static final MethodHandle DefWindowProcW = dc(USER32, "DefWindowProcW",
            FunctionDescriptor.of(I64, PTR, I32, I64, I64));
    private static final MethodHandle ShowWindow = dc(USER32, "ShowWindow",
            FunctionDescriptor.of(I32, PTR, I32));
    private static final MethodHandle DestroyWindow = dc(USER32, "DestroyWindow",
            FunctionDescriptor.of(I32, PTR));
    private static final MethodHandle GetSystemMetrics = dc(USER32, "GetSystemMetrics",
            FunctionDescriptor.of(I32, I32));
    private static final MethodHandle PeekMessageW = dc(USER32, "PeekMessageW",
            FunctionDescriptor.of(I32, PTR, PTR, I32, I32, I32));
    private static final MethodHandle TranslateMessage = dc(USER32, "TranslateMessage",
            FunctionDescriptor.of(I32, PTR));
    private static final MethodHandle DispatchMessageW = dc(USER32, "DispatchMessageW",
            FunctionDescriptor.of(I64, PTR));
    private static final MethodHandle EnumDisplayMonitors = dc(USER32, "EnumDisplayMonitors",
            FunctionDescriptor.of(I32, PTR, PTR, PTR, I64));

    // ── COM vtable invokers (address-less: leading arg is the fn pointer) ─
    // Each handle's invokeExact signature is (fnPtr, thisPtr, <descriptor args…>).
    private static MethodHandle inv(FunctionDescriptor fd) {
        return WINDOWS ? LINKER.downcallHandle(fd) : null;
    }

    /** (this, riid, ppv) -> HRESULT — IUnknown::QueryInterface (vtbl 0). */
    private static final MethodHandle INV_QI = inv(FunctionDescriptor.of(I32, PTR, PTR, PTR));
    /** (this) -> ULONG — IUnknown::Release (vtbl 2); also HRESULT-returning no-arg methods. */
    private static final MethodHandle INV_THIS = inv(FunctionDescriptor.of(I32, PTR));
    /** (this, ptr) -> HRESULT. */
    private static final MethodHandle INV_P = inv(FunctionDescriptor.of(I32, PTR, PTR));
    /** (this, ptr, int, ptr) -> HRESULT. */
    private static final MethodHandle INV_PIP = inv(FunctionDescriptor.of(I32, PTR, PTR, I32, PTR));
    /** (this, int, int, int, int, ptr) -> HRESULT. */
    private static final MethodHandle INV_IIIIP = inv(FunctionDescriptor.of(I32, PTR, I32, I32, I32, I32, PTR));
    /** (this, float) -> HRESULT. */
    private static final MethodHandle INV_F = inv(FunctionDescriptor.of(I32, PTR, F32));
    /** (this, ptr, ptr, ptr, ptr) -> HRESULT — IDCompositionSurface::BeginDraw. */
    private static final MethodHandle INV_PPPP = inv(FunctionDescriptor.of(I32, PTR, PTR, PTR, PTR, PTR));
    /** (this, ptr, int, ptr, ptr, int, int) -> void — ID3D11DeviceContext::UpdateSubresource. */
    private static final MethodHandle INV_UPDATE_SUBRESOURCE =
            inv(FunctionDescriptor.ofVoid(PTR, PTR, I32, PTR, PTR, I32, I32));

    // ── COM vtable indices (0-based; IUnknown = QI 0 / AddRef 1 / Release 2) ──
    private static final int IDX_RELEASE = 2;
    private static final int DEV_COMMIT = 3;
    private static final int DEV_CREATE_TARGET_FOR_HWND = 6;
    private static final int DEV_CREATE_VISUAL = 7;
    private static final int DEV_CREATE_SURFACE = 8;
    private static final int TARGET_SET_ROOT = 3;
    private static final int VISUAL_SET_OFFSET_X = 4;
    private static final int VISUAL_SET_OFFSET_Y = 6;
    private static final int VISUAL_SET_CONTENT = 15;
    private static final int VISUAL_ADD_VISUAL = 16;
    private static final int VISUAL_REMOVE_VISUAL = 17;
    private static final int SURFACE_BEGIN_DRAW = 3;
    private static final int SURFACE_END_DRAW = 4;
    private static final int CTX_UPDATE_SUBRESOURCE = 48;

    // ── Constants ────────────────────────────────────────────────────────
    private static final int COINIT_APARTMENTTHREADED = 0x2;
    private static final int D3D_DRIVER_TYPE_HARDWARE = 1;
    private static final int D3D_DRIVER_TYPE_WARP = 5;
    private static final int D3D11_SDK_VERSION = 7;
    private static final int D3D11_CREATE_DEVICE_BGRA_SUPPORT = 0x20;
    private static final int DXGI_FORMAT_B8G8R8A8_UNORM = 87;
    private static final int DXGI_ALPHA_MODE_PREMULTIPLIED = 1;

    private static final int WS_POPUP = 0x80000000;
    private static final int WS_VISIBLE = 0x10000000;
    private static final int WS_EX_TOPMOST = 0x00000008;
    private static final int WS_EX_TRANSPARENT = 0x00000020;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_LAYERED = 0x00080000;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int WS_EX_NOREDIRECTIONBITMAP = 0x00200000;
    private static final int SW_SHOWNOACTIVATE = 4;
    private static final int PM_REMOVE = 0x0001;

    /** Sent to determine what part of a window the cursor is over. Returning
     *  {@code HTTRANSPARENT} makes the whole overlay click-through. */
    private static final int WM_NCHITTEST = 0x0084;
    /** {@code WM_NCHITTEST} result: pass the hit-test through to the window
     *  below. */
    private static final long HTTRANSPARENT = -1L;

    private static final int SM_XVIRTUALSCREEN = 76;
    private static final int SM_YVIRTUALSCREEN = 77;
    private static final int SM_CXVIRTUALSCREEN = 78;
    private static final int SM_CYVIRTUALSCREEN = 79;

    // ── IIDs (as 16-byte little-endian GUID segments, in GLOBAL arena) ────
    private static final MemorySegment IID_IDXGIDevice =
            guid(0x54ec77fa, 0x1377, 0x44e6, 0x8c, 0x32, 0x88, 0xfd, 0x5f, 0x44, 0xc8, 0x4c);
    private static final MemorySegment IID_IDCompositionDevice =
            guid(0xc37ea93a, 0xe7aa, 0x450d, 0xb1, 0x6f, 0x97, 0x46, 0xcb, 0x04, 0x07, 0xf3);
    private static final MemorySegment IID_IDXGISurface =
            guid(0xcafcb56c, 0x6ac3, 0x4889, 0xbf, 0x47, 0x9e, 0x23, 0xbb, 0xd2, 0x60, 0xec);
    private static final MemorySegment IID_ID3D11Texture2D =
            guid(0x6f15aaf2, 0xd208, 0x4e89, 0x9a, 0xb4, 0x48, 0x95, 0x35, 0xd3, 0x4f, 0x9c);

    // ────────────────────────────────────────────────────────────────────
    //  Singleton owner-thread state
    // ────────────────────────────────────────────────────────────────────

    private static final DCompStage INSTANCE = new DCompStage();

    public static DCompStage instance() {
        return INSTANCE;
    }

    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicLong nextHandle = new AtomicLong(1);
    private final java.util.Map<Long, Visual> visuals = new java.util.concurrent.ConcurrentHashMap<>();

    private Thread ownerThread;
    private volatile CountDownLatch startLatch;

    // Native handles (valid only on the owner thread once ready).
    private long hwnd;
    private long d3dDevice;
    private long d3dContext;
    private long dcompDevice;
    private long dcompTarget;
    private long rootVisual;
    private int originX; // virtual-desktop left (host window origin)
    private int originY; // virtual-desktop top

    /** A pet visual: its DComp visual, its backing surface, and its pixel size. */
    private static final class Visual {
        long visual;
        long surface;
        int w;
        int h;
        boolean attached;
    }

    private DCompStage() {
    }

    // ────────────────────────────────────────────────────────────────────
    //  Public API (thread-safe; marshals onto the owner thread)
    // ────────────────────────────────────────────────────────────────────

    /** {@code true} once the host window + DComp device are live and usable. */
    public boolean isReady() {
        return ready.get();
    }

    /**
     * Start the owner thread and initialise DirectComposition. Blocks up to
     * {@code timeoutMs} for initialisation to finish. Returns {@code true} iff
     * the stage is ready; on any failure it returns {@code false} and the
     * caller should fall back to the Swing {@link Stage}. Idempotent.
     */
    public boolean start(long timeoutMs) {
        if (!WINDOWS || LINKER == null || DCOMP == null || D3D11 == null) {
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            return ready.get(); // already started
        }
        startLatch = new CountDownLatch(1);
        ownerThread = new Thread(this::ownerLoop, "pets-dcomp-owner");
        ownerThread.setDaemon(true);
        ownerThread.start();
        try {
            startLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ready.get();
    }

    /** Stop the owner thread and tear down native resources. */
    public void stop() {
        running.set(false);
        Thread t = ownerThread;
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Create a pet visual of the given pixel size. Returns an opaque handle
     * (&gt; 0) used by the other methods, or {@code 0} on failure. The visual
     * is not attached to the scene until {@link #show(long)} is called.
     */
    public long createVisual(int w, int h) {
        if (!ready.get() || w <= 0 || h <= 0) {
            return 0L;
        }
        long handle = nextHandle.getAndIncrement();
        AtomicReference<Boolean> ok = new AtomicReference<>(Boolean.FALSE);
        runSync(() -> {
            Visual v = new Visual();
            v.w = w;
            v.h = h;
            v.surface = createSurface(w, h);
            if (v.surface == 0L) {
                return;
            }
            v.visual = createVisual0();
            if (v.visual == 0L) {
                release(v.surface);
                return;
            }
            comP(v.visual, VISUAL_SET_CONTENT, MemorySegment.ofAddress(v.surface));
            visuals.put(handle, v);
            ok.set(Boolean.TRUE);
        });
        return ok.get() ? handle : 0L;
    }

    /** Upload premultiplied ARGB ({@code 0xAARRGGBB}) pixels to the visual's surface. */
    public void updateBitmap(long handle, int[] argbPremultiplied, int w, int h) {
        Visual v = visuals.get(handle);
        if (v == null || argbPremultiplied == null || w != v.w || h != v.h) {
            return;
        }
        runAsync(() -> uploadSurface(v, argbPremultiplied, w, h));
    }

    /** Position the visual, in virtual-desktop screen pixels. */
    public void setPosition(long handle, float screenX, float screenY) {
        Visual v = visuals.get(handle);
        if (v == null) {
            return;
        }
        runAsync(() -> {
            comF(v.visual, VISUAL_SET_OFFSET_X, screenX - originX);
            comF(v.visual, VISUAL_SET_OFFSET_Y, screenY - originY);
        });
    }

    /** Attach the visual to the scene (front of the root). */
    public void show(long handle) {
        Visual v = visuals.get(handle);
        if (v == null) {
            return;
        }
        runAsync(() -> {
            if (!v.attached) {
                // AddVisual(child, insertAbove=TRUE, referenceVisual=NULL) → front.
                comPIP(rootVisual, VISUAL_ADD_VISUAL, v.visual, 1, MemorySegment.NULL);
                v.attached = true;
            }
        });
    }

    /** Detach the visual from the scene without destroying it. */
    public void hide(long handle) {
        Visual v = visuals.get(handle);
        if (v == null) {
            return;
        }
        runAsync(() -> {
            if (v.attached) {
                comP(rootVisual, VISUAL_REMOVE_VISUAL, MemorySegment.ofAddress(v.visual));
                v.attached = false;
            }
        });
    }

    /** Detach and destroy the visual and its surface. */
    public void destroy(long handle) {
        Visual v = visuals.remove(handle);
        if (v == null) {
            return;
        }
        runAsync(() -> {
            if (v.attached) {
                comP(rootVisual, VISUAL_REMOVE_VISUAL, MemorySegment.ofAddress(v.visual));
            }
            release(v.visual);
            release(v.surface);
        });
    }

    /** Make all pending visual-tree changes visible. Cheap; batch per frame. */
    public void commit() {
        runAsync(() -> com0(dcompDevice, DEV_COMMIT));
    }

    // ────────────────────────────────────────────────────────────────────
    //  Owner thread
    // ────────────────────────────────────────────────────────────────────

    private void ownerLoop() {
        boolean initialised = false;
        try {
            // COM apartment for the window-owning thread.
            invokeInt(CoInitializeEx, MemorySegment.NULL, COINIT_APARTMENTTHREADED);
            initialised = initNative();
            ready.set(initialised);
        } catch (Throwable t) {
            log("owner init failed: " + t);
            ready.set(false);
        } finally {
            CountDownLatch l = startLatch;
            if (l != null) {
                l.countDown();
            }
        }

        if (!initialised) {
            running.set(false);
            return;
        }

        try (Arena pump = Arena.ofConfined()) {
            MemorySegment msg = pump.allocate(48); // MSG (x64)
            while (running.get()) {
                // 1) Always drain the window message queue FIRST. This window is
                //    full-screen + topmost, so any delay answering WM_NCHITTEST
                //    freezes input for the WHOLE desktop — the pump must never be
                //    starved by scene work.
                pumpMessages(msg);
                // 2) Run queued scene tasks, re-pumping messages between small
                //    batches so a burst of per-frame uploads can never delay
                //    hit-testing.
                Runnable r;
                int processed = 0;
                while ((r = tasks.poll()) != null) {
                    try {
                        r.run();
                    } catch (Throwable t) {
                        log("scene task failed: " + t);
                    }
                    if ((++processed & 7) == 0) {
                        pumpMessages(msg);
                    }
                }
                pumpMessages(msg);
                // 3) Idle only when there's nothing to do, and briefly, so the
                //    next WM_NCHITTEST is serviced with minimal latency.
                if (tasks.isEmpty()) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            log("owner loop failed: " + t);
        } finally {
            teardownNative();
            ready.set(false);
        }
    }

    /** Drain and dispatch all pending window messages for this thread. Uses a
     *  {@code NULL} hWnd filter (the standard pump) so thread messages and
     *  sent messages (WM_NCHITTEST) are always processed. */
    private static void pumpMessages(MemorySegment msg) {
        while (invokeInt(PeekMessageW, msg, MemorySegment.NULL, 0, 0, PM_REMOVE) != 0) {
            invokeInt(TranslateMessage, msg);
            invokeLong(DispatchMessageW, msg);
        }
    }

    /** Create the host window and the D3D11 + DirectComposition device chain. */
    private boolean initNative() {
        try (Arena a = Arena.ofConfined()) {
            if (!registerClass(a)) {
                return false;
            }
            originX = invokeInt(GetSystemMetrics, SM_XVIRTUALSCREEN);
            originY = invokeInt(GetSystemMetrics, SM_YVIRTUALSCREEN);
            int vw = invokeInt(GetSystemMetrics, SM_CXVIRTUALSCREEN);
            int vh = invokeInt(GetSystemMetrics, SM_CYVIRTUALSCREEN);
            if (vw <= 0 || vh <= 0) {
                return false;
            }
            if (!createHostWindow(a, originX, originY, vw, vh)) {
                return false;
            }
            if (!createDevices(a)) {
                return false;
            }
            log("ready: hwnd=0x" + Long.toHexString(hwnd) + " origin=(" + originX + "," + originY
                    + ") size=" + vw + "x" + vh);
            return true;
        } catch (Throwable t) {
            log("initNative failed: " + t);
            return false;
        }
    }

    private static final MemorySegment WNDPROC_STUB;
    private static final MemorySegment CLASS_NAME;
    static {
        MemorySegment stub = MemorySegment.NULL;
        MemorySegment cls = MemorySegment.NULL;
        if (WINDOWS && USER32 != null) {
            try {
                MethodHandle mh = MethodHandles.lookup().findStatic(DCompStage.class, "wndProc",
                        MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class));
                stub = LINKER.upcallStub(mh,
                        FunctionDescriptor.of(I64, PTR, I32, I64, I64), GLOBAL);
                cls = wide(GLOBAL, "DesktopPetsDCompHost");
            } catch (Throwable t) {
                stub = MemorySegment.NULL;
                cls = MemorySegment.NULL;
            }
        }
        WNDPROC_STUB = stub;
        CLASS_NAME = cls;
    }

    @SuppressWarnings("unused") // invoked via upcall stub
    private static long wndProc(MemorySegment hWnd, int msg, long wParam, long lParam) {
        try {
            // Make the entire overlay transparent to hit-testing so every click
            // falls through to the applications beneath it. WS_EX_TRANSPARENT
            // alone is unreliable without WS_EX_LAYERED, which a
            // WS_EX_NOREDIRECTIONBITMAP window cannot use — so we answer
            // WM_NCHITTEST with HTTRANSPARENT instead. Pet hover / click is
            // polled by PetMouse (cursor + GetAsyncKeyState), never delivered
            // as window messages, so nothing is lost.
            if (msg == WM_NCHITTEST) {
                return HTTRANSPARENT;
            }
            return (long) DefWindowProcW.invokeExact(hWnd, msg, wParam, lParam);
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ── Physical-monitor enumeration (diagnostics + coordinate mapping) ──
    private static final java.util.List<int[]> MON_RECTS =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final MemorySegment MON_PROC_STUB;
    static {
        MemorySegment stub = MemorySegment.NULL;
        if (WINDOWS && USER32 != null) {
            try {
                MethodHandle mh = MethodHandles.lookup().findStatic(DCompStage.class, "monEnumProc",
                        MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class,
                                MemorySegment.class, long.class));
                stub = LINKER.upcallStub(mh,
                        FunctionDescriptor.of(I32, PTR, PTR, PTR, I64), GLOBAL);
            } catch (Throwable t) {
                stub = MemorySegment.NULL;
            }
        }
        MON_PROC_STUB = stub;
    }

    @SuppressWarnings("unused") // invoked via upcall stub
    private static int monEnumProc(MemorySegment hmon, MemorySegment hdc, MemorySegment lprc, long data) {
        try {
            MemorySegment r = lprc.reinterpret(16);
            MON_RECTS.add(new int[] { r.get(I32, 0), r.get(I32, 4), r.get(I32, 8), r.get(I32, 12) });
        } catch (Throwable t) {
            // skip a bad monitor rect
        }
        return 1; // continue
    }

    /**
     * Physical monitor rectangles in virtual-desktop coordinates as
     * {@code {left, top, right, bottom}} (device pixels). Enumerated fresh
     * each call. Returns an empty list off Windows or on failure.
     */
    public static java.util.List<int[]> physicalMonitors() {
        if (!WINDOWS || EnumDisplayMonitors == null || MON_PROC_STUB.address() == 0) {
            return java.util.List.of();
        }
        MON_RECTS.clear();
        try {
            int r = (int) EnumDisplayMonitors.invokeExact(
                    MemorySegment.NULL, MemorySegment.NULL, MON_PROC_STUB, 0L);
        } catch (Throwable t) {
            log("EnumDisplayMonitors failed: " + t);
        }
        return new java.util.ArrayList<>(MON_RECTS);
    }

    private boolean registerClass(Arena a) throws Throwable {
        if (WNDPROC_STUB.address() == 0 || CLASS_NAME.address() == 0) {
            return false;
        }
        MemorySegment hInst = (MemorySegment) GetModuleHandleW.invokeExact(MemorySegment.NULL);
        MemorySegment wc = a.allocate(80); // WNDCLASSEXW
        wc.set(I32, 0, 80);                // cbSize
        wc.set(I32, 4, 0);                 // style
        wc.set(PTR, 8, WNDPROC_STUB);      // lpfnWndProc
        wc.set(I32, 16, 0);                // cbClsExtra
        wc.set(I32, 20, 0);                // cbWndExtra
        wc.set(PTR, 24, hInst);            // hInstance
        wc.set(PTR, 32, MemorySegment.NULL); // hIcon
        wc.set(PTR, 40, MemorySegment.NULL); // hCursor
        wc.set(PTR, 48, MemorySegment.NULL); // hbrBackground
        wc.set(PTR, 56, MemorySegment.NULL); // lpszMenuName
        wc.set(PTR, 64, CLASS_NAME);         // lpszClassName
        wc.set(PTR, 72, MemorySegment.NULL); // hIconSm
        short atom = (short) RegisterClassExW.invokeExact(wc);
        // A previously-registered class (atom 0 with ERROR_CLASS_ALREADY_EXISTS)
        // is fine — CreateWindowExW resolves by name below.
        return true;
    }

    private boolean createHostWindow(Arena a, int x, int y, int w, int h) throws Throwable {
        MemorySegment hInst = (MemorySegment) GetModuleHandleW.invokeExact(MemorySegment.NULL);
        MemorySegment title = wide(a, "DesktopPets DComp Stage");
        // WS_EX_LAYERED is required (together with WS_EX_TRANSPARENT) for real
        // cross-process click-through — WS_EX_TRANSPARENT alone / a WM_NCHITTEST
        // HTTRANSPARENT reply only passes hits to same-thread windows beneath.
        // WS_EX_NOREDIRECTIONBITMAP keeps the window surface empty so only the
        // DirectComposition visuals contribute pixels.
        int exStyle = WS_EX_NOREDIRECTIONBITMAP | WS_EX_LAYERED | WS_EX_TOPMOST
                | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW;
        int style = WS_POPUP;
        MemorySegment hWndSeg = (MemorySegment) CreateWindowExW.invokeExact(
                exStyle, CLASS_NAME, title, style,
                x, y, w, h,
                MemorySegment.NULL, MemorySegment.NULL, hInst, MemorySegment.NULL);
        if (hWndSeg == null || hWndSeg.address() == 0) {
            log("CreateWindowExW failed");
            return false;
        }
        this.hwnd = hWndSeg.address();
        invokeInt(ShowWindow, hWndSeg, SW_SHOWNOACTIVATE);
        return true;
    }

    private boolean createDevices(Arena a) throws Throwable {
        // 1) D3D11 device + immediate context.
        MemorySegment ppDevice = a.allocate(PTR);
        MemorySegment ppContext = a.allocate(PTR);
        int hr = createD3D11(D3D_DRIVER_TYPE_HARDWARE, ppDevice, ppContext);
        if (hr < 0) {
            // Fall back to the WARP software rasteriser (still GPU-composited by DWM).
            hr = createD3D11(D3D_DRIVER_TYPE_WARP, ppDevice, ppContext);
        }
        if (hr < 0) {
            log("D3D11CreateDevice failed hr=0x" + Integer.toHexString(hr));
            return false;
        }
        d3dDevice = ppDevice.get(PTR, 0).address();
        d3dContext = ppContext.get(PTR, 0).address();

        // 2) QI the D3D device for its DXGI device.
        MemorySegment ppDxgi = a.allocate(PTR);
        hr = comQI(d3dDevice, IID_IDXGIDevice, ppDxgi);
        if (hr < 0) {
            log("QI IDXGIDevice failed hr=0x" + Integer.toHexString(hr));
            return false;
        }
        long dxgiDevice = ppDxgi.get(PTR, 0).address();

        // 3) Create the DirectComposition device from the DXGI device.
        MemorySegment ppDComp = a.allocate(PTR);
        hr = invokeInt(DCompositionCreateDevice, MemorySegment.ofAddress(dxgiDevice),
                IID_IDCompositionDevice, ppDComp);
        release(dxgiDevice); // DComp device holds its own reference
        if (hr < 0) {
            log("DCompositionCreateDevice failed hr=0x" + Integer.toHexString(hr));
            return false;
        }
        dcompDevice = ppDComp.get(PTR, 0).address();

        // 4) Bind a composition target to the host window and set the root visual.
        MemorySegment ppTarget = a.allocate(PTR);
        hr = comPIP_ret(dcompDevice, DEV_CREATE_TARGET_FOR_HWND,
                MemorySegment.ofAddress(hwnd), 1 /* topmost */, ppTarget);
        if (hr < 0) {
            log("CreateTargetForHwnd failed hr=0x" + Integer.toHexString(hr));
            return false;
        }
        dcompTarget = ppTarget.get(PTR, 0).address();

        rootVisual = createVisual0();
        if (rootVisual == 0L) {
            return false;
        }
        hr = comP(dcompTarget, TARGET_SET_ROOT, MemorySegment.ofAddress(rootVisual));
        if (hr < 0) {
            log("Target SetRoot failed hr=0x" + Integer.toHexString(hr));
            return false;
        }
        com0(dcompDevice, DEV_COMMIT);
        return true;
    }

    private int createD3D11(int driverType, MemorySegment ppDevice, MemorySegment ppContext)
            throws Throwable {
        return (int) D3D11CreateDevice.invokeExact(
                MemorySegment.NULL,          // pAdapter (use default)
                driverType,                  // DriverType
                MemorySegment.NULL,          // Software
                D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                MemorySegment.NULL,          // pFeatureLevels (default set)
                0,                           // FeatureLevels
                D3D11_SDK_VERSION,
                ppDevice,                    // ppDevice
                MemorySegment.NULL,          // pFeatureLevel (out, ignored)
                ppContext);                  // ppImmediateContext
    }

    private void teardownNative() {
        try {
            for (Visual v : visuals.values()) {
                release(v.visual);
                release(v.surface);
            }
            visuals.clear();
            release(rootVisual);
            release(dcompTarget);
            release(dcompDevice);
            release(d3dContext);
            release(d3dDevice);
            if (hwnd != 0L) {
                invokeInt(DestroyWindow, MemorySegment.ofAddress(hwnd));
            }
        } catch (Throwable t) {
            log("teardown failed: " + t);
        } finally {
            rootVisual = dcompTarget = dcompDevice = d3dContext = d3dDevice = hwnd = 0L;
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Native scene helpers (owner thread only)
    // ────────────────────────────────────────────────────────────────────

    private long createVisual0() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(PTR);
            int hr = comP(dcompDevice, DEV_CREATE_VISUAL, out);
            if (hr < 0) {
                log("CreateVisual failed hr=0x" + Integer.toHexString(hr));
                return 0L;
            }
            return out.get(PTR, 0).address();
        } catch (Throwable t) {
            log("CreateVisual threw: " + t);
            return 0L;
        }
    }

    private long createSurface(int w, int h) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(PTR);
            int hr = comIIIIP(dcompDevice, DEV_CREATE_SURFACE, w, h,
                    DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_ALPHA_MODE_PREMULTIPLIED, out);
            if (hr < 0) {
                log("CreateSurface failed hr=0x" + Integer.toHexString(hr));
                return 0L;
            }
            return out.get(PTR, 0).address();
        } catch (Throwable t) {
            log("CreateSurface threw: " + t);
            return 0L;
        }
    }

    private void uploadSurface(Visual v, int[] argb, int w, int h) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ppSurface = a.allocate(PTR);
            MemorySegment offset = a.allocate(8); // POINT
            int hr = comBeginDraw(v.surface, MemorySegment.NULL, IID_IDXGISurface, ppSurface, offset);
            if (hr < 0) {
                log("BeginDraw failed hr=0x" + Integer.toHexString(hr));
                return;
            }
            long dxgiSurface = ppSurface.get(PTR, 0).address();
            try {
                int ox = offset.get(I32, 0);
                int oy = offset.get(I32, 4);

                // The update object is the atlas texture; QI to ID3D11Texture2D.
                MemorySegment ppTex = a.allocate(PTR);
                int qhr = comQI(dxgiSurface, IID_ID3D11Texture2D, ppTex);
                if (qhr < 0) {
                    log("QI ID3D11Texture2D failed hr=0x" + Integer.toHexString(qhr));
                    return;
                }
                long tex = ppTex.get(PTR, 0).address();
                try {
                    MemorySegment src = a.allocate((long) w * h * 4);
                    MemorySegment.copy(argb, 0, src, I32, 0, w * h);

                    MemorySegment box = a.allocate(24); // D3D11_BOX
                    box.set(I32, 0, ox);        // left
                    box.set(I32, 4, oy);        // top
                    box.set(I32, 8, 0);         // front
                    box.set(I32, 12, ox + w);   // right
                    box.set(I32, 16, oy + h);   // bottom
                    box.set(I32, 20, 1);        // back

                    ctxUpdateSubresource(d3dContext, tex, 0, box, src, w * 4, 0);
                } finally {
                    release(tex);
                }
            } finally {
                release(dxgiSurface);
                com0(v.surface, SURFACE_END_DRAW);
            }
        } catch (Throwable t) {
            log("uploadSurface threw: " + t);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Low-level COM call helpers
    // ────────────────────────────────────────────────────────────────────

    /** Read {@code vtbl[index]} of a COM object given its raw address. */
    private static MemorySegment vfn(long thisPtr, int index) {
        MemorySegment obj = MemorySegment.ofAddress(thisPtr).reinterpret(8);
        long vtbl = obj.get(PTR, 0).address();
        MemorySegment vt = MemorySegment.ofAddress(vtbl).reinterpret((index + 1) * 8L);
        return vt.getAtIndex(PTR, index);
    }

    private static int com0(long thisPtr, int index) {
        try {
            return (int) INV_THIS.invokeExact(vfn(thisPtr, index), MemorySegment.ofAddress(thisPtr));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int comP(long thisPtr, int index, MemorySegment arg) {
        try {
            return (int) INV_P.invokeExact(vfn(thisPtr, index), MemorySegment.ofAddress(thisPtr), arg);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int comF(long thisPtr, int index, float arg) {
        try {
            return (int) INV_F.invokeExact(vfn(thisPtr, index), MemorySegment.ofAddress(thisPtr), arg);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int comPIP(long thisPtr, int index, long p, int i, MemorySegment p2) {
        try {
            return (int) INV_PIP.invokeExact(vfn(thisPtr, index), MemorySegment.ofAddress(thisPtr),
                    MemorySegment.ofAddress(p), i, p2);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int comPIP_ret(long thisPtr, int index, MemorySegment p, int i, MemorySegment out) {
        try {
            return (int) INV_PIP.invokeExact(vfn(thisPtr, index), MemorySegment.ofAddress(thisPtr), p, i, out);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int comIIIIP(long thisPtr, int index, int a, int b, int c, int d, MemorySegment out) {
        try {
            return (int) INV_IIIIP.invokeExact(vfn(thisPtr, index), MemorySegment.ofAddress(thisPtr),
                    a, b, c, d, out);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int comQI(long thisPtr, MemorySegment iid, MemorySegment out) {
        try {
            return (int) INV_QI.invokeExact(vfn(thisPtr, 0), MemorySegment.ofAddress(thisPtr), iid, out);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int comBeginDraw(long surface, MemorySegment rect, MemorySegment iid,
            MemorySegment ppv, MemorySegment offset) {
        try {
            return (int) INV_PPPP.invokeExact(vfn(surface, SURFACE_BEGIN_DRAW),
                    MemorySegment.ofAddress(surface), rect, iid, ppv, offset);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void ctxUpdateSubresource(long ctx, long dstResource, int dstSubresource,
            MemorySegment box, MemorySegment src, int rowPitch, int depthPitch) {
        try {
            INV_UPDATE_SUBRESOURCE.invokeExact(vfn(ctx, CTX_UPDATE_SUBRESOURCE),
                    MemorySegment.ofAddress(ctx), MemorySegment.ofAddress(dstResource),
                    dstSubresource, box, src, rowPitch, depthPitch);
        } catch (Throwable t) {
            log("UpdateSubresource threw: " + t);
        }
    }

    private static void release(long comObj) {
        if (comObj != 0L) {
            com0(comObj, IDX_RELEASE);
        }
    }

    // ── Small invoke wrappers for DLL exports ────────────────────────────
    private static int invokeInt(MethodHandle h, Object... args) {
        try {
            return (int) h.invokeWithArguments(args);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long invokeLong(MethodHandle h, Object... args) {
        try {
            return (long) h.invokeWithArguments(args);
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Task marshalling
    // ────────────────────────────────────────────────────────────────────

    private void runAsync(Runnable r) {
        if (Thread.currentThread() == ownerThread) {
            r.run();
        } else {
            tasks.add(r);
        }
    }

    private void runSync(Runnable r) {
        if (Thread.currentThread() == ownerThread) {
            r.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        tasks.add(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Utilities
    // ────────────────────────────────────────────────────────────────────

    /** Build a 16-byte little-endian GUID segment in the GLOBAL arena. */
    private static MemorySegment guid(int data1, int data2, int data3,
            int b0, int b1, int b2, int b3, int b4, int b5, int b6, int b7) {
        MemorySegment g = GLOBAL.allocate(16);
        g.set(I32, 0, data1);
        g.set(I16, 4, (short) data2);
        g.set(I16, 6, (short) data3);
        g.set(I8, 8, (byte) b0);
        g.set(I8, 9, (byte) b1);
        g.set(I8, 10, (byte) b2);
        g.set(I8, 11, (byte) b3);
        g.set(I8, 12, (byte) b4);
        g.set(I8, 13, (byte) b5);
        g.set(I8, 14, (byte) b6);
        g.set(I8, 15, (byte) b7);
        return g;
    }

    /** Allocate a null-terminated UTF-16LE string. */
    private static MemorySegment wide(Arena a, String s) {
        int len = s.length();
        MemorySegment seg = a.allocate((len + 1) * 2L, 2);
        for (int i = 0; i < len; i++) {
            seg.set(ValueLayout.JAVA_CHAR, i * 2L, s.charAt(i));
        }
        seg.set(ValueLayout.JAVA_CHAR, len * 2L, '\0');
        return seg;
    }

    private static void log(String msg) {
        try {
            Log.info("dcomp", msg);
        } catch (Throwable t) {
            System.err.println("[dcomp] " + msg);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Self-contained smoke test
    // ────────────────────────────────────────────────────────────────────

    /**
     * Visual smoke test: brings up the DComp stage and animates a few solid
     * translucent squares in circles for ~20 s. Proves the whole native
     * pipeline (host window → D3D11 → DComp device/target/visual/surface →
     * per-frame offset + commit) works and that GPU stays low. Does not touch
     * the pet engine.
     *
     * <pre>
     * mvn -q -DskipTests compile
     * mvn -q exec:java -Dexec.mainClass=com.desktoppets.DCompStage
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        if (!WINDOWS) {
            System.out.println("DComp smoke test requires Windows.");
            return;
        }
        DCompStage stage = instance();
        if (!stage.start(5000)) {
            System.out.println("DComp stage failed to start (see log). Falling back would use Swing Stage.");
            return;
        }
        System.out.println("DComp stage ready — animating test squares for 20 s.");

        int n = 3;
        long[] handles = new long[n];
        int[][] colors = {
                {0xFF, 0xEB, 0x3B}, // duck yellow
                {0x4F, 0xC3, 0xF7}, // sky blue
                {0xFF, 0x8A, 0x65}, // coral
        };
        int size = 96;
        for (int i = 0; i < n; i++) {
            handles[i] = stage.createVisual(size, size);
            int[] px = solidRoundedSquare(size, colors[i][0], colors[i][1], colors[i][2], 220);
            stage.updateBitmap(handles[i], px, size, size);
            stage.show(handles[i]);
        }
        stage.commit();

        int cx = stage.originX + 600;
        int cy = stage.originY + 400;
        int radius = 220;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 20_000) {
            double t = (System.currentTimeMillis() - start) / 1000.0;
            for (int i = 0; i < n; i++) {
                double ang = t * 1.2 + i * (2 * Math.PI / n);
                float x = (float) (cx + radius * Math.cos(ang));
                float y = (float) (cy + radius * Math.sin(ang));
                stage.setPosition(handles[i], x, y);
            }
            stage.commit();
            Thread.sleep(16);
        }

        for (long h : handles) {
            stage.destroy(h);
        }
        stage.commit();
        stage.stop();
        System.out.println("DComp smoke test done.");
    }

    /** Premultiplied-ARGB solid rounded square for the smoke test. */
    private static int[] solidRoundedSquare(int size, int r, int g, int b, int alpha) {
        int[] px = new int[size * size];
        int radius = size / 6;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inside = insideRounded(x, y, size, radius);
                if (!inside) {
                    px[y * size + x] = 0; // transparent
                    continue;
                }
                int a = alpha;
                // Premultiply.
                int pr = r * a / 255;
                int pg = g * a / 255;
                int pb = b * a / 255;
                px[y * size + x] = (a << 24) | (pr << 16) | (pg << 8) | pb;
            }
        }
        return px;
    }

    private static boolean insideRounded(int x, int y, int size, int radius) {
        int minX = radius, maxX = size - 1 - radius;
        int minY = radius, maxY = size - 1 - radius;
        int cx = Math.max(minX, Math.min(x, maxX));
        int cy = Math.max(minY, Math.min(y, maxY));
        int dx = x - cx;
        int dy = y - cy;
        return dx * dx + dy * dy <= radius * radius;
    }
}
