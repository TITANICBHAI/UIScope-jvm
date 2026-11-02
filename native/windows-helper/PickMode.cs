using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Automation;
using System.Windows.Forms;

namespace UIScope.Helper
{
    /// <summary>
    /// The accent frame drawn around the hovered element: a click-through, never-activating, topmost layered window that is transparent
    /// everywhere except its 2 px border. (It used to be a solid red rectangle with no hole: it covered the element, sat under the pointer,
    /// and every hit-test then found the frame itself instead of the element below it.)
    /// </summary>
    internal sealed class OutlineForm : Form
    {
        private const int WS_EX_TOOLWINDOW = 0x00000080;
        private const int WS_EX_TRANSPARENT = 0x00000020;
        private const int WS_EX_LAYERED = 0x00080000;
        private const int WS_EX_TOPMOST = 0x00000008;
        private const int WS_EX_NOACTIVATE = 0x08000000;
        private const int WM_DPICHANGED = 0x02E0;
        private const int WM_NCHITTEST = 0x0084;
        private static readonly IntPtr HTTRANSPARENT = new IntPtr(-1);
        private static readonly Color Key = Color.FromArgb(255, 0, 255);
        private static readonly Color Accent = Color.FromArgb(255, 255, 94, 36);

        public OutlineForm()
        {
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.Manual;
            BackColor = Key;
            TransparencyKey = Key;
            Bounds = new Rectangle(-32000, -32000, 8, 8);
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        }

        protected override bool ShowWithoutActivation
        {
            get { return true; }
        }

        protected override CreateParams CreateParams
        {
            get
            {
                CreateParams cp = base.CreateParams;
                cp.ExStyle |= WS_EX_TOOLWINDOW | WS_EX_TRANSPARENT | WS_EX_LAYERED | WS_EX_TOPMOST | WS_EX_NOACTIVATE;
                return cp;
            }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.Clear(Key);
            int w = ClientSize.Width;
            int h = ClientSize.Height;
            if (w < 4 || h < 4) return;
            using (var accent = new SolidBrush(Accent))
            using (var white = new SolidBrush(Color.White))
            {
                e.Graphics.FillRectangle(accent, 0, 0, w, 2);
                e.Graphics.FillRectangle(accent, 0, h - 2, w, 2);
                e.Graphics.FillRectangle(accent, 0, 0, 2, h);
                e.Graphics.FillRectangle(accent, w - 2, 0, 2, h);
                if (w > 8 && h > 8)
                {
                    // a 1 px light line inside keeps the frame visible on dark surfaces
                    e.Graphics.FillRectangle(white, 2, 2, w - 4, 1);
                    e.Graphics.FillRectangle(white, 2, h - 3, w - 4, 1);
                    e.Graphics.FillRectangle(white, 2, 2, 1, h - 4);
                    e.Graphics.FillRectangle(white, w - 3, 2, 1, h - 4);
                }
            }
        }

        protected override void WndProc(ref Message m)
        {
            // Placement is exact physical pixels set by SetWindowPos; WinForms must not rescale the frame when it lands on another monitor.
            if (m.Msg == WM_DPICHANGED) return;
            if (m.Msg == WM_NCHITTEST)
            {
                m.Result = HTTRANSPARENT;
                return;
            }
            base.WndProc(ref m);
        }

        /// <summary>Puts the frame just outside <paramref name="element"/> (physical pixels); an empty rectangle hides it.</summary>
        public void Surround(Rectangle element)
        {
            if (!IsHandleCreated) return;
            if (element.Width <= 0 || element.Height <= 0)
            {
                Native.SetWindowPos(Handle, Native.HWND_TOPMOST, 0, 0, 0, 0, Native.SWP_HIDEWINDOW | Native.SWP_NOACTIVATE);
                return;
            }
            Native.SetWindowPos(Handle, Native.HWND_TOPMOST, element.X - 2, element.Y - 2, element.Width + 4, element.Height + 4,
                Native.SWP_SHOWWINDOW | Native.SWP_NOACTIVATE);
            Invalidate();
        }
    }

    /// <summary>
    /// One pick: hover to preview, click to choose, Esc or a right-click to cancel. It runs on the host's message loop, with no loop of its own
    /// (starting a second WinForms loop on that thread throws, which is what the first version did the moment it was started).
    ///
    /// Thread rules: the low-level mouse hook runs on the UI thread and does nothing but record the click and return, because a slow hook
    /// freezes the pointer for the whole machine; every UI Automation call (hover queries, finding the top-level window) runs on a worker
    /// thread; only drawing the frame happens on the UI thread.
    /// </summary>
    internal sealed class PickSession
    {
        private readonly HostForm host;
        private readonly object gate = new object();
        private readonly int ownProcessId = System.Diagnostics.Process.GetCurrentProcess().Id;

