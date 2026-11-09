// Applies the saved theme before the app renders, so dark mode never flashes white. Kept as a
// file (not inline) because the Content-Security-Policy allows scripts from 'self' only.
(function () {
  try {
    var saved = localStorage.getItem('tf_theme');
    var dark = saved === 'dark' || ((saved === null || saved === 'system') && window.matchMedia('(prefers-color-scheme: dark)').matches);
    if (dark) document.documentElement.classList.add('dark');
  } catch (e) { /* storage unavailable: light theme */ }
})();
