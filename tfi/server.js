import http from 'http';

const PORT      = parseInt(process.env.TFI_PORT ?? '8108');
const GTFSR_URL = process.env.GTFSR_STOP_TIMES_URL ?? 'http://localhost:8110';

// ---------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------

async function fetchDepartures(code) {
  const res = await fetch(`${GTFSR_URL}/departures/${encodeURIComponent(code)}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`service ${res.status}`);
  return res.json();
}

async function fetchStops(q) {
  const res = await fetch(`${GTFSR_URL}/stops?q=${encodeURIComponent(q)}`);
  if (!res.ok) return [];
  return res.json();
}

async function fetchRoutes(q) {
  const res = await fetch(`${GTFSR_URL}/routes?q=${encodeURIComponent(q)}`);
  if (!res.ok) return [];
  return res.json();
}

async function fetchRouteStops(route, direction) {
  const res = await fetch(`${GTFSR_URL}/route-stops?route=${encodeURIComponent(route)}&direction=${encodeURIComponent(direction)}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`service ${res.status}`);
  return res.json();
}

// ---------------------------------------------------------------------------
// HTML
// ---------------------------------------------------------------------------

const css = `
  *, *::before, *::after { box-sizing: border-box; }
  :root { --blue: #003b8c; --yellow: #ffd200; --bg: #eef1f7; --r: 3px; }
  body { margin: 0; font-family: system-ui, sans-serif; background: var(--bg); color: #111; min-height: 100dvh; }
  header { background: var(--blue); padding: .6rem 1.25rem; display: flex; align-items: center; gap: .75rem;
           border-bottom: 3px solid var(--yellow); }
  .badge { background: var(--yellow); color: var(--blue); font-weight: 900; font-size: 1rem;
           padding: .2rem .5rem; border-radius: var(--r); letter-spacing: .04em; }
  header span { color: #fff; font-weight: 700; font-size: 1.05rem; }
  main { max-width: 680px; margin: 0 auto; padding: 1.5rem 1rem; }
  h2 { font-size: .8rem; font-weight: 700; text-transform: uppercase; letter-spacing: .08em;
       color: var(--blue); margin: 0 0 .5rem; }
  .search-wrap { position: relative; margin-bottom: 1.5rem; }
  .search-input { width: 100%; padding: .65rem 1rem; font-size: 1rem; border: 2px solid var(--blue);
                  border-radius: var(--r); background: #fff; outline: none; font-family: inherit; }
  .search-input:focus { border-color: var(--yellow); box-shadow: 0 0 0 3px rgba(255,210,0,.35); }
  .dropdown { position: absolute; top: calc(100% + 1px); left: 0; right: 0; background: #fff;
              border: 2px solid var(--blue); border-top: none; border-radius: 0 0 var(--r) var(--r);
              list-style: none; margin: 0; padding: 0; z-index: 50; display: none; }
  .dropdown.open { display: block; }
  .dropdown li a { display: flex; gap: .6rem; align-items: center; padding: .55rem 1rem;
                   text-decoration: none; color: #111; font-size: .9rem; }
  .dropdown li a:hover { background: var(--yellow); }
  .dropdown li a .code { font-weight: 700; color: var(--blue); min-width: 3rem; }
  .dropdown li a .route-code { background: var(--blue); color: #fff; border-radius: 2px;
                                padding: .1rem .35rem; font-size: .8rem; }
  .dropdown .sep { padding: .2rem 1rem; font-size: .7rem; font-weight: 700; text-transform: uppercase;
                   letter-spacing: .08em; color: #999; background: #f5f7fc; border-bottom: 1px solid #e8ecf4; }
  table { width: 100%; border-collapse: collapse; background: #fff;
          border: 1px solid #d1daea; border-radius: var(--r); overflow: hidden; }
  thead th { text-align: left; padding: .5rem .75rem; background: var(--blue); color: #fff;
             font-size: .78rem; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; }
  tbody td { padding: .5rem .75rem; border-bottom: 1px solid #e8ecf4; font-size: .9rem; vertical-align: middle; }
  tbody tr:last-child td { border-bottom: none; }
  tbody tr:hover td { background: #f5f7fc; }
  .pill { display: inline-block; padding: .15rem .45rem; border-radius: var(--r);
          font-size: .72rem; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; }
  .live  { background: #d4edda; color: #155724; }
  .sched { background: #e9ecef; color: #555; }
  .due   { background: var(--yellow); color: var(--blue); }
  .late  { color: #b00; font-weight: 600; }
  .early { color: #0a6f0a; font-weight: 600; }
  .stop-title { margin: 0 0 .25rem; font-size: 1.2rem; font-weight: 800; color: var(--blue); }
  .stop-meta  { margin: 0 0 .75rem; font-size: .82rem; color: #666; }
  .bottom-row { display: flex; align-items: center; justify-content: space-between;
                flex-wrap: wrap; gap: .5rem; margin-top: .75rem; }
  .refresh-toggle { display: flex; align-items: center; gap: .4rem;
                    font-size: .8rem; color: #555; cursor: pointer; user-select: none; }
  #fav-ui { margin-bottom: 1rem; }
  .fav-btn { display: flex; align-items: center; gap: .35rem; background: none; border: 1px solid #d1daea;
             border-radius: var(--r); padding: .3rem .65rem; font-size: .82rem; color: #333;
             cursor: pointer; font-family: inherit; background: #fff; }
  .fav-btn:hover { border-color: var(--blue); color: var(--blue); }
  .fav-btn.saved { border-color: var(--yellow); background: var(--yellow); color: var(--blue); font-weight: 600; }
  .fav-name-row { display: flex; align-items: center; gap: .4rem; margin-top: .4rem; }
  .fav-name-input { flex: 1; padding: .3rem .5rem; font-size: .82rem; border: 1px solid #d1daea;
                    border-radius: var(--r); font-family: inherit; outline: none; }
  .fav-name-input:focus { border-color: var(--blue); }
  .fav-remove { background: none; border: none; cursor: pointer; color: #999; font-size: .8rem;
                padding: .2rem .4rem; border-radius: var(--r); }
  .fav-remove:hover { background: #fee2e2; color: #b00; }
  .fav-stop-row td { background: #eef1f7; padding: .35rem .75rem; font-size: .8rem; font-weight: 600; color: #555; }
  .fav-stop-row a { color: #444; text-decoration: none; }
  .fav-stop-row a:hover { text-decoration: underline; }
  .fav-stop-row .fav-code { color: #999; margin-left: .4rem; font-weight: 400; }
  .drag-handle { float: right; cursor: grab; color: #aaa; font-size: 1rem; line-height: 1; user-select: none; }
  .drag-handle:active { cursor: grabbing; }
  .fav-stop-row.dragging td { opacity: .35; }
  .fav-stop-row.drop-before td { border-top: 2px solid var(--blue) !important; }
  .drop-after td { border-bottom: 2px solid var(--blue) !important; }
  .fav-none { padding: .75rem 1rem; color: #888; font-size: .88rem; margin: 0; }
  .error-box { background: #fff3cd; border: 1px solid #f59e0b; border-left: 4px solid #f59e0b;
               border-radius: var(--r); padding: .75rem 1rem; color: #92400e; margin-bottom: 1.5rem; }
  @media (max-width: 600px) {
    .dep-table thead th:nth-child(3), .dep-table thead th:nth-child(4),
    .dep-table tbody td:nth-child(3), .dep-table tbody td:nth-child(4) { display: none; }
  }
`;

