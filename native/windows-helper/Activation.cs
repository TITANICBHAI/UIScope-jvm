using System;
using System.Diagnostics;
using System.Threading;

namespace UIScope.Helper
{
    /// <summary>
    /// Restoring and raising windows. A minimized window has no pixels, so inspecting one used to be a dead end, and the screenshot
    /// fallback copies the screen, which only shows the window when nothing lies on top of it. A capture that should look right therefore
    /// brings the window forward first. Windows only lets the foreground process change the foreground window, so
    /// <see cref="BringToFront"/> asks politely and then uses the tricks every window switcher relies on.
    /// </summary>
    internal static class Activation
    {
        /// <summary>What <see cref="Show"/> found and did.</summary>
        internal sealed class Shown
        {
            public bool WasMinimized;
            public bool Foreground;
        }

        private const int RestoreWaitMs = 1500;
        private const int PollMs = 25;

        /// <summary>
        /// Restores the window when it is minimized, brings it to the front and lets it repaint for <paramref name="settleMs"/>.
        /// A window that refuses to come forward is reported through <see cref="Shown.Foreground"/>, never thrown.
        /// </summary>
        internal static Shown Show(IntPtr hwnd, int settleMs)
        {
            var result = new Shown();
            result.WasMinimized = Native.IsIconic(hwnd);
            bool alreadyFront = !result.WasMinimized && IsForeground(RootOf(hwnd));
            if (result.WasMinimized) Restore(hwnd);
            result.Foreground = BringToFront(hwnd);
            // A window that was already in front and visible needs no time to repaint; one that just moved does.
            if (settleMs > 0 && !alreadyFront) Thread.Sleep(Math.Min(settleMs, 2000));
            return result;
        }

        /// <summary>Un-minimizes the window and waits (briefly) until it really is.</summary>
        internal static void Restore(IntPtr hwnd)
        {
            Native.ShowWindowAsync(hwnd, Native.SW_RESTORE);
            if (WaitUntil(delegate { return !Native.IsIconic(hwnd); }, RestoreWaitMs)) return;
            // Some apps ignore SW_RESTORE; the system menu's Restore command is what the taskbar sends.
            Native.PostMessage(hwnd, Native.WM_SYSCOMMAND, new IntPtr(Native.SC_RESTORE), IntPtr.Zero);
            WaitUntil(delegate { return !Native.IsIconic(hwnd); }, RestoreWaitMs);
        }

        /// <summary>True when <paramref name="hwnd"/> (or the top-level window it belongs to) is the foreground window afterwards.</summary>
        internal static bool BringToFront(IntPtr hwnd)
        {
            if (!Native.IsWindow(hwnd)) return false;
            IntPtr root = RootOf(hwnd);
            if (IsForeground(root)) return true;

            // 1. The polite way; it works whenever this process was the last to get input.
            Native.SetForegroundWindow(root);
            if (WaitUntil(delegate { return IsForeground(root); }, 120)) return true;

            // 2. A tap of an unassigned key makes this process "the one that last got input", which unlocks SetForegroundWindow, and
            //    sharing the input queue with the current foreground window and the target does the rest.
            Native.keybd_event(Native.VK_F24, 0, 0, UIntPtr.Zero);
            Native.keybd_event(Native.VK_F24, 0, Native.KEYEVENTF_KEYUP, UIntPtr.Zero);
            uint me = Native.GetCurrentThreadId();
            uint ignored;
            IntPtr front = Native.GetForegroundWindow();
            uint frontThread = front == IntPtr.Zero ? 0 : Native.GetWindowThreadProcessId(front, out ignored);
            uint targetThread = Native.GetWindowThreadProcessId(root, out ignored);
            bool attachedFront = frontThread != 0 && frontThread != me && Native.AttachThreadInput(me, frontThread, true);
            bool attachedTarget = targetThread != 0 && targetThread != me && targetThread != frontThread && Native.AttachThreadInput(me, targetThread, true);
            try
            {
                Native.BringWindowToTop(root);
                Native.SetForegroundWindow(root);
            }
            finally
            {
                if (attachedTarget) Native.AttachThreadInput(me, targetThread, false);
                if (attachedFront) Native.AttachThreadInput(me, frontThread, false);
            }
            if (WaitUntil(delegate { return IsForeground(root); }, 150)) return true;

            // 3. What Alt+Tab itself uses; not subject to the foreground lock.
            Native.SwitchToThisWindow(root, true);
            return WaitUntil(delegate { return IsForeground(root); }, 250);
        }

        /// <summary>Raises the main window of process <paramref name="pid"/> (UIScope itself), restoring it first when it was minimized.</summary>
        internal static bool ReturnTo(uint pid)
        {
            IntPtr main = MainWindowOf(pid);
            if (main == IntPtr.Zero) return false;
            if (Native.IsIconic(main)) Restore(main);
            return BringToFront(main);
        }

        /// <summary>The first visible, titled, un-owned top-level window of a process, in Z order (its main window).</summary>
        internal static IntPtr MainWindowOf(uint pid)
        {
            IntPtr found = IntPtr.Zero;
            Native.EnumWindows(delegate(IntPtr hWnd, IntPtr lParam)
            {
                uint owner;
                Native.GetWindowThreadProcessId(hWnd, out owner);
                if (owner != pid) return true;
                if (!Native.IsWindowVisible(hWnd) || Native.IsToolWindow(hWnd)) return true;
                if (Native.WindowTitle(hWnd).Length == 0) return true;
                if (Native.GetWindow(hWnd, Native.GW_OWNER) != IntPtr.Zero) return true;
                found = hWnd;
                return false;
            }, IntPtr.Zero);
            return found;
        }

        private static IntPtr RootOf(IntPtr hwnd)
        {
            IntPtr root = Native.GetAncestor(hwnd, Native.GA_ROOT);
            return root == IntPtr.Zero ? hwnd : root;
        }

        private static bool IsForeground(IntPtr root)
        {
            IntPtr front = Native.GetForegroundWindow();
            if (front == IntPtr.Zero) return false;
            return front == root || Native.GetAncestor(front, Native.GA_ROOT) == root;
        }

        private static bool WaitUntil(Func<bool> done, int timeoutMs)
        {
            var clock = Stopwatch.StartNew();
            while (clock.ElapsedMilliseconds < timeoutMs)
            {
                if (done()) return true;
                Thread.Sleep(PollMs);
            }
            return done();
        }
    }
}
