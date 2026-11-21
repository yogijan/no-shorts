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
  // App-like touch behaviour: no blue tap flashes, no text selection or link callouts on long-press.
  var APP_FEEL = [
    'html{-webkit-tap-highlight-color:transparent!important;-webkit-touch-callout:none}',
    'body{-webkit-user-select:none;user-select:none}',
    'input,textarea,[contenteditable],[contenteditable] *{-webkit-user-select:text;user-select:text}',
    // "Open the app" / "Try Premium" bottom-sheet promos
    'ytm-mealbar-promo-renderer{display:none!important}',
    // Picture-in-picture: the PiP window shows only the video
    'html.noshorts-pip,html.noshorts-pip body{overflow:hidden!important;background:#000!important}',
    'html.noshorts-pip video{position:fixed!important;left:0!important;top:0!important;width:100vw!important;' +
      'height:100vh!important;z-index:2147483647!important;object-fit:contain!important;background:#000!important;' +
      'transform:none!important}',
    'html.noshorts-pip ytm-mobile-topbar-renderer,html.noshorts-pip ytm-pivot-bar-renderer{visibility:hidden!important}'
  ];
  var CSS = HIDE.map(function (s) { return s + '{display:none!important}'; }).concat(APP_FEEL).join('\n');

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

  // ---- 4. Report to the app: page colour (so the system bars match YouTube's own light/dark
  // setting) and whether the main video is playing (keep screen on, picture-in-picture).
  function bridge() { return window.NoShortsAndroid; }

  function parseColor(str) {
    str = (str || '').trim();
    var m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(str);
    if (m) {
      var h = m[1].length === 3 ? m[1].replace(/./g, '$&$&') : m[1];
      return [0, 2, 4].map(function (i) { return parseInt(h.substr(i, 2), 16); });
    }
    m = /^rgba?\(([^)]+)\)$/i.exec(str);
    if (!m) return null;
    var p = m[1].split(/[\s,\/]+/).filter(Boolean).map(parseFloat);
    if (p.length < 3 || p.slice(0, 3).some(isNaN)) return null;
    if (p.length > 3 && p[3] < 0.5) return null; // transparent: not the real page colour
    return p.slice(0, 3).map(Math.round);
  }

  var lastColor = '';
  function reportPageColor() {
    var b = bridge();
    if (!b || !document.documentElement) return;
    var meta = document.querySelector('meta[name="theme-color"]');
    var rgb = parseColor(getComputedStyle(document.documentElement).backgroundColor) ||
      (document.body && parseColor(getComputedStyle(document.body).backgroundColor)) ||
      (meta && parseColor(meta.getAttribute('content')));
    if (!rgb || rgb.join() === lastColor) return;
    lastColor = rgb.join();
    b.setPageColor(rgb[0], rgb[1], rgb[2]);
  }

  // Muted previews that autoplay in feeds don't count; only the watch-page player does.
  var lastPlaying = null;
  function reportPlayback() {
    var b = bridge();
    if (!b) return;
    var playing = location.pathname === '/watch' &&
      Array.prototype.some.call(document.querySelectorAll('video'), function (v) {
        return !v.paused && !v.ended && !v.muted && v.readyState > 2;
      });
    if (playing === lastPlaying) return;
    lastPlaying = playing;
    b.setPlaying(playing);
  }
  ['play', 'playing', 'pause', 'ended', 'emptied', 'volumechange'].forEach(function (type) {
    document.addEventListener(type, reportPlayback, true);
  });

  function setPip(on) {
    document.documentElement.classList.toggle('noshorts-pip', !!on);
    if (on) window.scrollTo(0, 0);
  }

  // Re-scan whenever the page changes, at most once per frame.
  var queued = false;
  function queueScan() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(function () {
      queued = false;
      try { scan(); } catch (err) { /* keep the page usable if YouTube changes its markup */ }
      try { reportPageColor(); reportPlayback(); } catch (err) { /* reporting is best-effort */ }
    });
  }

  window.__noShorts = { scan: queueScan, setPip: setPip };
  ensureStyle();
  queueScan();
  new MutationObserver(queueScan).observe(document.documentElement, { childList: true, subtree: true });
})();