// Shared localStorage helpers injected once per page
const favsJs = `
<script>
window.TFI = (function(){
  const KEY = 'tfi-favourites';
  function load() { try { return JSON.parse(localStorage.getItem(KEY) || '[]'); } catch { return []; } }
  function save(list) { localStorage.setItem(KEY, JSON.stringify(list)); }
  function get(code) { return load().find(f => f.code === code) || null; }
  function add(code, name) {
    const list = load().filter(f => f.code !== code);
    list.push({ code, name });
    save(list);
  }
  function remove(code) { save(load().filter(f => f.code !== code)); }
  function rename(code, name) {
    const list = load().map(f => f.code === code ? { ...f, name } : f);
    save(list);
  }
  function reorder(codes) {
    const map = Object.fromEntries(load().map(f => [f.code, f]));
    save(codes.map(c => map[c]).filter(Boolean));
  }
  return { load, get, add, remove, rename, reorder };
})();
</script>`;

function page(title, body) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title>
<style>${css}</style>
${favsJs}
</head>
<body>
<header><a href="./" style="display:flex;align-items:center;gap:.75rem;text-decoration:none"><span class="badge">TFI</span><span style="color:#fff;font-weight:700;font-size:1.05rem">Live Departures</span></a></header>
<main>${body}</main>
</body>
</html>`;
}

const searchWidget = (prefill = '') => `
<h2>Find a stop</h2>
<div class="search-wrap">
  <input id="sq" class="search-input" type="search" autocomplete="off" spellcheck="false"
         placeholder="Stop name or number e.g. 3368" value="${escHtml(prefill)}">
  <ul id="dd" class="dropdown" role="listbox"></ul>
