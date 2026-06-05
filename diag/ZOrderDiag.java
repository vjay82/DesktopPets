import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Standalone diagnostic: dumps the true top-level window Z-order (front to
 * back, via GetTopWindow + GetWindow(GW_HWNDNEXT)), with each window's
 * topmost / minimised / maximised / cloaked state, rect, class and title,
 * plus the foreground window, shell window and taskbar ABM_GETSTATE.
 *
 * Run with JDK 22:
 *   java --enable-native-access=ALL-UNNAMED diag\ZOrderDiag.java
 */
public final class ZOrderDiag {

    static final Linker L = Linker.nativeLinker();
    static final SymbolLookup U32 = SymbolLookup.libraryLookup("user32", Arena.global());
    static final SymbolLookup SHELL = SymbolLookup.libraryLookup("shell32", Arena.global());
    static final SymbolLookup DWM = SymbolLookup.libraryLookup("dwmapi", Arena.global());

    static MethodHandle h(SymbolLookup lib, String name, FunctionDescriptor fd) {
        return L.downcallHandle(lib.find(name).orElseThrow(), fd);
    }

    static final MethodHandle GetTopWindow = h(U32, "GetTopWindow",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle GetWindow = h(U32, "GetWindow",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle IsWindowVisible = h(U32, "IsWindowVisible",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle IsIconic = h(U32, "IsIconic",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle IsZoomed = h(U32, "IsZoomed",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle GetWindowRect = h(U32, "GetWindowRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle GetWindowLongPtr = h(U32, "GetWindowLongPtrW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
    static final MethodHandle GetWindowTextA = h(U32, "GetWindowTextA",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle GetClassNameA = h(U32, "GetClassNameA",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle GetForegroundWindow = h(U32, "GetForegroundWindow",
            FunctionDescriptor.of(ADDRESS));
    static final MethodHandle GetShellWindow = h(U32, "GetShellWindow",
            FunctionDescriptor.of(ADDRESS));
    static final MethodHandle SHAppBarMessage = h(SHELL, "SHAppBarMessage",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS));
    static final MethodHandle DwmGetWindowAttribute = h(DWM, "DwmGetWindowAttribute",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

    static final int GWL_EXSTYLE = -20;
    static final long WS_EX_TOPMOST = 0x8L;
    static final int GW_HWNDNEXT = 2;
    static final int DWMWA_CLOAKED = 14;

    public static void main(String[] args) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment fg = (MemorySegment) GetForegroundWindow.invoke();
            MemorySegment shell = (MemorySegment) GetShellWindow.invoke();
            System.out.println("Foreground HWND = 0x" + Long.toHexString(fg.address()));
            System.out.println("Shell      HWND = 0x" + Long.toHexString(shell.address()));

            MemorySegment abd = a.allocate(64);
            abd.set(JAVA_INT, 0, 48); // cbSize = sizeof(APPBARDATA) on x64
            long state = (long) SHAppBarMessage.invoke(0x4, abd); // ABM_GETSTATE
            System.out.println("Taskbar ABM_GETSTATE = " + state
                    + "  (ALWAYSONTOP=" + ((state & 0x2) != 0) + ", AUTOHIDE=" + ((state & 0x1) != 0) + ")");
            System.out.println();

            System.out.printf("%-4s %-18s %-4s %-4s %-4s %-4s %-26s %-22s %s%n",
                    "idx", "hwnd", "top", "min", "max", "clk", "rect[l,t,r,b]", "class", "title");
            System.out.println("-".repeat(150));

            MemorySegment buf = a.allocate(512);
            MemorySegment rect = a.allocate(16);
            MemorySegment cloak = a.allocate(4);

            int stageIdx = -1;
            int npppIdx = -1;
            int fgIdx = -1;
            int taskbarIdx = -1;

            MemorySegment hwnd = (MemorySegment) GetTopWindow.invoke(MemorySegment.NULL);
            int idx = 0;
            while (hwnd != null && hwnd.address() != 0) {
                int vis = (int) IsWindowVisible.invoke(hwnd);
                if (vis != 0) {
                    long ex = (long) GetWindowLongPtr.invoke(hwnd, GWL_EXSTYLE);
                    boolean top = (ex & WS_EX_TOPMOST) != 0;
                    boolean min = ((int) IsIconic.invoke(hwnd)) != 0;
                    boolean max = ((int) IsZoomed.invoke(hwnd)) != 0;

                    boolean clk = false;
                    cloak.set(JAVA_INT, 0, 0);
                    int hr = (int) DwmGetWindowAttribute.invoke(hwnd, DWMWA_CLOAKED, cloak, 4);
                    if (hr == 0 && cloak.get(JAVA_INT, 0) != 0) clk = true;

                    int l = 0, t = 0, r = 0, b = 0;
                    if (((int) GetWindowRect.invoke(hwnd, rect)) != 0) {
                        l = rect.get(JAVA_INT, 0); t = rect.get(JAVA_INT, 4);
                        r = rect.get(JAVA_INT, 8); b = rect.get(JAVA_INT, 12);
                    }

                    int cn = (int) GetClassNameA.invoke(hwnd, buf, 512);
                    String cls = cn > 0 ? buf.getString(0) : "";
                    int tn = (int) GetWindowTextA.invoke(hwnd, buf, 512);
                    String title = tn > 0 ? buf.getString(0) : "";
                    if (title.length() > 40) title = title.substring(0, 40);

                    String marker = "";
                    if (hwnd.address() == fg.address()) { marker += " <FG>"; fgIdx = idx; }
                    if (hwnd.address() == shell.address()) marker += " <SHELL>";
                    if (title.contains("DesktopPets-Stage")) { marker += " <STAGE>"; stageIdx = idx; }
                    if (cls.equals("Shell_TrayWnd") || cls.equals("Shell_SecondaryTrayWnd")) {
                        marker += " <TASKBAR>";
                        if (taskbarIdx < 0) taskbarIdx = idx;
                    }
                    if (title.contains("Notepad++")) { marker += " <NPP>"; if (npppIdx < 0) npppIdx = idx; }

                    boolean interesting = (r - l) > 0 && (b - t) > 0
                            && (!title.isEmpty() || cls.startsWith("Shell_") || !marker.isEmpty());
                    if (interesting) {
                        System.out.printf("%-4d 0x%-16x %-4s %-4s %-4s %-4s [%5d,%5d,%5d,%5d] %-22s %s%s%n",
                                idx, hwnd.address(), top, min, max, clk, l, t, r, b, cls, title, marker);
                    }
                }
                hwnd = (MemorySegment) GetWindow.invoke(hwnd, GW_HWNDNEXT);
                idx++;
                if (idx > 5000) break;
            }

            System.out.println();
            System.out.println("Z-order indices (lower = nearer the front):");
            System.out.println("  STAGE    idx = " + stageIdx);
            System.out.println("  Notepad++ idx = " + npppIdx);
            System.out.println("  TASKBAR  idx = " + taskbarIdx);
            System.out.println("  FG       idx = " + fgIdx);
            if (stageIdx >= 0 && npppIdx >= 0) {
                System.out.println("  => STAGE is " + (stageIdx < npppIdx ? "IN FRONT OF" : "BEHIND")
                        + " Notepad++");
            }
            if (stageIdx >= 0 && taskbarIdx >= 0) {
                System.out.println("  => STAGE is " + (stageIdx < taskbarIdx ? "IN FRONT OF" : "BEHIND")
                        + " the taskbar");
            }
        }
    }
}
