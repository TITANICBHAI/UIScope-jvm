using System;
using System.Diagnostics;
using System.IO;
using System.Text;

namespace UIScope.Helper
{
    /// <summary>
    /// The UIScope Windows helper (plan P3-1, architecture decision D-WIN-1): a persistent process that owns everything
    /// touching Win32/UIA. Speaks the version-1 protocol (Appendix J): one JSON object per line on stdin, responses and
    /// events on stdout, diagnostics on stderr. Exits when stdin closes. The overlay and mouse hook arrive with P3-4.
    /// Written in C# 5 so the Framework MSBuild compiles it with its own compiler.
    /// </summary>
    internal static class Program
    {
        private const string HelperVersion = "0.1.0";
        private const int ProtocolVersion = 1;

        private static void Main()
        {
            Console.OutputEncoding = Encoding.UTF8;
            var reader = Console.In;
            string line;
            while ((line = reader.ReadLine()) != null)
            {
                string trimmed = line.Trim();
                if (trimmed.Length > 0) Handle(trimmed);
            }
            // stdin closed: exit, like the real helper must.
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

                    case "ping":
                        Reply(id, "{}");
                        break;

                    case "shutdown":
                        Reply(id, "{}");
                        Environment.Exit(0);
                        break;

                    default:
                        // Pick mode, hotkey and friends arrive with P3-4/P3-6; unknown types get a typed error.
                        ReplyError(id, "UIA_UNAVAILABLE", "the helper does not implement '" + type + "' yet");
                        break;
                }
            }
            catch (Exception e)
            {
                Console.Error.WriteLine("helper: " + type + " failed: " + e.Message);
                ReplyError(id, "UIA_UNAVAILABLE", e.Message);
            }
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
                try { processName = System.Diagnostics.Process.GetProcessById((int)pid).ProcessName; }
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

            System.Drawing.Rectangle windowRect;
            bool truncated;
            string roots = Inspector.CaptureTreeJson(hwnd, out windowRect, out truncated);

            string screenshotPath = Inspector.Screenshot(windowRect);
            var info = new FileInfo(screenshotPath);

            var sb = new StringBuilder("{\"id\":").Append(id).Append(",\"ok\":true");
            sb.Append(",\"window\":{\"hwnd\":").Append(Q(hwndText.ToLowerInvariant()))
              .Append(",\"title\":").Append(Q(Native.WindowTitle(hwnd)))
              .Append(",\"processName\":").Append(Q(ProcessNameOf(hwnd)))
              .Append(",\"pid\":").Append(PidOf(hwnd))
              .Append(",\"isElevated\":").Append(Native.IsElevated(PidOf(hwnd)) ? "true" : "false")
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
            Console.Out.WriteLine("{\"id\":" + id + ",\"ok\":true," + (result.StartsWith("{") ? TrimBraces(result) : "\"result\":" + result) + "}");
            Console.Out.Flush();
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
            Console.Out.WriteLine("{\"id\":" + id + ",\"ok\":false,\"error\":{\"code\":" + Q(code) + ",\"message\":" + Q(message) + "}}");
            Console.Out.Flush();
        }

        private static string Q(string s)
        {
            if (s == null) s = "";
            return "\"" + s.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\n", "\\n").Replace("\r", "\\r").Replace("\t", "\\t") + "\"";
        }
    }
}