</div>
<script>
(function(){
  const inp = document.getElementById('sq');
  const dd  = document.getElementById('dd');
  let t;
  inp.addEventListener('input', () => {
    clearTimeout(t);
    const q = inp.value.trim();
    if (q.length < 2) { dd.innerHTML=''; dd.classList.remove('open'); return; }
    t = setTimeout(async () => {
      const [stopsR, routesR] = await Promise.all([
        fetch('api/stops?q=' + encodeURIComponent(q)),
        fetch('api/routes?q=' + encodeURIComponent(q))
      ]);
      const stops = stopsR.ok ? await stopsR.json() : [];
      const routes = routesR.ok ? await routesR.json() : [];
      let html = '';
      if (routes.length) {
        html += '<li class="sep">Routes</li>' +
          routes.map(r =>
            '<li><a href="?route=' + encodeURIComponent(r.route_short_name) + '&direction=' + r.direction_id + '">' +
            '<span class="code route-code">' + r.route_short_name + '</span>' +
            '<span>' + r.from_stop + ' → ' + r.to_stop + '</span></a></li>'
          ).join('');
      }
      if (stops.length) {
        if (routes.length) html += '<li class="sep">Stops</li>';
        html += stops.map(s =>
          '<li><a href="?code=' + encodeURIComponent(s.stop_code) + '">' +
          '<span class="code">' + s.stop_code + '</span><span>' + s.stop_name + '</span></a></li>'
        ).join('');
      }
      dd.innerHTML = html;
      dd.classList.toggle('open', !!(routes.length || stops.length));
    }, 100);
  });
  document.addEventListener('click', e => { if (!inp.contains(e.target)) dd.classList.remove('open'); });
})();
</script>`;

function escHtml(s) {
  return String(s).replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;');
}

function toMins(hhmm) {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
}

function relTime(hhmm, nowMins) {
  const diff = toMins(hhmm) - nowMins;
  if (diff <= 0) return '<span class="pill due">Due</span>';
  if (diff === 1) return 'in 1 min';
  return `in ${diff} mins`;
}

function departureRow(d, nowMins) {
  const badge = d.realtime
    ? '<span class="pill live">Live</span>'
    : '<span class="pill sched">Sched</span>';

  const effective = d.estimated_departure ?? d.scheduled_departure;

  let expected = '—';
  let cls = '';
  if (d.estimated_departure) {
    const drift = toMins(d.estimated_departure) - toMins(d.scheduled_departure);
    if (drift > 0)      { cls = 'late';  expected = `${d.estimated_departure} <small>(+${drift}m)</small>`; }
    else if (drift < 0) { cls = 'early'; expected = `${d.estimated_departure} <small>(${drift}m)</small>`; }
    else                { expected = d.estimated_departure; }
  }

  return `<tr>
    <td><a href="?route=${encodeURIComponent(d.route_short_name)}" style="color:var(--blue);font-weight:700;text-decoration:none">${escHtml(d.route_short_name)}</a></td>
    <td>${escHtml(d.trip_headsign)}</td>
    <td>${escHtml(d.scheduled_departure)}</td>
    <td class="${cls}">${expected}</td>
    <td>${relTime(effective, nowMins)}</td>
    <td>${badge}</td>
  </tr>`;
}

function renderHome() {
  const body = `
