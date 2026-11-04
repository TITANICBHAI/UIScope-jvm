using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace UIScope.Helper
{
    /// <summary>
    /// The UIScope Windows helper: a tiny line-delimited JSON server on stdin/stdout (Appendix J). Run with no arguments it speaks the protocol;
    /// <c>--selftest</c> prints a human-readable health report; <c>--demo-pick</c> runs the pick overlay on its own.
    ///
    /// Written in C# 5 on purpose: the project builds with the Framework MSBuild that ships on every Windows box, whose compiler is C# 5.
    /// </summary>
    internal static class Program
    {
        internal const string HelperVersion = "0.3.0";
        private const int ProtocolVersion = 1;
        private const int HotkeyId = 0x5C01;
        private const uint ModNoRepeat = 0x4000;

        private static readonly object OutputLock = new object();
        private static readonly object PickLock = new object();
        private static TextWriter stdout;
        private static HostForm host;
        private static PickSession pickSession;

        private static int Main(string[] args)
        {
            AppDomain.CurrentDomain.UnhandledException += delegate(object sender, UnhandledExceptionEventArgs e)
            {
                Log("unhandled exception: " + e.ExceptionObject);
            };
            if (args.Length > 0)
            {
                switch (args[0])
                {
                    case "--selftest": return SelfTest.Run(args);
                    case "--demo-pick": return RunDemoPick();
                    case "--version":
                        Console.WriteLine(HelperVersion);
                        return 0;
                }
            }
            return RunProtocol();
        }

        // -- the protocol loop -------------------------------------------------------------------

        private static int RunProtocol()
        {
            // Explicit UTF-8 streams. Console.OutputEncoding/InputEncoding go through the console code page (the OEM page for a redirected
            // pipe), may write a byte-order mark, and can throw when the process has no console; raw streams do none of that.
            var utf8 = new UTF8Encoding(false);
            var writer = new StreamWriter(Console.OpenStandardOutput(), utf8, 4096);
            writer.AutoFlush = true;
            writer.NewLine = "\n";
            stdout = writer;
            TextReader stdin = new StreamReader(Console.OpenStandardInput(), utf8);

            StartUiThread();

            string line;
            while ((line = stdin.ReadLine()) != null)
            {
                if (line.Length == 0) continue;
                HandleLine(line);
            }
            // stdin closed: the client is gone. The UI thread is a background thread, but exit explicitly so nothing can keep the process alive.
            Environment.Exit(0);
            return 0;
        }

        /// <summary>The hidden window and its message loop live on their own STA thread; every UI Automation call stays off it.</summary>
        private static void StartUiThread()
        {
            var ready = new ManualResetEvent(false);
            var thread = new Thread(delegate()
            {
                try
                {
                    // A hidden process must never show the "unhandled exception" dialog nobody could click.
                    Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
                    Application.ThreadException += delegate(object sender, ThreadExceptionEventArgs e) { Log("UI thread: " + e.Exception); };
                    host = new HostForm();
                    host.HotkeyPressed += delegate(int id) { EmitLine("{\"event\":\"hotkey\",\"id\":" + id + "}"); };
                    IntPtr created = host.Handle; // create the window on this thread
                    GC.KeepAlive(created);
                    ready.Set();
                    Application.Run(host);
                }
                catch (Exception e)
                {
                    Log("the UI thread failed: " + e);
                    ready.Set();
                }
            });
            thread.SetApartmentState(ApartmentState.STA);
            thread.IsBackground = true;
            thread.Name = "uiscope-ui";
            thread.Start();
            if (!ready.WaitOne(10000)) Log("the UI thread did not start in time");
        }

        internal static void EmitLine(string line)
        {
            lock (OutputLock)
            {
                TextWriter target = stdout ?? Console.Out;
                target.WriteLine(line);
                target.Flush();
            }
        }

        internal static void Log(string text)
        {
            try
            {
                Console.Error.WriteLine("helper: " + text);
            }
            catch (Exception)
            {
                // nothing sensible left to do
            }
        }

        private static void Reply(string id, string fields)
        {
            EmitLine("{\"id\":" + id + ",\"ok\":true" + (string.IsNullOrEmpty(fields) ? "" : "," + fields) + "}");
        }

        private static void ReplyError(string id, string code, string message)
        {
            EmitLine("{\"id\":" + id + ",\"ok\":false,\"error\":{\"code\":" + Json.Str(code) + ",\"message\":" + Json.Str(message) + "}}");
        }

        private static void HandleLine(string line)
        {
            string id = Json.ExtractNumber(line, "id");
            if (id == null)
            {
                Log("ignoring a line without a numeric id");
                return;
            }
            string type = Json.ExtractString(line, "type") ?? "";
            try
            {
                switch (type)
                {
                    case "hello":
                        Reply(id, "\"protocol\":" + ProtocolVersion + ",\"helperVersion\":" + Json.Str(HelperVersion) +
                                  ",\"osBuild\":" + Json.Str(Environment.OSVersion.VersionString) + ",\"dpiAwareness\":\"PerMonitorV2\"" +
                                  ",\"isElevated\":" + Json.Bool(Native.IsCurrentProcessElevated()) + ",\"pid\":" + Process.GetCurrentProcess().Id);
                        break;

                    case "ping":
                        Reply(id, "");
                        break;

                    case "listWindows":
                        RunAsync(id, type, delegate { return ListWindowsFields(line); });
                        break;

                    case "captureWindow":
                        RunAsync(id, type, delegate { return CaptureFields(line); });
                        break;

                    case "activateWindow":
                        RunAsync(id, type, delegate { return ActivateFields(line); });
                        break;

                    case "startPick":
                    {
                        bool hide = Json.ExtractBool(line, "hideFromCapture", true);
                        Reply(id, "");
                        host.BeginInvoke(new Action(delegate { StartPick(hide); }));
                        break;
                    }

                    case "stopPick":
                        host.BeginInvoke(new Action(StopPick));
                        Reply(id, "");
                        break;

                    case "registerHotkey":
                        Reply(id, RegisterHotkeyFields(Json.ExtractString(line, "modifiers") ?? "", Json.ExtractString(line, "key") ?? ""));
                        break;

                    case "unregisterHotkey":
                        host.Invoke(new Action(delegate { host.TryUnregisterHotkey(HotkeyId); }));
                        Reply(id, "");
                        break;

                    case "shutdown":
                        Reply(id, "");
                        Environment.Exit(0);
                        break;

                    default:
                        ReplyError(id, "UIA_UNAVAILABLE", "the helper does not implement '" + type + "'");
                        break;
                }
            }
            catch (ProtocolException e)
            {
                Log(type + " failed: " + e.Code + ": " + e.Message);
                ReplyError(id, e.Code, e.Message);
            }
            catch (Exception e)
            {
                Log(type + " failed: " + e);
                ReplyError(id, "UIA_UNAVAILABLE", e.Message);
            }
        }

        /// <summary>
        /// Slow requests run on pool threads (which are MTA, the right home for UI Automation), so a long capture never blocks the reader:
        /// a <c>stopPick</c> or <c>shutdown</c> sent meanwhile is answered at once.
        /// </summary>
        private static void RunAsync(string id, string type, Func<string> work)
        {
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    Reply(id, work());
                }
                catch (ProtocolException e)
                {
                    Log(type + " failed: " + e.Code + ": " + e.Message);
                    ReplyError(id, e.Code, e.Message);
                }
                catch (Exception e)
                {
                    Log(type + " failed: " + e);
                    ReplyError(id, "UIA_UNAVAILABLE", e.Message);
                }
            });
        }

        // -- listWindows -------------------------------------------------------------------------

        private static string ListWindowsFields(string line)
        {
            // The caller's own process (UIScope) is left out: inspecting the inspector is never what anyone wants from this list.
            uint excludePid = (uint)Math.Max(0, Json.ExtractInt(line, "excludePid", 0));
            var sb = new StringBuilder("\"windows\":[");
            var names = new Dictionary<uint, string>();
            var elevation = new Dictionary<uint, bool>();
            bool selfElevated = Native.IsCurrentProcessElevated();
            bool first = true;
            foreach (IntPtr hwnd in Inspector.TopLevelWindows())
            {
                uint pid;
                Native.GetWindowThreadProcessId(hwnd, out pid);
                if (excludePid != 0 && pid == excludePid) continue;
                string name;
                if (!names.TryGetValue(pid, out name))
                {
                    name = ProcessName(pid);
                    names[pid] = name;
                }
                bool elevated;
                if (!elevation.TryGetValue(pid, out elevated))
                {
                    elevated = Native.IsElevated(pid);
                    elevation[pid] = elevated;
                }
                if (!first) sb.Append(',');
                first = false;
                // "isElevated" tells the client the window cannot be inspected: an elevated helper can inspect everything.
                sb.Append("{\"hwnd\":").Append(Json.Str(Json.Hwnd(hwnd)));
                sb.Append(",\"title\":").Append(Json.Str(Native.WindowTitle(hwnd)));
                sb.Append(",\"processName\":").Append(Json.Str(name));
                sb.Append(",\"pid\":").Append(pid);
                sb.Append(",\"isElevated\":").Append(Json.Bool(elevated && !selfElevated));
                sb.Append(",\"isMinimized\":").Append(Json.Bool(Native.IsIconic(hwnd))).Append('}');
            }
            return sb.Append(']').ToString();
        }

        private static string ProcessName(uint pid)
        {
            try
            {
                return Process.GetProcessById((int)pid).ProcessName;
            }
            catch (Exception)
            {
                return "";
            }
        }

        // -- captureWindow -----------------------------------------------------------------------

        private static IntPtr ParseHwnd(string text)
        {
            if (string.IsNullOrEmpty(text)) throw new ProtocolException("WINDOW_GONE", "No window handle was given");
            string digits = text.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ? text.Substring(2) : text;
            long value;
            if (!long.TryParse(digits, System.Globalization.NumberStyles.HexNumber, System.Globalization.CultureInfo.InvariantCulture, out value))
                throw new ProtocolException("WINDOW_GONE", "'" + text + "' is not a window handle");
            return new IntPtr(value);
        }

        private static string CaptureFields(string line)
        {
            IntPtr hwnd = ParseHwnd(Json.ExtractString(line, "hwnd"));
            int maxDepth = Json.ExtractInt(line, "maxDepth", 40);
            int maxNodes = Json.ExtractInt(line, "maxNodes", 10000);
            int budgetMs = Json.ExtractInt(line, "budgetMs", 20000);
            string folder = Json.ExtractString(line, "tmpDir");

            // "activate": restore the window if it is minimized and bring it to the front before looking at it (a minimized window has
            // no pixels, and the screen-copy fallback needs nothing on top of it); "returnToPid": raise that process (UIScope) again
            // afterwards, so the user lands back on the result.
            bool activate = Json.ExtractBool(line, "activate", true);
            int settleMs = Json.ExtractInt(line, "settleMs", 300);
            uint returnToPid = (uint)Math.Max(0, Json.ExtractInt(line, "returnToPid", 0));

            if (!Native.IsWindow(hwnd)) throw new ProtocolException("WINDOW_GONE", "The window no longer exists");
            uint pid;
            Native.GetWindowThreadProcessId(hwnd, out pid);
            bool targetElevated = Native.IsElevated(pid);
            if (targetElevated && !Native.IsCurrentProcessElevated())
                throw new ProtocolException("ELEVATED_TARGET", "That window runs as administrator; start UIScope as administrator to inspect it");

            bool wasMinimized = Native.IsIconic(hwnd);
            bool inFront = false;
            if (activate)
            {
                Activation.Shown shown = Activation.Show(hwnd, settleMs);
                wasMinimized = shown.WasMinimized;
                inFront = shown.Foreground;
            }
            try
            {
                return CaptureShown(hwnd, pid, targetElevated, maxDepth, maxNodes, budgetMs, folder, activate, wasMinimized, inFront);
            }
            finally
            {
                // Always: a failed capture must not leave the user staring at the target with UIScope hidden behind it.
                if (activate && returnToPid != 0) Activation.ReturnTo(returnToPid);
            }
        }

        private static string CaptureShown(IntPtr hwnd, uint pid, bool targetElevated, int maxDepth, int maxNodes, int budgetMs, string folder,
            bool activate, bool wasMinimized, bool inFront)
        {
            if (Native.IsIconic(hwnd))
                throw new ProtocolException("WINDOW_MINIMIZED", activate ? "The window is minimized and did not restore" : "The window is minimized; restore it and capture again");

            Native.RECT native;
            if (!Native.GetWindowRect(hwnd, out native) || native.Width < 1 || native.Height < 1)
                throw new ProtocolException("WINDOW_MINIMIZED", "The window has no visible area");
            Rectangle rect = new Rectangle(native.Left, native.Top, native.Width, native.Height);

            Inspector.TreeResult tree = Inspector.CaptureTree(hwnd, maxDepth, maxNodes, budgetMs);

            string method;
            string picture;
            var shot = Stopwatch.StartNew();
            try
            {
                picture = Inspector.Screenshot(hwnd, rect, folder, out method);
            }
            catch (Exception e)
            {
                throw new ProtocolException("UIA_UNAVAILABLE", "The screenshot could not be taken: " + e.Message);
            }
            Log("captured " + Json.Hwnd(hwnd) + ": " + tree.NodeCount + " nodes in " + tree.ElapsedMs + " ms" + (tree.Truncated ? " (truncated)" : "") +
                ", screenshot by " + method + " in " + shot.ElapsedMilliseconds + " ms");

            var sb = new StringBuilder();
            sb.Append("\"window\":{\"hwnd\":").Append(Json.Str(Json.Hwnd(hwnd)));
            sb.Append(",\"title\":").Append(Json.Str(Native.WindowTitle(hwnd)));
            sb.Append(",\"processName\":").Append(Json.Str(ProcessName(pid)));
            sb.Append(",\"pid\":").Append(pid);
            sb.Append(",\"isElevated\":").Append(Json.Bool(targetElevated));
            sb.Append(",\"rect\":").Append(Json.Rect(rect.X, rect.Y, rect.Width, rect.Height)).Append('}');
            sb.Append(",\"screenshot\":{\"path\":").Append(Json.Str(picture));
            sb.Append(",\"width\":").Append(rect.Width).Append(",\"height\":").Append(rect.Height);
            sb.Append(",\"originX\":").Append(rect.X).Append(",\"originY\":").Append(rect.Y);
            sb.Append(",\"method\":").Append(Json.Str(method)).Append('}');
            sb.Append(",\"roots\":[").Append(tree.RootJson).Append(']');
            sb.Append(",\"truncated\":").Append(Json.Bool(tree.Truncated));
            sb.Append(",\"nodeCount\":").Append(tree.NodeCount).Append(",\"elapsedMs\":").Append(tree.ElapsedMs);
            sb.Append(",\"activation\":{\"requested\":").Append(Json.Bool(activate));
            sb.Append(",\"wasMinimized\":").Append(Json.Bool(wasMinimized));
            sb.Append(",\"foreground\":").Append(Json.Bool(inFront)).Append('}');
            return sb.ToString();
        }

        // -- activateWindow ----------------------------------------------------------------------

        /// <summary>"Show window": restore the window if needed and leave it in front (no capture, no return to UIScope).</summary>
        private static string ActivateFields(string line)
        {
            IntPtr hwnd = ParseHwnd(Json.ExtractString(line, "hwnd"));
            if (!Native.IsWindow(hwnd)) throw new ProtocolException("WINDOW_GONE", "The window no longer exists");
            Activation.Shown shown = Activation.Show(hwnd, 0);
            return "\"wasMinimized\":" + Json.Bool(shown.WasMinimized) + ",\"foreground\":" + Json.Bool(shown.Foreground);
        }

        // -- pick --------------------------------------------------------------------------------

        /// <summary>UI thread.</summary>
        private static void StartPick(bool hideFromCapture)
        {
            try
            {
                lock (PickLock)
                {
                    if (pickSession != null) pickSession.Stop();
                    var session = new PickSession(host);
                    session.Ended += delegate
                    {
                        lock (PickLock)
                        {
                            if (pickSession == session) pickSession = null;
                        }
                    };
                    pickSession = session;
                    if (!session.Start(hideFromCapture))
                    {
                        pickSession = null;
                        EmitLine("{\"event\":\"pickFailed\",\"message\":" + Json.Str("Windows would not let UIScope watch the mouse. Pick by choosing the window from the list instead.") + "}");
                    }
                }
            }
            catch (Exception e)
            {
                Log("starting the pick failed: " + e);
                EmitLine("{\"event\":\"pickFailed\",\"message\":" + Json.Str(e.Message) + "}");
            }
        }

        /// <summary>UI thread.</summary>
        private static void StopPick()
        {
            lock (PickLock)
            {
                if (pickSession != null) pickSession.Stop();
                pickSession = null;
            }
        }

        // -- hotkey ------------------------------------------------------------------------------

        /// <summary>Registers the pick hotkey on the host window; HOTKEY_IN_USE when another application owns it (plan P3-6).</summary>
        private static string RegisterHotkeyFields(string modifiers, string key)
        {
            uint mods = ModNoRepeat;
            string m = modifiers.ToLowerInvariant();
            if (m.Contains("ctrl") || m.Contains("control")) mods |= 2; // MOD_CONTROL
            if (m.Contains("alt")) mods |= 1;                          // MOD_ALT
            if (m.Contains("shift")) mods |= 4;                        // MOD_SHIFT
            if (m.Contains("win")) mods |= 8;                          // MOD_WIN
            uint vk = KeyToVk(key);
            if (vk == 0) throw new ProtocolException("UIA_UNAVAILABLE", "unknown key '" + key + "'");
            if ((mods & 0xF) == 0) throw new ProtocolException("UIA_UNAVAILABLE", "a global hotkey needs at least one modifier key");

            bool registered = (bool)host.Invoke(new Func<bool>(delegate { return host.TryRegisterHotkey(HotkeyId, mods, vk); }));
            if (!registered) throw new ProtocolException("HOTKEY_IN_USE", "Another application already uses " + modifiers + "+" + key);
            return "";
        }

        /// <summary>
        /// Virtual-key codes: letters are the <b>upper-case</b> ASCII codes (0x41-0x5A). The first version returned the lower-case ones, which
        /// are F1-F12 and the numeric keypad, so "Ctrl+Alt+P" quietly registered Ctrl+Alt+F1.
        /// </summary>
        internal static uint KeyToVk(string key)
        {
            if (string.IsNullOrEmpty(key)) return 0;
            string k = key.Trim().ToLowerInvariant();
            if (k.Length == 1)
            {
                char c = k[0];
                if (c >= 'a' && c <= 'z') return (uint)char.ToUpperInvariant(c);
                if (c >= '0' && c <= '9') return (uint)c;
            }
            if (k.Length >= 2 && k[0] == 'f')
            {
                int number;
                if (int.TryParse(k.Substring(1), out number) && number >= 1 && number <= 24) return (uint)(0x70 + number - 1);
            }
            switch (k)
            {
                case "space": return 0x20;
                case "enter":
                case "return": return 0x0D;
                case "tab": return 0x09;
                case "esc":
                case "escape": return 0x1B;
                case "insert": return 0x2D;
                case "delete":
                case "del": return 0x2E;
                case "home": return 0x24;
                case "end": return 0x23;
                case "pageup": return 0x21;
                case "pagedown": return 0x22;
                case "left": return 0x25;
                case "up": return 0x26;
                case "right": return 0x27;
                case "down": return 0x28;
                case "printscreen": return 0x2C;
                case "pause": return 0x13;
                default: return 0;
            }
        }

        // -- demo --------------------------------------------------------------------------------

        /// <summary>Runs the pick overlay on its own (no client): hover, click to print the element, Esc to cancel.</summary>
        private static int RunDemoPick()
        {
            stdout = Console.Out;
            Console.WriteLine("--demo-pick: hover an element and click it (Esc cancels).");
            var thread = new Thread(delegate()
            {
                host = new HostForm();
                IntPtr created = host.Handle;
                GC.KeepAlive(created);
                var session = new PickSession(host);
                session.Ended += delegate { host.BeginInvoke(new Action(Application.ExitThread)); };
                host.BeginInvoke(new Action(delegate
                {
                    if (!session.Start(true))
                    {
                        Console.WriteLine("--demo-pick: the mouse hook could not be installed");
                        Application.ExitThread();
                    }
                }));
                Application.Run(host);
            });
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            thread.Join();
            return 0;
        }
    }
}
