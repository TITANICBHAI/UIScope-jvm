// Compile-check stand-ins for UIAutomationClient/UIAutomationTypes (not available on Linux). Signatures mirror the real ones used by the helper.
using System;
namespace System.Windows.Automation
{
    public class AutomationProperty { }
    public abstract class Condition { }
    [Flags] public enum TreeScope { Element = 1, Children = 2, Descendants = 4, Parent = 8, Ancestors = 16, Subtree = 7 }
    public class ElementNotAvailableException : Exception { }
    public class ControlType { public string ProgrammaticName { get { return ""; } } }
    public static class Automation { public static Condition ControlViewCondition { get { return null; } } }
    public class CacheRequest
    {
        public void Add(AutomationProperty p) { }
        public Condition TreeFilter { get; set; }
        public TreeScope TreeScope { get; set; }
        public IDisposable Activate() { return null; }
    }
    public class AutomationElementCollection { public int Count { get { return 0; } } public AutomationElement this[int i] { get { return null; } } }
    public struct AutomationElementInformation
    {
        public string AutomationId { get { return ""; } } public string Name { get { return ""; } } public ControlType ControlType { get { return null; } }
        public string ClassName { get { return ""; } } public string FrameworkId { get { return ""; } } public System.Windows.Rect BoundingRectangle { get { return default(System.Windows.Rect); } }
        public bool IsPassword { get { return false; } } public bool IsEnabled { get { return true; } } public bool IsOffscreen { get { return false; } }
        public int ProcessId { get { return 0; } } public int NativeWindowHandle { get { return 0; } }
    }
    public class AutomationElement
    {
        public static readonly AutomationProperty AutomationIdProperty = null, NameProperty = null, ControlTypeProperty = null, ClassNameProperty = null,
            FrameworkIdProperty = null, BoundingRectangleProperty = null, IsPasswordProperty = null, IsEnabledProperty = null, IsOffscreenProperty = null,
            ProcessIdProperty = null, RuntimeIdProperty = null;
        public static AutomationElement RootElement { get { return null; } }
        public static AutomationElement FromHandle(IntPtr h) { return null; }
        public static AutomationElement FromPoint(System.Windows.Point p) { return null; }
        public AutomationElement GetUpdatedCache(CacheRequest r) { return null; }
        public AutomationElementCollection CachedChildren { get { return null; } }
        public AutomationElementInformation Cached { get { return default(AutomationElementInformation); } }
        public AutomationElementInformation Current { get { return default(AutomationElementInformation); } }
        public object GetCachedPropertyValue(AutomationProperty p) { return null; }
        public static bool operator ==(AutomationElement a, AutomationElement b) { return object.ReferenceEquals(a, b); }
        public static bool operator !=(AutomationElement a, AutomationElement b) { return !object.ReferenceEquals(a, b); }
        public override bool Equals(object o) { return base.Equals(o); }
        public override int GetHashCode() { return 0; }
    }
    public class TreeWalker { public static TreeWalker ControlViewWalker { get { return null; } } public AutomationElement GetParent(AutomationElement e) { return null; } }
}
