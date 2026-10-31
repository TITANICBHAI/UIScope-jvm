using System;
using System.Collections.Generic;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Automation;
using System.Windows.Forms;

namespace UIScope.Helper
{
    /// <summary>
    /// One pick session (plan P3-4): a click-through overlay spanning all monitors, a hover timer
    /// (~30 Hz) outlining the element under the cursor, and a low-level mouse hook that exists only
    /// while picking and swallows the pick click. Esc is polled (no keyboard hooks, ever).
    /// Runs on the caller's STA thread: Start blocks on an internal message pump until Stop.
    /// </summary>
    /// <summary>The data of one picked element, as the protocol carries it (Appendix J pickResult).</summary>
    internal class PickedElementInfo
    {
        public string RuntimeId = "";
        public string AutomationId = "";
        public string Name = "";
        public string ControlType = "";
        public System.Drawing.Rectangle Rect = System.Drawing.Rectangle.Empty;
    }

    internal sealed class PickSession
    {
        private const int WH_MOUSE_LL = 14;
        private const int WM_LBUTTONDOWN = 0x0201;
        private const int WM_RBUTTONDOWN = 0x0204;
        private const int VK_ESCAPE = 0x1B;
        private const int GWL_EXSTYLE = -20;
        private const int WS_EX_TRANSPARENT = 0x20;
        private const int WS_EX_LAYERED = 0x80000;
        private const int WS_EX_NOACTIVATE = 0x08000000;
        private const uint WDA_EXCLUDEFROMCAPTURE = 0x11;

        private readonly Action<PickedElementInfo, IntPtr> onPicked;
        private readonly Action onCancelled;

        private Form anchor;                           // message-pump anchor; closing it ends the session
        private Form overlay;                          // click-through full-screen layer
        private Form outline;                          // the 2 px accent outline
        private IntPtr mouseHook;
        private readonly MouseHookProc hookProc;
        private Timer hoverTimer;
        private AutomationElement lastHovered;
        private bool finished;

        public PickSession(Action<PickedElementInfo, IntPtr> onPicked, Action onCancelled)
        {
            this.onPicked = onPicked;
            this.onCancelled = onCancelled;
            hookProc = HookCallback;
        }

        public void Start(bool hideFromCapture)
        {
            var screen = SystemInformation.VirtualScreen;

            anchor = new Form();
            anchor.FormBorderStyle = FormBorderStyle.None;
            anchor.ShowInTaskbar = false;
            anchor.StartPosition = FormStartPosition.Manual;
            anchor.Bounds = new Rectangle(-2000, -2000, 1, 1);

            overlay = new Form();
            overlay.FormBorderStyle = FormBorderStyle.None;
            overlay.StartPosition = FormStartPosition.Manual;
            overlay.Bounds = screen;
            overlay.BackColor = Color.Magenta;
            overlay.TransparencyKey = Color.Magenta;
            overlay.ShowInTaskbar = false;
            overlay.TopMost = true;

            // Click-through: the overlay never receives mouse input; the hook sees everything.
            SetWindowLong(overlay.Handle, GWL_EXSTYLE, WS_EX_TRANSPARENT | WS_EX_LAYERED | WS_EX_NOACTIVATE);
            if (hideFromCapture)
            {
                // Windows 10 2004+: the overlay vanishes from screenshots; older systems hide it during capture (client side).
                try { SetWindowDisplayAffinity(overlay.Handle, WDA_EXCLUDEFROMCAPTURE); }
                catch { /* pre-2004: the client hides the overlay around captures */ }
            }

            outline = new Form();
            outline.FormBorderStyle = FormBorderStyle.None;
            outline.StartPosition = FormStartPosition.Manual;
            outline.ShowInTaskbar = false;
            outline.TopMost = true;
            outline.BackColor = Color.Red;
            outline.MinimumSize = new Size(2, 2);

            overlay.Show();
            outline.Show();

            hoverTimer = new Timer();
            hoverTimer.Interval = 33; // ~30 Hz (plan 4.4)
            hoverTimer.Tick += OnHoverTick;
            hoverTimer.Start();

            mouseHook = SetWindowsHookEx(WH_MOUSE_LL, hookProc, IntPtr.Zero, 0);

            // Blocks the session thread, pumping messages for the hook and the timer, until Stop.
            Application.Run(anchor);
            Stop();
        }

        public void Stop()
        {
            if (finished) return;
            finished = true;
            if (hoverTimer != null) hoverTimer.Stop();
            if (mouseHook != IntPtr.Zero) UnhookWindowsHookEx(mouseHook);
            if (outline != null) outline.Close();
            if (overlay != null) overlay.Close();
            if (anchor != null) anchor.Close();
            outline = null;
            overlay = null;
            mouseHook = IntPtr.Zero;
        }

