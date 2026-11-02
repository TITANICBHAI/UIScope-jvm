/* Restore an explicit visitor choice before stylesheets paint. Dark is the default. */
(function () {
  try {
    var saved = window.localStorage.getItem('uiscope-theme');
    if (saved === 'light' || saved === 'dark') {
      document.documentElement.setAttribute('data-theme', saved);
    }
  } catch (_) { /* Storage may be unavailable; retain the dark default. */ }
})();
