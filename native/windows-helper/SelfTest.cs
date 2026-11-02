using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Automation;

namespace UIScope.Helper
{
    /// <summary>
    /// <c>UIScope.Helper.exe --selftest [0xHWND]</c>: runs every capability the app relies on once and prints what worked, how long it took
    /// and, when something failed, why. It needs no UIScope and no Java, so it answers the question "is it the helper or the app?" in seconds.
    /// Exit code 0 = everything passed.
    /// </summary>
    internal static class SelfTest
    {
        private static int failures;

        internal static int Run(string[] args)
        {
            Console.WriteLine("UIScope Windows helper " + Program.HelperVersion + " - self-test");
            Console.WriteLine("  OS        : " + Environment.OSVersion.VersionString + (Environment.Is64BitProcess ? ", 64-bit" : ", 32-bit"));
            Console.WriteLine("  Elevated  : " + (Native.IsCurrentProcessElevated() ? "yes" : "no"));
            Console.WriteLine("  Runtime   : .NET " + Environment.Version);
            Console.WriteLine();

            Step("UI Automation is available", delegate
            {
                AutomationElement root = AutomationElement.RootElement;
                return root == null ? "FAIL:no desktop element" : "desktop element found";
            });

            IntPtr target = IntPtr.Zero;
            Step("Window list", delegate
            {
                var windows = Inspector.TopLevelWindows();
                if (windows.Count == 0) return "FAIL:no visible windows were found";
                int ownPid = Process.GetCurrentProcess().Id;
                string sample = "";
                foreach (IntPtr w in windows)
                {
                    uint pid;
                    Native.GetWindowThreadProcessId(w, out pid);
                    if (target == IntPtr.Zero && pid != (uint)ownPid && !Native.IsIconic(w) && !Native.IsElevated(pid)) target = w;
                    if (sample.Length < 160) sample += (sample.Length > 0 ? " | " : "") + Short(Native.WindowTitle(w), 28);
                }
                return windows.Count + " windows: " + sample;
            });

            if (args.Length > 1)
            {
                try
                {
                    string text = args[1].StartsWith("0x", StringComparison.OrdinalIgnoreCase) ? args[1].Substring(2) : args[1];
                    target = new IntPtr(Convert.ToInt64(text, 16));
                }
                catch (Exception)
                {
                    Console.WriteLine("  (ignoring '" + args[1] + "': not a hex window handle)");
                }
            }

            if (target == IntPtr.Zero)
            {
                Console.WriteLine("[SKIP] Capture: no suitable window is open (start any application and run this again)");
            }
            else
            {
                string title = Native.WindowTitle(target);
                Native.RECT rect;
                Native.GetWindowRect(target, out rect);
                Rectangle bounds = new Rectangle(rect.Left, rect.Top, Math.Max(1, rect.Width), Math.Max(1, rect.Height));
                Console.WriteLine("  Target    : " + Json.Hwnd(target) + " \"" + Short(title, 50) + "\" " + bounds.Width + "x" + bounds.Height);

                Step("UI Automation tree (cached walk, 20 s budget)", delegate
                {
                    Inspector.TreeResult tree = Inspector.CaptureTree(target, 40, 10000, 20000);
                    return tree.NodeCount + " nodes in " + tree.ElapsedMs + " ms" + (tree.Truncated ? " (truncated at the limits)" : "");
                });

                Step("Screenshot", delegate
                {
                    string method;
                    string path = Inspector.Screenshot(target, bounds, null, out method);
                    long size = new FileInfo(path).Length;
                    File.Delete(path);
                    return method + ", " + bounds.Width + "x" + bounds.Height + " px, " + (size / 1024) + " KB";
                });
            }

            Step("Hover query (element under the pointer)", delegate
            {
                Native.POINT p;
                Native.GetCursorPos(out p);
                Inspector.ElementInfo info = Inspector.ElementAt(p.X, p.Y);
                return info == null ? "nothing under the pointer" : info.ControlType + " \"" + Short(info.Name, 30) + "\"";
            });

            Step("Mouse hook can be installed (pick mode)", delegate
            {
                Native.LowLevelProc proc = delegate(int code, IntPtr w, IntPtr l) { return Native.CallNextHookEx(IntPtr.Zero, code, w, l); };
                IntPtr hook = Native.SetWindowsHookEx(Native.WH_MOUSE_LL, proc, Native.GetModuleHandle(null), 0);
                if (hook == IntPtr.Zero) return "FAIL:SetWindowsHookEx failed, Win32 error " + Marshal.GetLastWin32Error();
                Native.UnhookWindowsHookEx(hook);
                GC.KeepAlive(proc);
                return "installed and removed";
            });

            Step("Hotkey letters map to the right keys", delegate
            {
                return Program.KeyToVk("p") == 0x50 && Program.KeyToVk("F5") == 0x74 ? "P = 0x50, F5 = 0x74" : "FAIL:key table is wrong";
            });

            Console.WriteLine();
            Console.WriteLine(failures == 0 ? "All checks passed." : failures + " check(s) failed.");
            return failures == 0 ? 0 : 1;
        }

        private static void Step(string name, Func<string> action)
        {
            var clock = Stopwatch.StartNew();
            try
            {
                string result = action();
                if (result.StartsWith("FAIL:", StringComparison.Ordinal))
                {
                    failures++;
                    Console.WriteLine("[FAIL] " + name + " - " + result.Substring(5));
                }
                else
                {
                    Console.WriteLine("[ OK ] " + name + " (" + clock.ElapsedMilliseconds + " ms) - " + result);
                }
            }
            catch (Exception e)
            {
                failures++;
                Console.WriteLine("[FAIL] " + name + " - " + e.GetType().Name + ": " + e.Message);
            }
        }

        private static string Short(string text, int max)
        {
            if (text == null) return "";
            text = text.Replace('\r', ' ').Replace('\n', ' ');
            return text.Length <= max ? text : text.Substring(0, max - 1) + "...";
        }
    }
}
