// Marks the page as scripted before its first paint, as `_includes/site-intro-head.html` does inline,
// for a page whose policy admits no inline script (`api-keys.html`, #764): the header's layout
// (`site-header-style.html`) keys on it. No intro here: a tool page plays none.
document.documentElement.classList.add('js');