        private void OnHoverTick()
        {
            if (finished) return;
            // Esc is polled, never hooked (plan: no keyboard hooks).
            if ((GetAsyncKeyState(VK_ESCAPE) & 0x8000) != 0)
            {
                FinishCancelled();
                return;
            }
            var cursor = Cursor.Position;
            AutomationElement element = null;
            try { element = AutomationElement.FromPoint(new Point(cursor.X, cursor.Y)); }
            catch { element = null; }
            if (element == null) return;

            lastHovered = element;
            var rect = ElementRect(element);
            if (outline != null)
            {
                outline.SetBounds(rect.Left - 2, rect.Top - 2, rect.Width + 4, rect.Height + 4);
            }
            EmitLine("{\"event\":\"pickHover\",\"element\":{\"controlType\":" + JsonQ(ControlTypeName(element)) +
                ",\"name\":" + JsonQ(SafeName(element)) +
                ",\"rect\":{\"x\":" + rect.Left + ",\"y\":" + rect.Top + ",\"width\":" + rect.Width + ",\"height\":" + rect.Height + "}}}");
        }

        private IntPtr HookCallback(int code, IntPtr wParam, IntPtr lParam)
        {
            if (code >= 0 && !finished)
            {
                int message = wParam.ToInt32();
                if (message == WM_LBUTTONDOWN)
                {
                    FinishPicked();
                    return (IntPtr)1; // the pick click never reaches the target application
                }
                if (message == WM_RBUTTONDOWN)
                {
                    FinishCancelled();
                    return (IntPtr)1;
                }
            }
            return CallNextHookEx(mouseHook, code, wParam, lParam);
        }

        private void FinishPicked()
        {
            if (finished) return;
            finished = true;
            var element = lastHovered;
            var rect = element != null ? ElementRect(element) : System.Drawing.Rectangle.Empty;
            string runtimeId = RuntimeId(element);
            string automationId = SafeAutomationId(element);
            string name = SafeName(element);
            string controlType = ControlTypeName(element);
            IntPtr hwnd = TopLevelHwnd(element);

            EmitLine("{\"event\":\"pickResult\",\"hwnd\":" + JsonQ("0x" + hwnd.ToInt64().ToString("x8")) +
                ",\"element\":{\"runtimeId\":" + JsonQ(runtimeId) +
                ",\"automationId\":" + JsonQ(automationId) +
                ",\"name\":" + JsonQ(name) +
                ",\"controlType\":" + JsonQ(controlType) +
                ",\"rect\":{\"x\":" + rect.Left + ",\"y\":" + rect.Top +
                ",\"width\":" + rect.Width + ",\"height\":" + rect.Height + "}}}");
            var info = new PickedElementInfo();
            info.RuntimeId = runtimeId;
            info.AutomationId = automationId;
            info.Name = name;
            info.ControlType = controlType;
            info.Rect = rect;
            if (onPicked != null) onPicked(info, hwnd);
            Stop();
        }

        private void FinishCancelled()
        {
            if (finished) return;
            finished = true;
            EmitLine("{\"event\":\"pickCancelled\"}");
            if (onCancelled != null) onCancelled();
            Stop();
        }

        // ── element helpers (every read guarded; password values are never read) ──

        private static System.Drawing.Rectangle ElementRect(AutomationElement e)
        {
            try
            {
                // BoundingRectangle is a System.Windows.Rect (WindowsBase) - convert explicitly.
                var r = e.Current.BoundingRectangle;
                return System.Drawing.Rectangle.FromLTRB((int)r.Left, (int)r.Top, (int)r.Right, (int)r.Bottom);
            }
            catch { return System.Drawing.Rectangle.Empty; }
        }

        private static string SafeName(AutomationElement e)
        {
            try { return e.Current.Name ?? ""; }
            catch { return ""; }
        }

        private static string SafeAutomationId(AutomationElement e)
        {
            try { return e.Current.AutomationId ?? ""; }
            catch { return ""; }
        }

        private static string ControlTypeName(AutomationElement e)
        {
            try { return e.Current.ControlType.ProgrammaticName.Replace("ControlType.", ""); }
            catch { return ""; }
        }

        private static string RuntimeId(AutomationElement e)
        {
            try { return string.Join(" ", e.GetRuntimeId()); }
            catch { return ""; }
        }

        /// <summary>Walks to the top-level ancestor and returns its native handle (0 when none).</summary>
        private static IntPtr TopLevelHwnd(AutomationElement e)
        {
            try
            {
                var walker = TreeWalker.ControlViewWalker;
                var current = e;
                var parent = walker.GetParent(current);
                while (parent != null && parent != AutomationElement.RootElement)
                {
                    current = parent;
                    parent = walker.GetParent(current);
                }
                int handle = current.Current.NativeWindowHandle;
                return new IntPtr(handle);
            }
            catch { return IntPtr.Zero; }
        }

        private static void EmitLine(string line)
        {
            Program.EmitLine(line);
        }

        private static string JsonQ(string s)
        {
            if (s == null) return "null";
            return "\"" + s.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\n", "\\n").Replace("\r", "\\r").Replace("\t", "\\t") + "\"";
        }

        // ── Win32 ──

        private delegate IntPtr HookProc(int code, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr SetWindowsHookEx(int idHook, HookProc proc, IntPtr module, uint threadId);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool UnhookWindowsHookEx(IntPtr hook);

        [DllImport("user32.dll")]
        private static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern int SetWindowLong(IntPtr hWnd, int index, int style);

        [DllImport("user32.dll")]
        private static extern bool SetWindowDisplayAffinity(IntPtr hWnd, uint affinity);

        [DllImport("user32.dll")]
        private static extern short GetAsyncKeyState(int key);
    }
}