${searchWidget()}
<div id="favs-section"></div>
<div class="bottom-row" style="margin-top:.75rem">
  <label class="refresh-toggle">
    <input type="checkbox" id="auto-refresh">
    Update every 30 seconds
  </label>
</div>
<script>
(function(){
  const cb = document.getElementById('auto-refresh');
  const RK = 'tfi-auto-refresh';
  let timer;
  if (localStorage.getItem(RK) !== 'off') cb.checked = true;
  function arm() { clearTimeout(timer); if (cb.checked) timer = setTimeout(() => location.reload(), 30000); }
  cb.addEventListener('change', () => { localStorage.setItem(RK, cb.checked ? 'on' : 'off'); arm(); });
  arm();
})();
</script>
<script>
(async function(){
  if (!TFI.load().length) return;
  const el = document.getElementById('favs-section');
  el.innerHTML = '<h2>Favourites</h2>';

  function toMins(t) { const [h,m] = t.split(':').map(Number); return h*60+m; }
  function relTime(t) {
    const now = new Date();
    const diff = toMins(t) - (now.getHours()*60 + now.getMinutes());
    if (diff <= 0) return '<span class="pill due">Due</span>';
    if (diff === 1) return 'in 1 min';
    return 'in ' + diff + ' mins';
  }

  const depsCache = {};
  async function loadDeps(code) {
    if (code in depsCache) return depsCache[code];
    try {
      const r = await fetch('api/departures?code=' + encodeURIComponent(code));
      if (!r.ok) throw new Error();
      const data = await r.json();
      depsCache[code] = (data.departures || []).slice(0, 3);
    } catch { depsCache[code] = null; }
    return depsCache[code];
  }

  function renderTable() {
    const favs = TFI.load();
    let tbody = '';
    for (const fav of favs) {
      const deps = depsCache[fav.code];
      tbody += '<tr class="fav-stop-row" draggable="true" data-code="' + fav.code + '">' +
        '<td colspan="6">' +
        '<a href="?code=' + encodeURIComponent(fav.code) + '">' + fav.name + '</a>' +
        '<span class="fav-code">' + fav.code + '</span>' +
        '<span class="drag-handle">⠿</span>' +
        '</td></tr>';
      if (deps === null || deps === undefined) {
        tbody += '<tr><td colspan="6" class="fav-none">Could not load.</td></tr>';
      } else if (!deps.length) {
        tbody += '<tr><td colspan="6" class="fav-none">No upcoming departures.</td></tr>';
      } else {
        for (const d of deps) {
          const eff = d.estimated_departure || d.scheduled_departure;
          const badge = d.realtime ? '<span class="pill live">Live</span>' : '<span class="pill sched">Sched</span>';
          let expected = '—', cls = '';
          if (d.estimated_departure) {
            const drift = toMins(d.estimated_departure) - toMins(d.scheduled_departure);
            if (drift > 0)      { cls = 'late';  expected = d.estimated_departure + ' <small>(+' + drift + 'm)</small>'; }
            else if (drift < 0) { cls = 'early'; expected = d.estimated_departure + ' <small>(' + drift + 'm)</small>'; }
            else                { expected = d.estimated_departure; }
          }
          tbody += '<tr>' +
            '<td><a href="?route=' + encodeURIComponent(d.route_short_name) + '" style="color:var(--blue);font-weight:700;text-decoration:none">' + d.route_short_name + '</a></td>' +
            '<td>' + d.trip_headsign + '</td>' +
            '<td>' + d.scheduled_departure + '</td>' +
            '<td class="' + cls + '">' + expected + '</td>' +
            '<td>' + relTime(eff) + '</td>' +
            '<td>' + badge + '</td>' +
            '</tr>';
        }
      }
    }

    el.innerHTML = '<h2>Favourites</h2>' +
      '<table class="dep-table"><thead><tr><th>Route</th><th>Direction</th><th>Scheduled</th><th>Expected</th><th>Due</th><th></th></tr></thead>' +
      '<tbody>' + tbody + '</tbody></table>';

    let dragSrc = null;
    const tbody_el = el.querySelector('tbody');

    // --- helpers ---

    function lastRowOfGroup(headerRow) {
      let last = headerRow;
      let cur = headerRow.nextElementSibling;
      while (cur && !cur.classList.contains('fav-stop-row')) { last = cur; cur = cur.nextElementSibling; }
      return last;
    }

    function visibleStopRows() {
      return Array.from(el.querySelectorAll('.fav-stop-row'))
        .filter(function(r) { return r.dataset.code !== dragSrc; });
    }

    // Returns the code to insert AFTER (null = insert at top of list).
    function insertAfterCode(clientY) {
      var after = null;
      visibleStopRows().forEach(function(headerRow) {
        var top    = headerRow.getBoundingClientRect().top;
        var bottom = lastRowOfGroup(headerRow).getBoundingClientRect().bottom;
        if (clientY > (top + bottom) / 2) after = headerRow.dataset.code;
      });
      return after;
    }

    function clearDropIndicators() {
      el.querySelectorAll('.fav-stop-row').forEach(function(r) {
        r.classList.remove('dragging', 'drop-before');
      });
      el.querySelectorAll('.drop-after').forEach(function(r) { r.classList.remove('drop-after'); });
    }

    function showIndicator(after) {
      clearDropIndicators();
      var rows = visibleStopRows();
      if (!rows.length) return;
      if (after === null) {
        rows[0].classList.add('drop-before');
      } else {
        var idx = rows.findIndex(function(r) { return r.dataset.code === after; });
        if (idx < rows.length - 1) {
          rows[idx + 1].classList.add('drop-before');
        } else {
          lastRowOfGroup(rows[idx]).classList.add('drop-after');
        }
      }
    }

    function commitReorder(after) {
      var others = TFI.load().map(function(f) { return f.code; }).filter(function(c) { return c !== dragSrc; });
      var insertAt = after === null ? 0 : others.indexOf(after) + 1;
      others.splice(insertAt, 0, dragSrc);
      TFI.reorder(others);
      renderTable();
    }

    function buildClone(row) {
      var w = el.querySelector('table').offsetWidth;
      var t = document.createElement('table');
      t.style.cssText = 'position:fixed;pointer-events:none;z-index:9999;border-collapse:collapse;width:' + w + 'px;background:#fff;box-shadow:0 4px 16px rgba(0,0,0,.25);border-radius:3px;overflow:hidden;opacity:.9;';
      var tb = document.createElement('tbody');
      var cur = row;
      while (cur) { tb.appendChild(cur.cloneNode(true)); cur = cur.nextElementSibling; if (!cur || cur.classList.contains('fav-stop-row')) break; }
      t.appendChild(tb);
      return t;
    }

    // --- mouse drag ---

    tbody_el.addEventListener('dragstart', function(e) {
      var row = e.target.closest('.fav-stop-row');
      if (!row) { e.preventDefault(); return; }
      dragSrc = row.dataset.code;
      var ghost = buildClone(row);
      ghost.style.top = '-9999px'; ghost.style.left = '0';
      document.body.appendChild(ghost);
      var rect = row.getBoundingClientRect();
      e.dataTransfer.setDragImage(ghost, e.clientX - rect.left, e.clientY - rect.top);
      setTimeout(function() { document.body.removeChild(ghost); row.classList.add('dragging'); }, 0);
    });

    tbody_el.addEventListener('dragend', clearDropIndicators);

    tbody_el.addEventListener('dragover', function(e) {
      if (!dragSrc) return;
      e.preventDefault();
      showIndicator(insertAfterCode(e.clientY));
    });

    tbody_el.addEventListener('drop', function(e) {
      e.preventDefault();
      if (!dragSrc) return;
      commitReorder(insertAfterCode(e.clientY));
    });

    // --- touch drag ---

    var touchSrc = null, touchClone = null, touchAfter = null;
    var touchOX = 0, touchOY = 0;

    tbody_el.addEventListener('touchstart', function(e) {
      if (!e.target.closest('.drag-handle')) return;
      var row = e.target.closest('.fav-stop-row');
      if (!row) return;
      dragSrc = touchSrc = row.dataset.code;
      var touch = e.touches[0];
      var rect = row.getBoundingClientRect();
      touchOX = touch.clientX - rect.left;
      touchOY = touch.clientY - rect.top;
      touchClone = buildClone(row);
      touchClone.style.left = (touch.clientX - touchOX) + 'px';
      touchClone.style.top  = (touch.clientY - touchOY) + 'px';
      document.body.appendChild(touchClone);
      row.classList.add('dragging');
    }, { passive: true });

    tbody_el.addEventListener('touchmove', function(e) {
      if (!touchSrc) return;
      e.preventDefault();
      var touch = e.touches[0];
      touchClone.style.left = (touch.clientX - touchOX) + 'px';
      touchClone.style.top  = (touch.clientY - touchOY) + 'px';
      touchClone.style.visibility = 'hidden';
      var below = document.elementFromPoint(touch.clientX, touch.clientY);
      touchClone.style.visibility = '';
      touchAfter = insertAfterCode(touch.clientY);
      showIndicator(touchAfter);
    }, { passive: false });

    function endTouch() {
      if (!touchSrc) return;
      if (touchClone) { document.body.removeChild(touchClone); touchClone = null; }
      var after = touchAfter;
      touchSrc = null; touchAfter = null;
      clearDropIndicators();
      if (after !== undefined) commitReorder(after);
    }

    tbody_el.addEventListener('touchend',    endTouch, { passive: true });
    tbody_el.addEventListener('touchcancel', endTouch, { passive: true });
  }

  await Promise.all(TFI.load().map(function(fav) { return loadDeps(fav.code); }));
  renderTable();
})();
</script>`;
  return page('TFI Live Departures', body);
}

function renderStop(stop, departures) {
  const nowDate = new Date();
  const nowMins = nowDate.getHours() * 60 + nowDate.getMinutes();
  const now = nowDate.toLocaleTimeString('en-IE', { hour: '2-digit', minute: '2-digit' });
  const rows = departures.length
    ? departures.map(d => departureRow(d, nowMins)).join('')
    : `<tr><td colspan="6" style="padding:1.5rem;text-align:center;color:#888">
         No departures in the next 105 minutes.
       </td></tr>`;

  const board = `
