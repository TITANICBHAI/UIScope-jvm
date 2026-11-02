using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Text;
using System.Windows.Automation;

namespace UIScope.Helper
{
    /// <summary>
    /// Window enumeration, the UI Automation tree walk and the screenshot (plan P3-2).
    ///
    /// The walk used to read every property of every element with its own cross-process call (about a dozen per node), which takes minutes on
    /// a real application and always ran into the client's timeout. It now fetches each element's properties together with its children in a
    /// single call per node through a <see cref="CacheRequest"/>, and it stops at a node count and a time budget instead of running forever;
    /// whatever was read up to then is returned and flagged as truncated.
    /// </summary>
    internal static class Inspector
    {
        private const int MaxBitmapSide = 16384;

        internal sealed class TreeResult
        {
            public string RootJson;
            public bool Truncated;
            public int NodeCount;
            public long ElapsedMs;
        }

        /// <summary>One element as the pick events describe it (Appendix J).</summary>
        internal sealed class ElementInfo
        {
            public string ControlType = "";
            public string Name = "";
            public string AutomationId = "";
            public string RuntimeId = "";
            public Rectangle Rect;
            public int ProcessId;
            public AutomationElement Element;

            public string Json()
            {
                return "{\"controlType\":" + UIScope.Helper.Json.Str(ControlType) + ",\"name\":" + UIScope.Helper.Json.Str(Name) +
                       ",\"automationId\":" + UIScope.Helper.Json.Str(AutomationId) + ",\"runtimeId\":" + UIScope.Helper.Json.Str(RuntimeId) +
                       ",\"rect\":" + UIScope.Helper.Json.Rect(Rect.X, Rect.Y, Rect.Width, Rect.Height) + "}";
            }
        }

        private sealed class WalkState
        {
            public CacheRequest Cache;
            public int MaxDepth;
            public int MaxNodes;
            public long BudgetMs;
            public Stopwatch Clock = Stopwatch.StartNew();
            public int Count;
            public bool Truncated;

            public bool OutOfBudget()
            {
                return Count >= MaxNodes || Clock.ElapsedMilliseconds >= BudgetMs;
            }
        }

        private static CacheRequest NewCache(bool withChildren)
        {
            var cache = new CacheRequest();
            cache.Add(AutomationElement.AutomationIdProperty);
            cache.Add(AutomationElement.NameProperty);
            cache.Add(AutomationElement.ControlTypeProperty);
            cache.Add(AutomationElement.ClassNameProperty);
            cache.Add(AutomationElement.FrameworkIdProperty);
            cache.Add(AutomationElement.BoundingRectangleProperty);
            cache.Add(AutomationElement.IsPasswordProperty);
            cache.Add(AutomationElement.IsEnabledProperty);
            cache.Add(AutomationElement.IsOffscreenProperty);
            cache.Add(AutomationElement.ProcessIdProperty);
            cache.Add(AutomationElement.RuntimeIdProperty);
            if (withChildren)
            {
                cache.TreeFilter = Automation.ControlViewCondition;
                cache.TreeScope = TreeScope.Element | TreeScope.Children;
            }
            else
            {
                cache.TreeScope = TreeScope.Element;
            }
            return cache;
        }

        // -- the tree ---------------------------------------------------------------------------

        /// <summary>The UI Automation subtree of one window as protocol JSON. Password values are never read (plan 4.4).</summary>
        internal static TreeResult CaptureTree(IntPtr hwnd, int maxDepth, int maxNodes, int budgetMs)
        {
            var state = new WalkState
            {
                Cache = NewCache(true),
                MaxDepth = Math.Max(0, maxDepth),
                MaxNodes = Math.Max(1, maxNodes),
                BudgetMs = Math.Max(1000, budgetMs)
            };
            AutomationElement root;
            try
            {
                root = AutomationElement.FromHandle(hwnd).GetUpdatedCache(state.Cache);
            }
            catch (ElementNotAvailableException)
            {
                throw new ProtocolException("WINDOW_GONE", "The window no longer exists");
            }
            var sb = new StringBuilder(64 * 1024);
            Walk(root, 0, true, state, sb);
            return new TreeResult
            {
                RootJson = sb.ToString(),
                Truncated = state.Truncated,
                NodeCount = state.Count,
                ElapsedMs = state.Clock.ElapsedMilliseconds
            };
        }

        /// <summary>Depth-first walk; writes this node and its subtree. <paramref name="childrenCached"/> is true when the node already carries its children.</summary>
        private static void Walk(AutomationElement node, int depth, bool childrenCached, WalkState s, StringBuilder sb)
        {
            s.Count++;
            AppendNode(node, sb);

            AutomationElementCollection kids = null;
            if (depth < s.MaxDepth)
            {
                if (s.OutOfBudget())
                {
                    s.Truncated = true;
                }
                else
                {
                    try
                    {
                        AutomationElement full = childrenCached ? node : node.GetUpdatedCache(s.Cache);
                        kids = full.CachedChildren;
                    }
                    catch (Exception)
                    {
                        kids = null; // the element vanished mid-walk: keep what we have
                    }
                }
            }

            if (kids != null && kids.Count > 0)
            {
                sb.Append(",\"children\":[");
                bool first = true;
                for (int i = 0; i < kids.Count; i++)
                {
                    if (s.OutOfBudget())
                    {
                        s.Truncated = true;
                        break;
                    }
                    if (!first) sb.Append(',');
                    first = false;
                    Walk(kids[i], depth + 1, false, s, sb);
                }
                sb.Append(']');
            }
            sb.Append('}');
        }

