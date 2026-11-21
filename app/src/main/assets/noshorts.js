// NoShorts filter for m.youtube.com. Injected on every page load and in-app navigation; safe to run repeatedly.
(function () {
  'use strict';

  if (window.__noShorts) { window.__noShorts.scan(); return; }

  // ---- 1. Never stay on a Short: open the same video in the regular player instead.
  function shortsId(path) {
    var m = /^\/shorts\/([A-Za-z0-9_-]+)/.exec(path || '');
    return m ? m[1] : null;
  }

  function redirectIfShorts() {
    var id = shortsId(location.pathname);
    if (id) { location.replace('/watch?v=' + id); return true; }
    return false;
  }

  if (redirectIfShorts()) return;

  ['pushState', 'replaceState'].forEach(function (fn) {
    var original = history[fn];
    history[fn] = function () {
      var result = original.apply(this, arguments);
      redirectIfShorts();
      return result;
    };
  });
  window.addEventListener('popstate', redirectIfShorts);

  // A tap on any leftover Shorts link goes to the normal player.
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest && e.target.closest('a[href*="/shorts/"]');
    if (!a) return;
    var id = shortsId(new URL(a.href, location.href).pathname);
    if (!id) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    location.href = '/watch?v=' + id;
  }, true);

  // ---- 2. CSS: hide what can be matched by selector alone. One rule per selector, so a
  // selector an older WebView doesn't understand can't knock out the others.
  var HIDE = [
    // Shorts shelves (Home, Subscriptions, Search, under the player)
    'ytm-reel-shelf-renderer',
    'ytm-rich-section-renderer:has(ytm-reel-shelf-renderer)',
    'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model)',
    'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model-v2)',
    'grid-shelf-view-model:has(ytm-shorts-lockup-view-model)',
    'grid-shelf-view-model:has(ytm-shorts-lockup-view-model-v2)',
    // Individual Shorts tiles
    'ytm-shorts-lockup-view-model',
    'ytm-shorts-lockup-view-model-v2',
    'ytm-reel-item-renderer',
    'ytm-rich-item-renderer:has(a[href^="/shorts/"])',
    'ytm-video-with-context-renderer:has(a[href^="/shorts/"])',
    'ytm-compact-video-renderer:has(a[href^="/shorts/"])',
    // "Shorts" tab in the bottom bar
    'ytm-pivot-bar-item-renderer:has(.pivot-shorts)',
    // Desktop layout, in case the site ever serves it
    'ytd-reel-shelf-renderer',
    'ytd-rich-shelf-renderer[is-shorts]',
    'ytd-rich-section-renderer:has(ytd-rich-shelf-renderer[is-shorts])',
    'ytd-guide-entry-renderer:has(a[title="Shorts"])',
    'ytd-mini-guide-entry-renderer[aria-label="Shorts"]'
  ];
  var CSS = HIDE.map(function (s) { return s + '{display:none!important}'; }).join('\n');

  function ensureStyle() {
    if (document.getElementById('noshorts-style')) return;
    var style = document.createElement('style');
    style.id = 'noshorts-style';
    style.textContent = CSS;
    (document.head || document.documentElement).appendChild(style);
  }

  // ---- 3. Script pass for what CSS can't express (text labels, unknown wrappers).
  var ITEMS = [
    'ytm-rich-item-renderer', 'ytm-video-with-context-renderer', 'ytm-compact-video-renderer',
    'ytm-shorts-lockup-view-model', 'ytm-shorts-lockup-view-model-v2', 'ytm-reel-item-renderer',
    'ytm-video-card-renderer', 'ytm-media-item',
    'ytd-rich-item-renderer', 'ytd-video-renderer', 'ytd-grid-video-renderer', 'ytd-compact-video-renderer'
  ].join(',');
  var SHELVES = [
    'ytm-reel-shelf-renderer', 'grid-shelf-view-model', 'ytm-rich-section-renderer',
    'ytd-reel-shelf-renderer', 'ytd-rich-shelf-renderer', 'ytd-rich-section-renderer'
  ].join(',');
  var SHORTS_INSIDE =
    'ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2, ytm-reel-item-renderer, a[href*="/shorts/"]';
  // Things labelled "Shorts": bottom-bar tab, channel tab, search filter chip, side menu entry.
  var LABELLED = [
    'ytm-pivot-bar-item-renderer', 'ytm-chip-cloud-chip-renderer', 'yt-chip-cloud-chip-view-model',
    'chip-view-model', '[role="tab"]', '.scbrr-tab', 'yt-tab-shape',
    'ytd-guide-entry-renderer', 'ytd-mini-guide-entry-renderer', 'tp-yt-paper-tab'
  ].join(',');

  function hide(el) {
    if (!el || el.hasAttribute('data-noshorts')) return;
    el.setAttribute('data-noshorts', '');
    el.style.setProperty('display', 'none', 'important');
  }

  function scan() {
    ensureStyle();

    document.querySelectorAll('a[href*="/shorts/"]').forEach(function (a) {
      if (!shortsId(new URL(a.href, location.href).pathname)) return;
      hide(a.closest(ITEMS) || a);
    });

    document.querySelectorAll(SHELVES).forEach(function (shelf) {
      var tag = shelf.tagName.toLowerCase();
      if (tag === 'ytm-reel-shelf-renderer' || tag === 'ytd-reel-shelf-renderer' ||
          shelf.hasAttribute('is-shorts') || shelf.querySelector(SHORTS_INSIDE)) {
        hide(shelf);
      }
    });

    document.querySelectorAll(LABELLED).forEach(function (el) {
      var label = (el.getAttribute('aria-label') || el.textContent || '').trim();
      if (/^shorts$/i.test(label) || el.querySelector('.pivot-shorts')) hide(el);
    });

    // Channel page "Shorts" tab, linked as /@channel/shorts
    document.querySelectorAll('a[href$="/shorts"]').forEach(function (a) {
      hide(a.closest(LABELLED) || a);
    });
  }

  // Re-scan whenever the page changes, at most once per frame.
  var queued = false;
  function queueScan() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(function () {
      queued = false;
      try { scan(); } catch (err) { /* keep the page usable if YouTube changes its markup */ }
    });
  }

  window.__noShorts = { scan: queueScan };
  ensureStyle();
  queueScan();
  new MutationObserver(queueScan).observe(document.documentElement, { childList: true, subtree: true });
})();