        private OutlineForm outline;
        private Native.LowLevelProc hookProc; // a field: the delegate must outlive the hook
        private IntPtr hook = IntPtr.Zero;
        private Thread worker;
        private System.Windows.Forms.Timer releaseTimer;

        private volatile bool stopWorker;
        private volatile bool decided;
        private bool started;
        private bool closed;
        private int swallowUpMessage;
        private Native.POINT clickPoint;
        private Inspector.ElementInfo lastHovered;

        public PickSession(HostForm host)
        {
            this.host = host;
        }

        /// <summary>Raised once, on a pool thread, when the session ended by itself (picked or cancelled), after the event line was written.</summary>
        public event Action Ended;

        /// <summary>Starts the session on the UI thread. False (with a log line) when the mouse hook cannot be installed.</summary>
        public bool Start(bool hideFromCapture)
        {
            if (started) return true;
            started = true;

            outline = new OutlineForm();
            outline.Show();
            if (hideFromCapture) Native.SetWindowDisplayAffinity(outline.Handle, Native.WDA_EXCLUDEFROMCAPTURE); // Windows 10 2004+; older systems ignore it

            hookProc = HookCallback;
            hook = Native.SetWindowsHookEx(Native.WH_MOUSE_LL, hookProc, Native.GetModuleHandle(null), 0);
            if (hook == IntPtr.Zero)
            {
                int error = Marshal.GetLastWin32Error();
                Program.Log("the mouse hook could not be installed (error " + error + ")");
                Close();
                return false;
            }

            worker = new Thread(PollLoop);
            worker.IsBackground = true;
            worker.Name = "uiscope-pick";
            worker.SetApartmentState(ApartmentState.MTA);
            worker.Start();
            return true;
        }

        /// <summary>Ends the session without an event (the client asked for it). Safe to call more than once. UI thread.</summary>
        public void Stop()
        {
            decided = true;
            Close();
        }

        // -- UI thread: the hook -----------------------------------------------------------------

        private IntPtr HookCallback(int code, IntPtr wParam, IntPtr lParam)
        {
            if (code >= 0)
            {
                int message = wParam.ToInt32();
                switch (message)
                {
                    case Native.WM_LBUTTONDOWN:
                    case Native.WM_LBUTTONDBLCLK:
                        swallowUpMessage = Native.WM_LBUTTONUP;
                        Decide(true, lParam);
                        return (IntPtr)1; // the click belongs to UIScope, never to the application under it
                    case Native.WM_RBUTTONDOWN:
                    case Native.WM_RBUTTONDBLCLK:
                        swallowUpMessage = Native.WM_RBUTTONUP;
                        Decide(false, lParam);
                        return (IntPtr)1;
                    case Native.WM_LBUTTONUP:
                    case Native.WM_RBUTTONUP:
                        if (swallowUpMessage == message)
                        {
                            swallowUpMessage = 0;
                            UnhookNow();
                            host.BeginInvoke(new Action(Close)); // the frame is torn down after the hook has returned
                            return (IntPtr)1; // the matching button-up, so the application never sees half a click
                        }
                        break;
                }
            }
            return Native.CallNextHookEx(hook, code, wParam, lParam);
        }

        /// <summary>Hook thread: record the decision and hand the real work to the message loop; return at once.</summary>
        private void Decide(bool picked, IntPtr lParam)
        {
            if (decided) return;
            decided = true;
            stopWorker = true;
            try
            {
                var data = (Native.MSLLHOOKSTRUCT)Marshal.PtrToStructure(lParam, typeof(Native.MSLLHOOKSTRUCT));
                clickPoint = data.pt;
            }
            catch (Exception)
            {
                Native.GetCursorPos(out clickPoint);
            }
            host.BeginInvoke(new Action(delegate { Finish(picked); }));
        }

        // -- UI thread: finishing ----------------------------------------------------------------

