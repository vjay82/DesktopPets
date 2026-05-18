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
import java.util.List;

/**
 * Standalone diagnostic: enumerate every visible top-level window on the
 * desktop, look up its owning process image, and print the ones belonging to
 * {@code EXPLORER.EXE} together with class name, title, and rect. Used to
 * identify the Windows shell "bar" windows (taskbar, secondary taskbars,
 * notification overflow, etc.) that the pets should treat as off-limits
 * when computing the desktop work area.
 *
 * <p>Run with: {@code mvn -q exec:java -Dexec.mainClass=com.desktoppets.tools.ListExplorerWindows}
 * — or just from the IDE.
 *
 * <p>Output columns: {@code HWND  PID  RECT(x,y,w,h)  CLASS  "TITLE"}.
 */
public final class ListExplorerWindows {

    private static final int GWL_EXSTYLE = -20;
    private static final long WS_EX_TOPMOST = 0x00000008L;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    private static final Arena ARENA = Arena.ofShared();
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup USER32   = SymbolLookup.libraryLookup("user32",   ARENA);
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", ARENA);

    private static final MethodHandle ENUM_WINDOWS = LINKER.downcallHandle(
            USER32.find("EnumWindows").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle IS_WINDOW_VISIBLE = LINKER.downcallHandle(
            USER32.find("IsWindowVisible").orElseThrow(),
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

    private static final List<Row> ROWS = new ArrayList<>();

    private static final MemorySegment ENUM_STUB;
    static {
        try {
            MethodHandle h = MethodHandles.lookup().findStatic(
                    ListExplorerWindows.class, "enumProc",
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
        System.out.printf("%-18s %-7s %-26s %-32s %-9s %s%n",
                "HWND", "PID", "RECT(x,y,w,h)", "CLASS", "TOPMOST", "TITLE / IMAGE");
        System.out.println("-".repeat(140));
        for (Row r : ROWS) {
            if (r.image == null || !r.image.toLowerCase().endsWith("\\explorer.exe")) {
                continue;
            }
            System.out.printf("0x%016x %-7d %-26s %-32s %-9s \"%s\"%n",
                    r.hwnd, r.pid,
                    "(" + r.x + "," + r.y + "," + r.w + "," + r.h + ")",
                    r.cls, r.topmost ? "yes" : "no", r.title);
        }
        System.out.println();
        System.out.println("(Only EXPLORER.EXE windows are shown. Pass -all to dump everything.)");
        if (args.length > 0 && "-all".equals(args[0])) {
            System.out.println();
            System.out.println("ALL TOP-LEVEL WINDOWS:");
            for (Row r : ROWS) {
                System.out.printf("0x%016x %-7d %-26s %-32s  %s | %s%n",
                        r.hwnd, r.pid,
                        "(" + r.x + "," + r.y + "," + r.w + "," + r.h + ")",
                        r.cls, r.image, r.title);
            }
        }
    }

    @SuppressWarnings("unused") // upcall stub
    private static int enumProc(MemorySegment hwnd, long lparam) {
        try {
            if ((int) IS_WINDOW_VISIBLE.invoke(hwnd) == 0) return 1;
            try (Arena a = Arena.ofConfined()) {
                // RECT
                MemorySegment rect = a.allocate(MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_INT));
                if ((int) GET_WINDOW_RECT.invoke(hwnd, rect) == 0) return 1;
                int l = rect.getAtIndex(ValueLayout.JAVA_INT, 0);
                int t = rect.getAtIndex(ValueLayout.JAVA_INT, 1);
                int r = rect.getAtIndex(ValueLayout.JAVA_INT, 2);
                int b = rect.getAtIndex(ValueLayout.JAVA_INT, 3);

                // class name
                MemorySegment cnBuf = a.allocate(256);
                int cnLen = (int) GET_CLASS_NAME.invoke(hwnd, cnBuf, 256);
                String cls = cnLen > 0 ? cnBuf.reinterpret(cnLen + 1).getString(0, StandardCharsets.US_ASCII) : "";

                // title
                MemorySegment titleBuf = a.allocate(512);
                int tLen = (int) GET_WINDOW_TEXT.invoke(hwnd, titleBuf, 512);
                String title = tLen > 0 ? titleBuf.reinterpret(tLen + 1).getString(0, StandardCharsets.US_ASCII) : "";

                // pid
                MemorySegment pidBuf = a.allocate(ValueLayout.JAVA_INT);
                GET_WINDOW_THREAD_PROCESS_ID.invoke(hwnd, pidBuf);
                int pid = pidBuf.get(ValueLayout.JAVA_INT, 0);

                // image path
                String image = processImage(pid);

                // topmost
                long ex = (long) GET_WINDOW_LONG_PTR.invoke(hwnd, GWL_EXSTYLE);
                boolean topmost = (ex & WS_EX_TOPMOST) != 0;

                Row row = new Row();
                row.hwnd = hwnd.address();
                row.pid = pid;
                row.x = l; row.y = t; row.w = r - l; row.h = b - t;
                row.cls = cls; row.title = title; row.image = image; row.topmost = topmost;
                ROWS.add(row);
            }
        } catch (Throwable t) {
            // continue enumeration
        }
        return 1;
    }

    private static String processImage(int pid) {
        try {
            MemorySegment h = (MemorySegment) OPEN_PROCESS.invoke(
                    PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if (h == null || h.address() == 0) return null;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment buf = a.allocate(1024);
                MemorySegment sizeRef = a.allocate(ValueLayout.JAVA_INT);
                sizeRef.set(ValueLayout.JAVA_INT, 0, 1024);
                int ok = (int) QUERY_FULL_PROCESS_IMAGE_NAME.invoke(h, 0, buf, sizeRef);
                CLOSE_HANDLE.invoke(h);
                if (ok == 0) return null;
                int len = sizeRef.get(ValueLayout.JAVA_INT, 0);
                return buf.reinterpret(len + 1).getString(0, StandardCharsets.US_ASCII);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class Row {
        long hwnd;
        int pid;
        int x, y, w, h;
        String cls;
        String title;
        String image;
        boolean topmost;
    }

    private ListExplorerWindows() {}
}