        /// <summary>Writes the node without its closing brace, so the caller can append the children.</summary>
        private static void AppendNode(AutomationElement e, StringBuilder sb)
        {
            Rectangle rect = CachedRect(e);
            sb.Append("{\"automationId\":").Append(Json.Str(Text(delegate { return e.Cached.AutomationId; })));
            sb.Append(",\"name\":").Append(Json.Str(Text(delegate { return e.Cached.Name; })));
            sb.Append(",\"controlType\":").Append(Json.Str(CachedControlType(e)));
            sb.Append(",\"className\":").Append(Json.Str(Text(delegate { return e.Cached.ClassName; })));
            sb.Append(",\"frameworkId\":").Append(Json.Str(Text(delegate { return e.Cached.FrameworkId; })));
            sb.Append(",\"runtimeId\":").Append(Json.Str(CachedRuntimeId(e)));
            sb.Append(",\"rect\":").Append(Json.Rect(rect.X, rect.Y, rect.Width, rect.Height));
            sb.Append(",\"isPassword\":").Append(Json.Bool(Flag(delegate { return e.Cached.IsPassword; }, false)));
            sb.Append(",\"isEnabled\":").Append(Json.Bool(Flag(delegate { return e.Cached.IsEnabled; }, true)));
            sb.Append(",\"isOffscreen\":").Append(Json.Bool(Flag(delegate { return e.Cached.IsOffscreen; }, false)));
        }

        private static string Text(Func<string> read)
        {
            try { return read() ?? ""; }
            catch (Exception) { return ""; }
        }

        private static bool Flag(Func<bool> read, bool fallback)
        {
            try { return read(); }
            catch (Exception) { return fallback; }
        }

        private static string CachedControlType(AutomationElement e)
        {
            try { return ControlTypeName(e.Cached.ControlType); }
            catch (Exception) { return "Unknown"; }
        }

        internal static string ControlTypeName(ControlType type)
        {
            if (type == null) return "Unknown";
            string name = type.ProgrammaticName ?? "Unknown";
            const string prefix = "ControlType.";
            return name.StartsWith(prefix, StringComparison.Ordinal) ? name.Substring(prefix.Length) : name;
        }

        private static string CachedRuntimeId(AutomationElement e)
        {
            try
            {
                return RuntimeIdText(e.GetCachedPropertyValue(AutomationElement.RuntimeIdProperty) as int[]);
            }
            catch (Exception)
            {
                return "";
            }
        }

        internal static string RuntimeIdText(int[] ids)
        {
            if (ids == null) return "";
            var sb = new StringBuilder();
            for (int i = 0; i < ids.Length; i++)
            {
                if (i > 0) sb.Append(' ');
                sb.Append(ids[i]);
            }
            return sb.ToString();
        }

        private static Rectangle CachedRect(AutomationElement e)
        {
            try { return ToRectangle(e.Cached.BoundingRectangle); }
            catch (Exception) { return Rectangle.Empty; }
        }

        /// <summary>UI Automation reports "no rectangle" as infinities; those become an empty rectangle instead of garbage coordinates.</summary>
        internal static Rectangle ToRectangle(System.Windows.Rect r)
        {
            if (r.IsEmpty) return Rectangle.Empty;
            return new Rectangle(ToInt(r.X), ToInt(r.Y), Math.Max(0, ToInt(r.Width)), Math.Max(0, ToInt(r.Height)));
        }

        private static int ToInt(double d)
        {
            if (double.IsNaN(d) || double.IsInfinity(d)) return 0;
            if (d > 1000000) return 1000000;
            if (d < -1000000) return -1000000;
            return (int)Math.Round(d);
        }

        // -- hover / pick -----------------------------------------------------------------------

        /// <summary>The element under a screen point, with its properties fetched in one call; null when nothing is there.</summary>
        internal static ElementInfo ElementAt(int x, int y)
        {
            CacheRequest cache = NewCache(false);
            AutomationElement element;
            using (cache.Activate())
            {
                element = AutomationElement.FromPoint(new System.Windows.Point(x, y));
            }
            return element == null ? null : Describe(element);
        }

