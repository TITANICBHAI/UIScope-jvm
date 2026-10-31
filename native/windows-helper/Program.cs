using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace UIScope.Helper
{
    /// <summary>
    /// The UIScope Windows helper (plan P3-1, architecture decision D-WIN-1): a persistent process that owns everything
    /// touching Win32/UIA. Speaks the version-1 protocol (Appendix J): one JSON object per line on stdin, responses and
    /// events on stdout, diagnostics on stderr. Exits when stdin closes. Pick mode (P3-4) and the hotkey (P3-6) run on
    /// the hidden host window's STA thread. Written in C# 5.
    /// </summary>
    internal static class Program
    {
        private const string HelperVersion = "0.1.0";
        private const int ProtocolVersion = 1;
        private const int HotkeyId = 1;

        private static readonly object StdoutLock = new object();
        private static HostForm host;
        private static ManualResetEventSlim hostReady = new ManualResetEventSlim(false);
        private static PickSession pickSession;

        private static void Main(string[] args)
        {
            if (args.Length > 0 && args[0] == "--demo-pick")
            {
                RunDemoPick();
                return;
            }

            Console.OutputEncoding = Encoding.UTF8;

            // The UI thread owns the hidden host window (hotkeys, pick sessions). Started first so
            // request handlers can marshal onto it from the stdin reader below.
            var uiThread = new Thread(delegate()
            {
                host = new HostForm();
                host.HotkeyPressed += delegate(int hotkeyId)
                {
                    EmitLine("{\"event\":\"hotkey\",\"id\":" + hotkeyId + "}");
                };
                hostReady.Set();
                Application.Run(host);
            });
            uiThread.SetApartmentState(ApartmentState.STA);
            uiThread.Start();
            hostReady.Wait();

            var reader = Console.In;
            string line;
            while ((line = reader.ReadLine()) != null)
            {
                string trimmed = line.Trim();
                if (trimmed.Length > 0) Handle(trimmed);
            }
            // stdin closed: exit, like the real helper must.
        }

        /// <summary>--demo-pick (Appendix K, early check 2a): the overlay comes up, the picked element prints as
        /// JSON on stdout, then the helper exits. Lets the owner verify alignment and click-swallowing before the UI exists.</summary>
        private static void RunDemoPick()
        {
            Console.OutputEncoding = Encoding.UTF8;
            var done = new ManualResetEventSlim(false);
            var thread = new Thread(delegate()
            {
                var session = new PickSession(
                    delegate(PickedElementInfo element, IntPtr hwnd)
                    {
                        EmitLine("--demo-pick: picked from window " + hwnd.ToInt64().ToString("x8"));
                        done.Set();
                    },
                    delegate
                    {
                        EmitLine("--demo-pick: cancelled");
                        done.Set();
                    });
                session.Start(true);
                done.Set();
            });
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            done.Wait(60000);
            thread.Join(2000);
        }

        /// <summary>The one stdout writer: stdin responses and UI-thread events both funnel through the lock.</summary>
        internal static void EmitLine(string line)
        {
            lock (StdoutLock)
            {
                Console.Out.WriteLine(line);
                Console.Out.Flush();
            }
        }

        private static void Handle(string line)
        {
            string id = ExtractString(line, "id") ?? ExtractNumber(line, "id");
            if (id == null) return; // no correlation id: not a request we can answer
            string type = ExtractString(line, "type") ?? "";

            try
            {
                switch (type)
                {
                    case "hello":
                        Reply(id, "{\"protocol\":" + ProtocolVersion + ",\"helperVersion\":" + Q(HelperVersion) +
                                  ",\"osBuild\":" + Q(Environment.OSVersion.VersionString) +
                                  ",\"dpiAwareness\":\"PerMonitorV2\",\"isElevated\":" +
                                  (IsCurrentProcessElevated() ? "true" : "false") + "}");
                        break;

                    case "listWindows":
                        Reply(id, ListWindowsJson());
                        break;

                    case "captureWindow":
                        Reply(id, CaptureWindowJson(id, ExtractString(line, "hwnd")));
                        break;

                    case "startPick":
                        // The pick session runs on the UI thread; the events flow while it is up.
                        Reply(id, "{}");
                        host.BeginInvoke(new Action(delegate
                        {
                            if (pickSession != null) pickSession.Stop();
                            pickSession = new PickSession(
                                delegate(PickedElementInfo element, IntPtr hwnd) { /* the event line is already emitted */ },
                                delegate { /* cancelled: the event line is already emitted */ });
                            pickSession.Start(true);
                        }));
                        break;

                    case "stopPick":
                        host.BeginInvoke(new Action(delegate
                        {
                            if (pickSession != null) pickSession.Stop();
                            pickSession = null;
                        }));
                        Reply(id, "{}");
                        break;

                    case "registerHotkey":
                        Reply(id, RegisterHotkeyJson(ExtractString(line, "modifiers") ?? "", ExtractString(line, "key") ?? ""));
                        break;

                    case "unregisterHotkey":
                        host.BeginInvoke(new Action(delegate { host.TryUnregisterHotkey(HotkeyId); }));
                        Reply(id, "{}");
                        break;

                    case "ping":
                        Reply(id, "{}");
                        break;

                    case "shutdown":
                        Reply(id, "{}");
                        Environment.Exit(0);
                        break;

                    default:
                        ReplyError(id, "UIA_UNAVAILABLE", "the helper does not implement '" + type + "' yet");
                        break;
                }
            }
            catch (ProtocolException e)
            {
                Console.Error.WriteLine("helper: " + type + " failed: " + e.Message);
                ReplyError(id, e.Code, e.Message);
            }
            catch (Exception e)
            {
                Console.Error.WriteLine("helper: " + type + " failed: " + e.Message);
                ReplyError(id, "UIA_UNAVAILABLE", e.Message);
            }
        }

        /// <summary>Registers the pick hotkey on the host window; HOTKEY_IN_USE when another app owns it (plan P3-6).</summary>
        private static string RegisterHotkeyJson(string modifiers, string key)
        {
            uint mods = 0;
            var m = modifiers.ToLowerInvariant();
            if (m.Contains("ctrl")) mods |= 2;   // MOD_CONTROL
            if (m.Contains("alt")) mods |= 1;    // MOD_ALT
            if (m.Contains("shift")) mods |= 4;  // MOD_SHIFT
            if (m.Contains("win")) mods |= 8;    // MOD_WIN
            uint vk = KeyToVk(key);
            if (vk == 0) throw new ArgumentException("unknown key '" + key + "'");

            bool registered = false;
            host.BeginInvoke(new Action(delegate
            {
                registered = host.TryRegisterHotkey(HotkeyId, mods, vk);
            }));
            var deadline = Environment.TickCount + 1000;
            while (Environment.TickCount < deadline) Thread.Sleep(10);
            if (!registered) throw new InvalidOperationException("HOTKEY_IN_USE");
            return "{}";
        }

        private static uint KeyToVk(string key)
        {
            if (string.IsNullOrEmpty(key)) return 0;
            var k = key.Trim().ToLowerInvariant();
            if (k.Length == 1 && k[0] >= 'a' && k[0] <= 'z') return (uint)k[0];
            if (k.Length == 1 && k[0] >= '0' && k[0] <= '9') return (uint)k[0];
            if (k.StartsWith("f") && k.Length <= 3)
            {
                int f;
                if (int.TryParse(k.Substring(1), out f) && f >= 1 && f <= 24) return (uint)(0x6F + f); // VK_F1 = 0x70
            }
            return 0;
        }

        private static bool IsCurrentProcessElevated()
        {
            var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
            var principal = new System.Security.Principal.WindowsPrincipal(identity);
            return principal.IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
        }

        private static string ListWindowsJson()
        {
            var sb = new StringBuilder("{\"windows\":[");
            bool first = true;
            foreach (IntPtr hWnd in Inspector.TopLevelWindows())
            {
                uint pid;
                Native.GetWindowThreadProcessId(hWnd, out pid);
                string processName = "";
                try { processName = Process.GetProcessById((int)pid).ProcessName; }
                catch { /* the process may have exited */ }
                Native.RECT rect;
                Native.GetWindowRect(hWnd, out rect);
                if (!first) sb.Append(",");
                first = false;
                sb.Append("{\"hwnd\":").Append(Q("0x" + hWnd.ToInt64().ToString("x8")))
                  .Append(",\"title\":").Append(Q(Native.WindowTitle(hWnd)))
                  .Append(",\"processName\":").Append(Q(processName))
                  .Append(",\"pid\":").Append(pid)
                  .Append(",\"isElevated\":").Append(Native.IsElevated(pid) ? "true" : "false")
                  .Append(",\"rect\":{\"x\":").Append(rect.Left).Append(",\"y\":").Append(rect.Top)
                  .Append(",\"width\":").Append(rect.Width).Append(",\"height\":").Append(rect.Height).Append("}}");
            }
            sb.Append("]}");
            return sb.ToString();
        }

        private static string CaptureWindowJson(string id, string hwndText)
        {
            if (hwndText == null || !hwndText.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
                throw new ArgumentException("no hwnd given");
            IntPtr hwnd = new IntPtr(Convert.ToInt64(hwndText.Substring(2), 16));
            if (hwnd == IntPtr.Zero || !Native.IsWindowVisible(hwnd))
                throw new InvalidOperationException("the window was closed");
            uint pid = PidOf(hwnd);
            if (Native.IsElevated(pid))
                throw new ProtocolException("ELEVATED_TARGET", "the target window runs as administrator; start UIScope elevated to inspect it");

            System.Drawing.Rectangle windowRect;
            bool truncated;
            string roots = Inspector.CaptureTreeJson(hwnd, out windowRect, out truncated);

            string screenshotPath = Inspector.Screenshot(windowRect);
            var info = new FileInfo(screenshotPath);

            var sb = new StringBuilder("{\"id\":").Append(id).Append(",\"ok\":true");
            sb.Append(",\"window\":{\"hwnd\":").Append(Q(hwndText.ToLowerInvariant()))
              .Append(",\"title\":").Append(Q(Native.WindowTitle(hwnd)))
              .Append(",\"processName\":").Append(Q(ProcessNameOf(hwnd)))
              .Append(",\"pid\":").Append(pid)
              .Append(",\"isElevated\":").Append(Native.IsElevated(pid) ? "true" : "false")
              .Append(",\"rect\":{\"x\":").Append(windowRect.Left).Append(",\"y\":").Append(windowRect.Top)
              .Append(",\"width\":").Append(windowRect.Width).Append(",\"height\":").Append(windowRect.Height).Append("}}");
            sb.Append(",\"screenshot\":{\"path\":").Append(Q(screenshotPath))
              .Append(",\"width\":").Append(windowRect.Width)
              .Append(",\"height\":").Append(windowRect.Height)
              .Append(",\"originX\":").Append(windowRect.Left)
              .Append(",\"originY\":").Append(windowRect.Top).Append("}");
            sb.Append(",\"roots\":[").Append(roots).Append("]");
            sb.Append(",\"truncated\":").Append(truncated ? "true" : "false");
            sb.Append(",\"fileSize\":").Append(info.Length);
            sb.Append("}");
            return sb.ToString();
        }

        private static string ProcessNameOf(IntPtr hwnd)
        {
            uint pid = PidOf(hwnd);
            try { return System.Diagnostics.Process.GetProcessById((int)pid).ProcessName; }
            catch { return ""; }
        }

        private static uint PidOf(IntPtr hwnd)
        {
            uint pid;
            Native.GetWindowThreadProcessId(hwnd, out pid);
            return pid;
        }

        // ── tiny JSON field extraction (the client's requests are machine-written and flat) ──

        private static string ExtractString(string line, string key)
        {
            string needle = "\"" + key + "\":";
            int at = line.IndexOf(needle, StringComparison.Ordinal);
            if (at < 0) needle = "\"" + key + "\": ";
            if ((at = line.IndexOf(needle, StringComparison.Ordinal)) < 0) return null;
            int start = at + needle.Length;
            while (start < line.Length && char.IsWhiteSpace(line[start])) start++;
            if (start >= line.Length || line[start] != '"') return null;
            var sb = new StringBuilder();
            for (int i = start + 1; i < line.Length; i++)
            {
                char c = line[i];
                if (c == '\\' && i + 1 < line.Length)
                {
                    char n = line[++i];
                    if (n == 'n') sb.Append('\n');
                    else if (n == 'r') sb.Append('\r');
                    else if (n == 't') sb.Append('\t');
                    else sb.Append(n);
                    continue;
                }
                if (c == '"') return sb.ToString();
                sb.Append(c);
            }
            return sb.ToString();
        }

        private static string ExtractNumber(string line, string key)
        {
            string needle = "\"" + key + "\":";
            int at = line.IndexOf(needle, StringComparison.Ordinal);
            if (at < 0) needle = "\"" + key + "\": ";
            if ((at = line.IndexOf(needle, StringComparison.Ordinal)) < 0) return null;
            int start = at + needle.Length;
            while (start < line.Length && char.IsWhiteSpace(line[start])) start++;
            int end = start;
            while (end < line.Length && (char.IsDigit(line[end]) || line[end] == '-')) end++;
            return end > start ? line.Substring(start, end - start) : null;
        }

        // ── replies ───────────────────────────────────────────────────────────────────────────

        private static void Reply(string id, string result)
        {
            EmitLine("{\"id\":" + id + ",\"ok\":true," + (result.StartsWith("{") ? TrimBraces(result) : "\"result\":" + result) + "}");
        }

        private static string TrimBraces(string result)
        {
            // {"a":1} -> "a":1  so the result fields sit directly in the response object (Appendix J shape).
            return result.Length >= 2 && result[0] == '{' && result[result.Length - 1] == '}'
                ? result.Substring(1, result.Length - 2)
                : "\"result\":" + result;
        }

        private static void ReplyError(string id, string code, string message)
        {
            EmitLine("{\"id\":" + id + ",\"ok\":false,\"error\":{\"code\":" + Q(code) + ",\"message\":" + Q(message) + "}}");
        }

        private static string Q(string s)
        {
            if (s == null) s = "";
            return "\"" + s.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\n", "\\n").Replace("\r", "\\r").Replace("\t", "\\t") + "\"";
        }
    }

    /// <summary>An error that carries its Appendix A code to the client; anything else reports UIA_UNAVAILABLE.</summary>
    internal class ProtocolException : Exception
    {
        public readonly string Code;
        public ProtocolException(string code, string message) : base(message) { Code = code; }
    }
}