        private void Finish(bool picked)
        {
            Inspector.ElementInfo element;
            lock (gate) { element = lastHovered; }
            if (outline != null && !outline.IsDisposed) outline.Hide();
            // If the button-up never arrives (the hook was removed by the system, the user released elsewhere) the hook must not stay.
            ReleaseHookSoon(1500);

            Native.POINT point = clickPoint;
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    if (picked && element != null)
                    {
                        IntPtr top = TopLevelOf(point, element);
                        Program.EmitLine("{\"event\":\"pickResult\",\"hwnd\":" + Json.Str(Json.Hwnd(top)) + ",\"element\":" + element.Json() + "}");
                    }
                    else
                    {
                        Program.EmitLine("{\"event\":\"pickCancelled\"}");
                    }
                }
                catch (Exception e)
                {
                    Program.Log("finishing the pick failed: " + e.Message);
                    Program.EmitLine("{\"event\":\"pickCancelled\"}");
                }
                finally
                {
                    Action ended = Ended;
                    if (ended != null) ended();
                }
            });
        }

        /// <summary>The top-level window under the click: Win32 first (instant), then up the UI Automation parents.</summary>
        private IntPtr TopLevelOf(Native.POINT point, Inspector.ElementInfo element)
        {
            IntPtr child = Native.WindowFromPoint(point);
            if (child != IntPtr.Zero)
            {
                IntPtr root = Native.GetAncestor(child, Native.GA_ROOT);
                if (root != IntPtr.Zero)
                {
                    uint pid;
                    Native.GetWindowThreadProcessId(root, out pid);
                    if (pid != (uint)ownProcessId) return root;
                }
            }
            return TopLevelByAutomation(element.Element);
        }

        private static IntPtr TopLevelByAutomation(AutomationElement start)
        {
            try
            {
                AutomationElement desktop = AutomationElement.RootElement;
                AutomationElement current = start;
                while (current != null)
                {
                    AutomationElement parent = TreeWalker.ControlViewWalker.GetParent(current);
                    if (parent == null || parent == desktop)
                    {
                        int handle = current.Current.NativeWindowHandle;
                        return new IntPtr(handle);
                    }
                    current = parent;
                }
            }
            catch (Exception)
            {
                // fall through
            }
            return IntPtr.Zero;
        }

        /// <summary>Removes the hook (and the frame) after <paramref name="delayMs"/>; 0 means right now. UI thread.</summary>
        private void ReleaseHookSoon(int delayMs)
        {
            if (delayMs <= 0)
            {
                Close();
                return;
            }
            if (releaseTimer != null) return;
            releaseTimer = new System.Windows.Forms.Timer();
            releaseTimer.Interval = delayMs;
            releaseTimer.Tick += delegate { Close(); };
            releaseTimer.Start();
        }

        private void UnhookNow()
        {
            IntPtr installed = hook;
            hook = IntPtr.Zero;
            if (installed != IntPtr.Zero) Native.UnhookWindowsHookEx(installed);
        }

        private void Close()
        {
            if (closed) return;
            closed = true;
            stopWorker = true;
            if (releaseTimer != null)
            {
                releaseTimer.Stop();
                releaseTimer.Dispose();
                releaseTimer = null;
            }
            UnhookNow();
            if (outline != null && !outline.IsDisposed)
            {
                outline.Close();
                outline.Dispose();
            }
            outline = null;
        }

        // -- worker thread: hover ----------------------------------------------------------------

        private void PollLoop()
        {
            int spins = 0;
            string lastFailure = null;
            while (!stopWorker)
            {
                try
                {
                    // An Esc that was already down when the pick began (it dismissed something else) must not cancel it at once.
                    if (spins++ > 6 && (Native.GetAsyncKeyState(Native.VK_ESCAPE) & 0x8000) != 0)
                    {
                        EscapePressed();
                        return;
                    }
                    Native.POINT cursor;
                    if (Native.GetCursorPos(out cursor)) Hover(cursor);
                    lastFailure = null;
                }
                catch (Exception e)
                {
                    if (e.Message != lastFailure) Program.Log("hover query failed: " + e.Message); // once per distinct failure, not 30 times a second
                    lastFailure = e.Message;
                }
                Thread.Sleep(30);
            }
        }

        private void Hover(Native.POINT cursor)
        {
            Inspector.ElementInfo info = Inspector.ElementAt(cursor.X, cursor.Y);
            if (info == null || info.ProcessId == ownProcessId) return; // never the helper's own windows
            bool changed;
            lock (gate)
            {
                changed = lastHovered == null || lastHovered.RuntimeId != info.RuntimeId || lastHovered.Rect != info.Rect;
                if (changed) lastHovered = info;
            }
            if (!changed || stopWorker) return;
            Rectangle rect = info.Rect;
            host.BeginInvoke(new Action(delegate
            {
                if (outline != null && !outline.IsDisposed) outline.Surround(rect);
            }));
            Program.EmitLine("{\"event\":\"pickHover\",\"element\":" + info.Json() + "}");
        }

        private void EscapePressed()
        {
            if (decided) return;
            decided = true;
            stopWorker = true;
            host.BeginInvoke(new Action(delegate { Finish(false); }));
        }
    }
}
