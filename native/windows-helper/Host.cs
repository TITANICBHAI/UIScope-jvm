using System;
using System.Windows.Forms;

namespace UIScope.Helper
{
    /// <summary>
    /// Hidden message-loop window (plan P3-4/P3-6): owns the hotkey registrations and receives WM_HOTKEY.
    /// Never visible; its message loop pumps everything else (pick sessions, timers, hooks).
    /// </summary>
    internal class HostForm : Form
    {
        public const int WM_HOTKEY = 0x0312;

        public event Action<int> HotkeyPressed;

        public HostForm()
        {
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            Opacity = 0;
            Text = "UIScopeHelperHost";
        }

        // Never visible: the message loop runs anyway (classic hidden-form pattern).
        protected override void SetVisibleCore(bool value)
        {
            base.SetVisibleCore(false);
        }

        public bool TryRegisterHotkey(int id, uint modifiers, uint key)
        {
            return RegisterHotKey(Handle, id, modifiers, key);
        }

        public bool TryUnregisterHotkey(int id)
        {
            return UnregisterHotKey(Handle, id);
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_HOTKEY && HotkeyPressed != null) HotkeyPressed(m.WParam.ToInt32());
            base.WndProc(ref m);
        }

        [System.Runtime.InteropServices.DllImport("user32.dll")]
        private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint modifiers, uint vk);

        [System.Runtime.InteropServices.DllImport("user32.dll")]
        private static extern bool UnregisterHotKey(IntPtr hWnd, int id);
    }
}