        /// <summary>Reads the cached properties of an element that was obtained under the hover cache request.</summary>
        private static ElementInfo Describe(AutomationElement e)
        {
            var info = new ElementInfo();
            info.Element = e;
            info.ControlType = CachedControlType(e);
            info.Name = Text(delegate { return e.Cached.Name; });
            info.AutomationId = Text(delegate { return e.Cached.AutomationId; });
            info.RuntimeId = CachedRuntimeId(e);
            info.Rect = CachedRect(e);
            try { info.ProcessId = e.Cached.ProcessId; }
            catch (Exception) { info.ProcessId = 0; }
            return info;
        }

        // -- windows ----------------------------------------------------------------------------

        /// <summary>
        /// The windows a person would call "open windows" (Appendix J listWindows): visible, titled, on screen (not cloaked), not tool
        /// windows, not the shell, not hung. The helper's own windows are never visible, so they never appear.
        /// </summary>
        internal static List<IntPtr> TopLevelWindows()
        {
            var result = new List<IntPtr>();
            IntPtr shell = Native.GetShellWindow();
            Native.EnumWindows(delegate(IntPtr hWnd, IntPtr lParam)
            {
                if (hWnd == shell) return true;
                if (!Native.IsWindowVisible(hWnd)) return true;
                if (Native.IsCloaked(hWnd)) return true;
                if (Native.IsToolWindow(hWnd)) return true;
                if (Native.IsHungAppWindow(hWnd)) return true;
                if (Native.WindowTitle(hWnd).Length == 0) return true;
                result.Add(hWnd);
                return true;
            }, IntPtr.Zero);
            return result;
        }

        // -- screenshot -------------------------------------------------------------------------

        /// <summary>
        /// A PNG of the window at physical resolution (origin = the window's top-left). The window is asked to paint itself
        /// (<c>PrintWindow</c> with full content), so another window lying on top of it, UIScope's own included, does not end up in the picture;
        /// when that yields nothing (a black or empty result) the pixels are copied from the screen instead.
        /// </summary>
        internal static string Screenshot(IntPtr hwnd, Rectangle rect, string folder, out string method)
        {
            int width = Math.Min(Math.Max(1, rect.Width), MaxBitmapSide);
            int height = Math.Min(Math.Max(1, rect.Height), MaxBitmapSide);
            string target = EnsureFolder(folder);
            PruneOld(target);
            string path = Path.Combine(target, "uiscope-helper-" + Guid.NewGuid().ToString("N") + ".png");
            using (var bitmap = new Bitmap(width, height, PixelFormat.Format32bppArgb))
            {
                method = "printwindow";
                if (!RenderWithPrintWindow(hwnd, bitmap) || LooksBlank(bitmap))
                {
                    method = "screen";
                    using (Graphics graphics = Graphics.FromImage(bitmap))
                    {
                        graphics.Clear(Color.White);
                        graphics.CopyFromScreen(rect.Left, rect.Top, 0, 0, new Size(width, height));
                    }
                }
                bitmap.Save(path, ImageFormat.Png);
            }
            return path;
        }

        private static bool RenderWithPrintWindow(IntPtr hwnd, Bitmap bitmap)
        {
            try
            {
                using (Graphics graphics = Graphics.FromImage(bitmap))
                {
                    graphics.Clear(Color.Transparent);
                    IntPtr hdc = graphics.GetHdc();
                    try { return Native.PrintWindow(hwnd, hdc, Native.PW_RENDERFULLCONTENT); }
                    finally { graphics.ReleaseHdc(hdc); }
                }
            }
            catch (Exception)
            {
                return false;
            }
        }

        /// <summary>True when a 16 x 16 sample of the picture is one single colour (a failed render is solid black or fully transparent).</summary>
        private static bool LooksBlank(Bitmap bitmap)
        {
            bool haveFirst = false;
            int first = 0;
            for (int row = 0; row < 16; row++)
            {
                for (int column = 0; column < 16; column++)
                {
                    int x = (bitmap.Width - 1) * column / 15;
                    int y = (bitmap.Height - 1) * row / 15;
                    int argb = bitmap.GetPixel(x, y).ToArgb();
                    if (!haveFirst)
                    {
                        first = argb;
                        haveFirst = true;
                    }
                    else if (argb != first)
                    {
                        return false;
                    }
                }
            }
            return true;
        }

        private static string EnsureFolder(string folder)
        {
            string target = string.IsNullOrEmpty(folder) ? Path.GetTempPath() : folder;
            try
            {
                Directory.CreateDirectory(target);
                return target;
            }
            catch (Exception)
            {
                return Path.GetTempPath();
            }
        }

        /// <summary>The helper's pictures are scratch files: ones older than a day are removed so the folder cannot grow without bound.</summary>
        private static void PruneOld(string folder)
        {
            try
            {
                DateTime cutoff = DateTime.UtcNow.AddHours(-24);
                foreach (string file in Directory.GetFiles(folder, "uiscope-helper-*.png"))
                {
                    try
                    {
                        if (File.GetLastWriteTimeUtc(file) < cutoff) File.Delete(file);
                    }
                    catch (IOException) { }
                    catch (UnauthorizedAccessException) { }
                }
            }
            catch (Exception)
            {
                // best effort only
            }
        }
    }
}
