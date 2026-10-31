using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Text;
using System.Windows.Automation;

namespace UIScope.Helper
{
    /// <summary>
    /// UI Automation capture (plan P3-3): a depth-first walk of one top-level window with depth/node limits,
    /// Appendix B properties per node, and a physical-resolution screenshot via a window-rect blit. One failing
    /// property never drops a subtree: every property read is guarded. Written in C# 5 so the Framework MSBuild
    /// compiles it with its own compiler (no Roslyn, no NuGet).
    /// </summary>
    internal static class Inspector
    {
        private const int MaxDepth = 40;
        private const int MaxNodes = 10000;

        private static string ControlTypeName(AutomationElement e)
        {
            try { return e.Current.ControlType.ProgrammaticName.Replace("ControlType.", ""); }
            catch { return ""; }
        }

        private static string Safe(Func<string> read)
        {
            try { return read() ?? ""; }
            catch { return ""; }
        }

        private static bool SafeBool(Func<bool> read)
        {
            try { return read(); }
            catch { return false; }
        }

        private static System.Drawing.Rectangle SafeRect(AutomationElement e)
        {
            try { return e.Current.BoundingRectangle; }
            catch { return System.Drawing.Rectangle.Empty; }
        }

        private static string RuntimeId(AutomationElement e)
        {
            try { return string.Join(" ", e.GetRuntimeId()); }
            catch { return ""; }
        }

        private static string Escape(string s)
        {
            if (s == null) return "";
            var sb = new StringBuilder();
            foreach (char c in s)
            {
                switch (c)
                {
                    case '\\': sb.Append("\\\\"); break;
                    case '"': sb.Append("\\\""); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    default:
                        if (c < ' ') sb.Append("\\u").Append(((int)c).ToString("x4"));
                        else sb.Append(c);
                        break;
                }
            }
            return sb.ToString();
        }

        private static string Q(string s) => "\"" + Escape(s) + "\"";

        /// <summary>The UIA subtree of one window as protocol JSON. Password values are never read (plan 4.4).</summary>
        internal static string CaptureTreeJson(IntPtr hwnd, out System.Drawing.Rectangle windowRect, out bool truncated)
        {
            truncated = false;
            var automation = AutomationElement.FromHandle(hwnd);
            windowRect = SafeRect(automation);
            int nodeCount = 0;
            var sb = new StringBuilder();
            Walk(automation, 0, sb, ref nodeCount);
            truncated = nodeCount >= MaxNodes;
            return sb.ToString();
        }

        /// <summary>Depth-first walk; emits this node and its (expanded) subtree into sb.</summary>
        private static void Walk(AutomationElement e, int depth, StringBuilder sb, ref int nodeCount)
        {
            if (depth > MaxDepth) return;
            if (nodeCount >= MaxNodes) return;
            nodeCount++;

            var rect = SafeRect(e);
            sb.Append("{\"automationId\":").Append(Q(Safe(() => e.Current.AutomationId)));
            sb.Append(",\"name\":").Append(Q(Safe(() => e.Current.Name)));
            sb.Append(",\"controlType\":").Append(Q(ControlTypeName(e)));
            sb.Append(",\"className\":").Append(Q(Safe(() => e.Current.ClassName)));
            sb.Append(",\"frameworkId\":").Append(Q(Safe(() => e.Current.FrameworkId)));
            sb.Append(",\"runtimeId\":").Append(Q(RuntimeId(e)));
            sb.Append(",\"rect\":{\"x\":").Append(rect.Left).Append(",\"y\":").Append(rect.Top)
               .Append(",\"width\":").Append(rect.Width).Append(",\"height\":").Append(rect.Height).Append("}");
            sb.Append(",\"isPassword\":").Append(SafeBool(() => e.Current.IsPasswordElementProperty) ? "true" : "false");
            sb.Append(",\"isEnabled\":").Append(SafeBool(() => !e.Current.IsEnabled) ? "false" : "true");
            sb.Append(",\"isOffscreen\":").Append(SafeBool(() => e.Current.IsOffscreen) ? "true" : "false");

            var children = new List<string>();
            if (nodeCount < MaxNodes)
            {
                var walker = TreeWalker.ControlViewWalker;
                try
                {
                    var child = walker.GetFirstChild(e);
                    while (child != AutomationElement.None && child != null)
                    {
                        var childSb = new StringBuilder();
                        int before = nodeCount;
                        Walk(child, depth + 1, childSb, ref nodeCount);
                        if (nodeCount > before) children.Add(childSb.ToString());
                        child = walker.GetNextSibling(child);
                    }
                }
                catch { /* element vanished mid-walk: keep what we have */ }
            }

            if (children.Count > 0) sb.Append(",\"children\":[").Append(string.Join(",", children)).Append("]");
            sb.Append("}");
        }

        /// <summary>The visible, titled top-level windows (Appendix J listWindows), shell and hung ones skipped.</summary>
        internal static List<IntPtr> TopLevelWindows()
        {
            var result = new List<IntPtr>();
            IntPtr shell = Native.GetShellWindow();
            Native.EnumWindows(delegate(IntPtr hWnd, IntPtr _) 
            {
                if (hWnd == shell) return true;
                if (!Native.IsWindowVisible(hWnd)) return true;
                if (Native.IsHungAppWindow(hWnd)) return true;
                if (Native.WindowTitle(hWnd).Length == 0) return true;
                result.Add(hWnd);
                return true;
            }, IntPtr.Zero);
            return result;
        }

        /// <summary>Blits the window rect at physical resolution into a temp PNG (origin = window top-left).</summary>
        internal static string Screenshot(System.Drawing.Rectangle rect)
        {
            using (var bitmap = new Bitmap(rect.Width, rect.Height, PixelFormat.Format32bppArgb))
            {
                using (var graphics = Graphics.FromImage(bitmap))
                {
                    graphics.CopyFromScreen(rect.Left, rect.Top, 0, 0, new Size(rect.Width, rect.Height));
                }
                string path = Path.Combine(Path.GetTempPath(), "uiscope-helper-" + Guid.NewGuid().ToString("N") + ".png");
                bitmap.Save(path, ImageFormat.Png);
                return path;
            }
        }
    }
}