<p class="stop-title">${escHtml(stop.stop_name)}</p>
<p class="stop-meta">Stop ${escHtml(stop.stop_code)} &middot; as of ${now}</p>
<div id="fav-ui"></div>
<table class="dep-table">
  <thead><tr><th>Route</th><th>Direction</th><th>Scheduled</th><th>Expected</th><th>Due</th><th></th></tr></thead>
  <tbody>${rows}</tbody>
</table>
<div class="bottom-row">
  <label class="refresh-toggle">
    <input type="checkbox" id="auto-refresh" checked>
    Update every 30 seconds
  </label>
</div>
<script>
(function(){
  // Auto-refresh
  const cb = document.getElementById('auto-refresh');
  const RK = 'tfi-auto-refresh';
  let timer;
  if (localStorage.getItem(RK) === 'off') cb.checked = false;
  function arm() { clearTimeout(timer); if (cb.checked) timer = setTimeout(() => location.reload(), 30000); }
  cb.addEventListener('change', () => { localStorage.setItem(RK, cb.checked ? 'on' : 'off'); arm(); });
  arm();

  // Favourites
  const CODE = ${JSON.stringify(stop.stop_code)};
  const DEFAULT_NAME = ${JSON.stringify(stop.stop_name)};
  const ui = document.getElementById('fav-ui');

  function render() {
    const fav = TFI.get(CODE);
    if (fav) {
      ui.innerHTML =
        '<div class="fav-name-row">' +
          '<button class="fav-btn saved" onclick="removeFav()">★ Favourite</button>' +
          '<input class="fav-name-input" id="fav-name" value="' + fav.name.replace(/"/g,'&quot;') + '" placeholder="Name this stop">' +
          '<button class="fav-remove" onclick="removeFav()" title="Remove">✕</button>' +
        '</div>';
      document.getElementById('fav-name').addEventListener('input', e => {
        TFI.rename(CODE, e.target.value || DEFAULT_NAME);
      });
    } else {
      ui.innerHTML =
        '<button class="fav-btn" onclick="addFav()">☆ Add to favourites</button>';
    }
  }

  window.addFav = function() { TFI.add(CODE, DEFAULT_NAME); render(); };
  window.removeFav = function() { TFI.remove(CODE); render(); };

  render();
})();
</script>`;

  return page(`${stop.stop_name} — TFI`, searchWidget(stop.stop_code) + board);
}

async function renderRoute(route, direction) {
  const stops = await fetchRouteStops(route, direction);
  if (!stops) return renderError(`Route "${route}" direction ${direction} not found.`);

  const otherDir = direction === '0' ? 1 : 0;
  const from = stops[0]?.stop_name ?? '';
  const to   = stops[stops.length - 1]?.stop_name ?? '';

  const rows = stops.map(s => `<tr>
    <td style="color:#888;font-size:.8rem">${s.stop_sequence}</td>
    <td><a href="?code=${encodeURIComponent(s.stop_code)}" style="color:var(--blue);text-decoration:none">${escHtml(s.stop_name)}</a></td>
    <td style="font-variant-numeric:tabular-nums">${escHtml(s.stop_code)}</td>
  </tr>`).join('');

  const board = `
<p class="stop-title">Route ${escHtml(route)}</p>
<p class="stop-meta">${escHtml(from)} → ${escHtml(to)} &middot; ${stops.length} stops</p>
<div style="margin-bottom:.75rem">
  <a class="fav-btn" href="?route=${encodeURIComponent(route)}&direction=${otherDir}">&#8644; Reverse direction</a>
</div>
<table>
  <thead><tr><th>#</th><th>Stop</th><th>Code</th></tr></thead>
  <tbody>${rows}</tbody>
</table>`;

  return page(`Route ${route} — TFI`, searchWidget() + board);
}

function renderError(msg) {
  return page('TFI — Error', `<div class="error-box">${escHtml(msg)}</div>` + searchWidget());
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

http.createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost');

  if (url.pathname === '/api/routes') {
    const q = url.searchParams.get('q') ?? '';
    try {
      const routes = await fetchRoutes(q);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(routes));
    } catch {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end('[]');
    }
    return;
  }

  if (url.pathname === '/api/departures') {
    const code = url.searchParams.get('code')?.trim();
    if (!code) { res.writeHead(400); res.end('{}'); return; }
    try {
      const data = await fetchDepartures(code);
      res.writeHead(data ? 200 : 404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(data ?? {}));
    } catch {
      res.writeHead(502, { 'Content-Type': 'application/json' });
      res.end('{}');
    }
    return;
  }

  if (url.pathname === '/api/stops') {
    const q = url.searchParams.get('q') ?? '';
    try {
      const stops = await fetchStops(q);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(stops));
    } catch {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end('[]');
    }
    return;
  }

  const code  = url.searchParams.get('code')?.trim();
  const route = url.searchParams.get('route')?.trim();
  const direction = url.searchParams.get('direction') ?? '0';
  let body;
  try {
    if (route) {
      body = await renderRoute(route, direction);
    } else if (code) {
      const data = await fetchDepartures(code);
      body = data
        ? renderStop(data.stop, data.departures)
        : renderError(`Stop "${code}" not found.`);
    } else {
      body = renderHome();
    }
  } catch (err) {
    console.error('[tfi]', err);
    body = renderError('Could not load departures — service may be unavailable.');
  }

  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(body);

}).listen(PORT, '127.0.0.1', () => {
  console.log(`TFI on port ${PORT}, upstream ${GTFSR_URL}`);
});
