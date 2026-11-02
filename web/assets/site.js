/* Progressive enhancements: theme preference and accessible disclosure menu. */
(function () {
  'use strict';
  var root = document.documentElement;
  var toggle = document.querySelector('[data-theme-toggle]');
  var label = document.querySelector('[data-theme-label]');
  var themeMeta = document.querySelector('meta[name="theme-color"]');

  function applyTheme(theme, persist) {
    var safeTheme = theme === 'light' ? 'light' : 'dark';
    root.setAttribute('data-theme', safeTheme);
    if (label) label.textContent = safeTheme === 'dark' ? 'Light mode' : 'Dark mode';
    if (toggle) {
      var target = safeTheme === 'dark' ? 'light' : 'dark';
      toggle.setAttribute('aria-label', 'Switch to ' + target + ' mode');
      toggle.setAttribute('title', 'Switch to ' + target + ' mode');
      toggle.setAttribute('aria-pressed', String(safeTheme === 'light'));
    }
    if (themeMeta) themeMeta.setAttribute('content', safeTheme === 'dark' ? '#0b1119' : '#f4f7fb');
    if (persist) {
      try { window.localStorage.setItem('uiscope-theme', safeTheme); }
      catch (_) { /* Theme still changes for this page if storage is disabled. */ }
    }
  }

  var initial = root.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
  applyTheme(initial, false);
  if (toggle) {
    toggle.addEventListener('click', function () {
      applyTheme(root.getAttribute('data-theme') === 'light' ? 'dark' : 'light', true);
    });
  }

  var drawer = document.querySelector('.nav-drawer');
  if (drawer) {
    document.addEventListener('click', function (event) {
      if (drawer.open && !drawer.contains(event.target)) drawer.open = false;
      if (event.target.closest && event.target.closest('.nav-drawer .nav a')) drawer.open = false;
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && drawer.open) {
        drawer.open = false;
        var summary = drawer.querySelector('summary');
        if (summary) summary.focus();
      }
    });
  }
})();
