using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace UIScope.Helper
{
    /// <summary>Win32 interop for window enumeration, rects, hit-testing, capture and elevation checks (plan P3-1).</summary>
    internal static class Native
    {
        [StructLayout(LayoutKind.Sequential)]
        public struct RECT
        {
            public int Left, Top, Right, Bottom;

            public int Width { get { return Right - Left; } }

            public int Height { get { return Bottom - Top; } }
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct POINT
        {
            public int X;
            public int Y;
        }

        /// <summary>The low-level mouse hook's payload; only the cursor position is read.</summary>
        [StructLayout(LayoutKind.Sequential)]
        public struct MSLLHOOKSTRUCT
        {
            public POINT pt;
            public uint mouseData;
            public uint flags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        public delegate IntPtr LowLevelProc(int code, IntPtr wParam, IntPtr lParam);

        // -- windows ----------------------------------------------------------------------------
        [DllImport("user32.dll")]
        public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

        [DllImport("user32.dll")]
        public static extern bool IsWindowVisible(IntPtr hWnd);

        [DllImport("user32.dll")]
        public static extern bool IsWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        public static extern bool IsIconic(IntPtr hWnd);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        public static extern int GetWindowTextW(IntPtr hWnd, StringBuilder text, int count);

        [DllImport("user32.dll")]
        public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);

        [DllImport("user32.dll")]
        public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);

        [DllImport("user32.dll")]
        public static extern bool IsHungAppWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        public static extern IntPtr GetShellWindow();

        [DllImport("user32.dll")]
        public static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll")]
        public static extern IntPtr GetAncestor(IntPtr hWnd, uint flags);

        [DllImport("user32.dll", EntryPoint = "GetWindowLongW")]
        public static extern int GetWindowLong(IntPtr hWnd, int index);

        [DllImport("user32.dll")]
        public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdcBlt, uint flags);

        [DllImport("user32.dll", SetLastError = true)]
        public static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int x, int y, int cx, int cy, uint flags);

        [DllImport("user32.dll")]
        public static extern bool SetWindowDisplayAffinity(IntPtr hWnd, uint affinity);

        [DllImport("dwmapi.dll")]
        public static extern int DwmGetWindowAttribute(IntPtr hWnd, int attribute, out int value, int size);

        // -- pointer, keyboard, hook ------------------------------------------------------------
        [DllImport("user32.dll")]
        public static extern bool GetCursorPos(out POINT point);

        [DllImport("user32.dll")]
        public static extern IntPtr WindowFromPoint(POINT point);

        [DllImport("user32.dll")]
        public static extern short GetAsyncKeyState(int virtualKey);

        [DllImport("user32.dll", SetLastError = true)]
        public static extern IntPtr SetWindowsHookEx(int hookId, LowLevelProc proc, IntPtr module, uint threadId);

        [DllImport("user32.dll", SetLastError = true)]
        public static extern bool UnhookWindowsHookEx(IntPtr hook);

        [DllImport("user32.dll")]
        public static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr wParam, IntPtr lParam);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
        public static extern IntPtr GetModuleHandle(string name);

        // -- processes --------------------------------------------------------------------------
        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);

        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern bool CloseHandle(IntPtr handle);

        [DllImport("advapi32.dll", SetLastError = true)]
        public static extern bool OpenProcessToken(IntPtr process, uint desiredAccess, out IntPtr token);

        [DllImport("advapi32.dll", SetLastError = true)]
        public static extern bool GetTokenInformation(
            IntPtr token, int informationClass, out uint info, uint length, out uint returnLength);

        public const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
        public const uint TOKEN_QUERY = 0x0008;
        public const int TokenElevation = 20;
        public const int ERROR_ACCESS_DENIED = 5;

        public const uint GA_ROOT = 2;
        public const uint PW_RENDERFULLCONTENT = 0x2;
        public const int GWL_EXSTYLE = -20;
        public const int WS_EX_TOOLWINDOW = 0x00000080;
        public const int WS_EX_APPWINDOW = 0x00040000;
        public const int DWMWA_CLOAKED = 14;
        public const uint WDA_EXCLUDEFROMCAPTURE = 0x11;

        public const uint SWP_NOACTIVATE = 0x0010;
        public const uint SWP_SHOWWINDOW = 0x0040;
        public const uint SWP_HIDEWINDOW = 0x0080;
        public static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);

        public const int WH_MOUSE_LL = 14;
        public const int WM_LBUTTONDOWN = 0x0201;
        public const int WM_LBUTTONUP = 0x0202;
        public const int WM_LBUTTONDBLCLK = 0x0203;
        public const int WM_RBUTTONDOWN = 0x0204;
        public const int WM_RBUTTONUP = 0x0205;
        public const int WM_RBUTTONDBLCLK = 0x0206;
        public const int VK_ESCAPE = 0x1B;

        private static bool? currentElevated;

        /// <summary>
        /// True when the process runs elevated. When the process cannot be opened at all, "access denied" means it runs at a higher
        /// integrity level than we do (elevated or protected); any other failure (the process just exited, an invalid id) is not elevation.
        /// </summary>
        public static bool IsElevated(uint pid)
        {
            IntPtr process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
            if (process == IntPtr.Zero) return Marshal.GetLastWin32Error() == ERROR_ACCESS_DENIED;
            try
            {
                IntPtr token;
                if (!OpenProcessToken(process, TOKEN_QUERY, out token)) return Marshal.GetLastWin32Error() == ERROR_ACCESS_DENIED;
                try
                {
                    uint elevation;
                    uint returned;
                    bool ok = GetTokenInformation(token, TokenElevation, out elevation, 4, out returned);
                    return ok && elevation != 0;
                }
                finally { CloseHandle(token); }
            }
            finally { CloseHandle(process); }
        }

        /// <summary>Whether the helper itself is elevated. An elevated helper can inspect every window, so no target is "too elevated" for it.</summary>
        public static bool IsCurrentProcessElevated()
        {
            if (!currentElevated.HasValue) currentElevated = IsElevated((uint)Process.GetCurrentProcess().Id);
            return currentElevated.Value;
        }

        public static string WindowTitle(IntPtr hWnd)
        {
            var sb = new StringBuilder(512);
            int len = GetWindowTextW(hWnd, sb, sb.Capacity);
            return len > 0 ? sb.ToString(0, len) : "";
        }

        /// <summary>UWP and virtual-desktop ghosts are "visible" but cloaked: they are not on screen and must not clutter the window list.</summary>
        public static bool IsCloaked(IntPtr hWnd)
        {
            try
            {
                int cloaked;
                return DwmGetWindowAttribute(hWnd, DWMWA_CLOAKED, out cloaked, 4) == 0 && cloaked != 0;
            }
            catch (Exception)
            {
                return false; // dwmapi missing: nothing is cloaked
            }
        }

        public static bool IsToolWindow(IntPtr hWnd)
        {
            int style = GetWindowLong(hWnd, GWL_EXSTYLE);
            return (style & WS_EX_TOOLWINDOW) != 0 && (style & WS_EX_APPWINDOW) == 0;
        }
    }
}
