const HTFT = ["1/1", "1/X", "1/2", "X/1", "X/X", "X/2", "2/1", "2/X", "2/2"];
const PRESETS = {
    "Odwrócenie (1/2 + 2/1)": ["1/2", "2/1"],
    "Prowadzenie → remis (1/X + 2/X)": ["1/X", "2/X"],
    "Remis → wygrana (X/1 + X/2)": ["X/1", "X/2"],
};

const state = {
    view: "daily",
    competitions: [],
    country: null,
    league: null,          // null = wszystkie ligi kraju
    events: new Set(["1/2", "2/1"]),
    stages: new Set(),
    sub: "pairs",
    countries: [],
    selectedCountries: new Set(),
    selectedLeagues: new Set(),
    daily: [],
};

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;"}[c]));
const pct = (a, b) => b ? (100 * a / b).toFixed(1) + "%" : "–";
const fmtDate = (d) => d ? d.slice(0, 10) : "";
const fmtTime = (d) => d ? d.slice(11, 16) : "";
// wyszukiwanie lig/krajów: bez wielkości liter i znaków diakrytycznych, kraje też po polsku
const norm = (s) => String(s ?? "").toLowerCase().replace(/ł/g, "l").replace(/ı/g, "i")
    .normalize("NFD").replace(/\p{M}/gu, "").replace(/-/g, " ");
const PL_COUNTRY = (() => {
    const m = {};
    try {
        const en = new Intl.DisplayNames(["en"], {type: "region"}), pl = new Intl.DisplayNames(["pl"], {type: "region"});
        const A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        for (const a of A) for (const b of A) {
            const code = a + b, name = en.of(code);
            if (name && name !== code) m[norm(name)] = pl.of(code);
        }
    } catch {
    }
    Object.assign(m, {england: "Anglia", scotland: "Szkocja", wales: "Walia", "northern ireland": "Irlandia Północna",
        turkey: "Turcja", usa: "USA Stany Zjednoczone", "czech republic": "Czechy", "south korea": "Korea Południowa",
        "ivory coast": "Wybrzeże Kości Słoniowej", "faroe islands": "Wyspy Owcze", world: "Świat"});
    return m;
})();
const matchesSearch = (q, country, league) =>
    norm(`${country}: ${league} ${PL_COUNTRY[norm(country)] ?? ""}`).includes(norm(q));
const isoDay = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

async function api(path, opts) {
    const res = await fetch(path, opts);
    const body = await res.json().catch(() => null);
    if (!res.ok) throw new Error(body?.error || body?.message || res.statusText);
    return body;
}

function chip(label, on, onClick, extra = "") {
    const b = document.createElement("button");
    b.className = `chip ${extra} ${on ? "on" : ""}`;
    b.textContent = label;
    b.onclick = onClick;
    return b;
}

// ---------------- nawigacja ----------------

function showView(view) {
    state.view = view;
    document.querySelectorAll("#mainTabs button").forEach(x => x.classList.toggle("active", x.dataset.view === view));
    document.querySelectorAll(".view").forEach(v => v.classList.toggle("active", v.id === "view-" + view));
    if (view === "analysis") loadCompetitions();
    if (view === "daily") loadDaily();
    if (view === "history") loadHistory();
    if (view === "strategy") {
        loadStrategy();
        pollStrategyJob();
    }
}

document.querySelectorAll("#mainTabs button").forEach(b => b.onclick = () => showView(b.dataset.view));

document.querySelectorAll("#subTabs button").forEach(b => b.onclick = () => {
    document.querySelectorAll("#subTabs button").forEach(x => x.classList.toggle("active", x === b));
    state.sub = b.dataset.sub;
    refresh();
});

// ---------------- zdarzenia HT/FT (wspólne dla Analizy i Meczów dnia) ----------------

function renderEvents() {
    document.querySelectorAll(".events-presets").forEach(box => {
        box.innerHTML = "";
        Object.entries(PRESETS).forEach(([name, ev]) => {
            const on = ev.length === state.events.size && ev.every(e => state.events.has(e));
            box.appendChild(chip(name, on, () => setEvents(new Set(ev)), "preset"));
        });
    });
    document.querySelectorAll(".events-chips").forEach(box => {
        box.innerHTML = "";
        HTFT.forEach(e => box.appendChild(chip(e, state.events.has(e), () => {
            const next = new Set(state.events);
            next.has(e) ? next.delete(e) : next.add(e);
            setEvents(next);
        })));
    });
}

function setEvents(events) {
    state.events = events;
    renderEvents();
    state.view === "daily" ? loadDaily() : refresh();
}

// ---------------- Analiza: lista lig ----------------

async function loadCompetitions() {
    state.competitions = await api("/api/v2/analytics/competitions");
    if (!state.country && state.competitions.length) {
        state.country = state.competitions[0].country;
        state.league = state.competitions[0].leagues[0]?.league ?? null;
    }
    renderNav();
    onSelectionChange();
}

function renderNav() {
    const q = $("leagueSearch").value.trim().toLowerCase();
    const html = state.competitions.map(c => {
        const leagues = c.leagues.filter(l => !q || c.country.toLowerCase().includes(q) || l.league.toLowerCase().includes(q));
        if (!leagues.length) return "";
        const total = c.leagues.reduce((a, l) => a + l.matches, 0);
        const countryActive = state.country === c.country && state.league === null;
        return `<div class="nav-country">
            <button class="${countryActive ? "active" : ""}" data-country="${esc(c.country)}" title="Wszystkie ligi kraju">
                <span>${esc(c.country)}</span><span class="nav-count">${total}</span></button>
            ${leagues.map(l => `<button class="nav-league ${state.country === c.country && state.league === l.league ? "active" : ""}"
                data-country="${esc(c.country)}" data-league="${esc(l.league)}">
                <span>${esc(l.league)}</span><span class="nav-count">${l.matches}</span></button>`).join("")}
        </div>`;
    }).join("");
    $("leagueNav").innerHTML = html || `<p class="muted small">Brak lig w bazie.</p>`;
    $("leagueNav").querySelectorAll("button").forEach(b => b.onclick = () => {
        state.country = b.dataset.country;
        state.league = b.dataset.league ?? null;
        renderNav();
        onSelectionChange();
    });
}

$("leagueSearch").oninput = renderNav;

function selectedLeagues() {
    const c = state.competitions.find(c => c.country === state.country);
    if (!c) return [];
    return state.league ? c.leagues.filter(x => x.league === state.league) : c.leagues;
}

function onSelectionChange() {
    $("aTitle").textContent = state.country ? `${state.country} · ${state.league ?? "wszystkie ligi"}` : "Wybierz ligę z listy";

    const leagues = selectedLeagues();
    const stages = [...new Set(leagues.flatMap(l => l.stages))].sort();
    // domyślnie tylko faza zasadnicza (Regular Season / Apertura / Clausura) - bez grup po podziale i play-offów
    state.stages = new Set(stages.filter(s => !/play|relegation|final|qualif|reclasific|group/i.test(s)));
    if (!state.stages.size) state.stages = new Set(stages);
    renderStages(stages);

    const seasons = new Map();
    leagues.forEach(l => Object.entries(l.seasons).forEach(([y, label]) => seasons.set(+y, label)));
    const years = [...seasons.keys()].sort();
    $("fFrom").innerHTML = years.map(y => `<option value="${y}">${esc(seasons.get(y))}</option>`).join("");
    refresh();
}

function renderStages(stages) {
    const box = $("fStages");
    box.innerHTML = "";
    stages.forEach(s => box.appendChild(chip(s, state.stages.has(s), () => {
        state.stages.has(s) ? state.stages.delete(s) : state.stages.add(s);
        renderStages(stages);
        refresh();
    })));
}

function query(extra = {}) {
    const p = new URLSearchParams();
    p.set("country", state.country);
    if (state.league) p.set("league", state.league);
    p.set("fromSeason", $("fFrom").value || 0);
    state.stages.forEach(s => p.append("stages", s));
    state.events.forEach(e => p.append("events", e));
    Object.entries(extra).forEach(([k, v]) => p.set(k, v));
    return p.toString();
}

["fFrom", "fMin"].forEach(id => $(id).onchange = refresh);

// ---------------- Analiza: widoki ----------------

let refreshSeq = 0;

async function refresh() {
    const out = $("result");
    if (!state.country) {
        out.innerHTML = `<p class="muted">Brak danych. Uruchom scraping w zakładce „Scraper”.</p>`;
        return;
    }
    if (state.sub !== "summary" && state.sub !== "matches" && !state.events.size) {
        out.innerHTML = `<p class="muted">Wybierz co najmniej jedno zdarzenie HT/FT.</p>`;
        return;
    }
    const seq = ++refreshSeq;
    out.innerHTML = `<p class="muted">Ładowanie…</p>`;
    try {
        const html = await ({pairs: viewPairs, summary: viewSummary, teams: viewTeams, matches: viewMatches})[state.sub]();
        if (seq === refreshSeq) out.innerHTML = html;
    } catch (e) {
        if (seq === refreshSeq) out.innerHTML = `<p class="muted">Błąd: ${esc(e.message)}</p>`;
    }
}

function matchRows(list, showLeague) {
    return list.map(m => `<tr>
        <td>${esc(m.season)}</td>${showLeague ? `<td>${esc(m.league)}</td>` : ""}<td>${esc(m.stage)}</td>
        <td>${esc(m.round ?? "")}</td><td>${fmtDate(m.kickoff)}</td>
        <td>${esc(m.home)} – ${esc(m.away)}</td>
        <td class="num">${esc(m.scoreHT ?? "")}</td><td class="num">${esc(m.scoreFT ?? "")}</td>
        <td class="${state.events.has(m.htft) ? "event" : ""}">${esc(m.htft ?? "")}</td></tr>`).join("");
}

async function viewPairs() {
    const pairs = await api("/api/v2/analytics/pairs?" + query({minCount: $("fMin").value || 1}));
    if (!pairs.length) return `<p class="muted">Żadna para nie miała tego zdarzenia ${$("fMin").value}+ razy.</p>`;
    window.__pairs = pairs;
    return `<p class="muted small">${pairs.length} par · kliknij wiersz, aby zobaczyć mecze · „spotkań” = wszystkie mecze tej pary w wybranym zakresie</p>
    <table><thead><tr><th>Para</th><th class="num">Ile razy</th><th class="num">Spotkań</th><th class="num">%</th><th>Ostatnio</th></tr></thead>
    <tbody>${pairs.map((p, i) => `
        <tr class="clickable" onclick="togglePair(${i}, this)">
            <td><b>${esc(p.teamA)}</b> & <b>${esc(p.teamB)}</b></td>
            <td class="num">${p.count}<span class="bar" style="width:${Math.min(p.count * 8, 80)}px"></span></td>
            <td class="num">${p.meetings}</td><td class="num">${pct(p.count, p.meetings)}</td>
            <td>${esc(p.occurrences.at(-1)?.season ?? "")}</td>
        </tr>`).join("")}</tbody></table>`;
}

window.togglePair = (i, tr) => {
    if (tr.nextElementSibling?.classList.contains("detail")) return tr.nextElementSibling.remove();
    const p = window.__pairs[i];
    tr.insertAdjacentHTML("afterend", `<tr class="detail"><td colspan="5"><table>${matchRows(p.occurrences, !state.league)}</table></td></tr>`);
};

async function viewSummary() {
    const rows = await api("/api/v2/analytics/summary?" + query());
    if (!rows.length) return `<p class="muted">Brak meczów.</p>`;
    const total = rows.reduce((a, r) => a + r.matches, 0);
    const sel = rows.reduce((a, r) => a + r.selected, 0);
    return `<table><thead><tr><th>Sezon</th><th>Etap</th><th class="num">Mecze</th>
        <th class="num hl">Wybrane</th><th class="num hl">%</th>
        ${HTFT.map(e => `<th class="num ${state.events.has(e) ? "hl" : ""}">${e}</th>`).join("")}</tr></thead>
        <tbody>${rows.map(r => `<tr><td>${esc(r.season)}</td><td>${esc(r.stage ?? "")}</td><td class="num">${r.matches}</td>
            <td class="num hl"><b>${r.selected}</b></td><td class="num hl">${pct(r.selected, r.matches)}</td>
            ${HTFT.map(e => `<td class="num ${state.events.has(e) ? "hl" : ""}">${r.htft[e]}</td>`).join("")}</tr>`).join("")}
        <tr><td><b>Razem</b></td><td></td><td class="num"><b>${total}</b></td><td class="num hl"><b>${sel}</b></td>
            <td class="num hl"><b>${pct(sel, total)}</b></td><td colspan="9"></td></tr>
        </tbody></table>`;
}

async function viewTeams() {
    const teams = await api("/api/v2/analytics/teams?" + query());
    if (!teams.length) return `<p class="muted">Brak.</p>`;
    return `<table><thead><tr><th>Drużyna</th><th class="num">Ile razy</th><th class="num">Mecze</th><th class="num">%</th></tr></thead>
        <tbody>${teams.map(t => `<tr><td>${esc(t.team)}</td><td class="num">${t.hits}</td><td class="num">${t.matches}</td>
        <td class="num">${pct(t.hits, t.matches)}</td></tr>`).join("")}</tbody></table>`;
}

async function viewMatches() {
    const list = await api("/api/v2/analytics/matches?" + query());
    const showLeague = !state.league;
    return `<p class="muted small">${list.length} meczów · wybrane zdarzenia są wyróżnione</p>
        <table><thead><tr><th>Sezon</th>${showLeague ? "<th>Liga</th>" : ""}<th>Etap</th><th>Runda</th><th>Data</th><th>Mecz</th>
        <th class="num">HT</th><th class="num">FT</th><th>HT/FT</th></tr></thead>
        <tbody>${matchRows(list.slice().reverse(), showLeague)}</tbody></table>`;
}

// ---------------- Mecze dnia ----------------

$("dDate").value = new URLSearchParams(location.search).get("date") || isoDay(new Date());
$("dDate").onchange = loadDaily;
$("dSearch").oninput = renderDaily;
$("dHitsOnly").onchange = renderDaily;
$("dMinRounds").oninput = $("dMinPair").oninput = renderDaily;
$("dDryEvents").onchange = loadDaily;
$("dToday").onclick = () => { $("dDate").value = new URLSearchParams(location.search).get("date") || isoDay(new Date()); loadDaily(); };
$("dPrev").onclick = () => shiftDay(-1);
$("dNext").onclick = () => shiftDay(1);

function shiftDay(delta) {
    const d = new Date($("dDate").value + "T12:00:00");
    d.setDate(d.getDate() + delta);
    $("dDate").value = isoDay(d);
    loadDaily();
}

let dailySeq = 0;

async function loadDaily() {
    const seq = ++dailySeq;
    $("dailyResult").innerHTML = `<p class="muted">Pobieram mecze dnia…</p>`;
    const p = new URLSearchParams({date: $("dDate").value, onlyTracked: false});
    state.events.forEach(e => p.append("events", e));
    $("dDryEvents").value.split(",").forEach(e => p.append("droughtEvents", e));
    try {
        const list = await api("/api/v2/daily?" + p);
        if (seq !== dailySeq) return;
        state.daily = list;
        renderDaily();
        loadDailyOdds(seq, $("dDate").value);
    } catch (e) {
        if (seq === dailySeq) $("dailyResult").innerHTML = `<p class="muted">Błąd: ${esc(e.message)}</p>`;
    }
}

/** Kursy 1X2 doładowywane po pokazaniu listy (pierwszy raz dla dnia trwa nawet minutę, potem są zapamiętane). */
async function loadDailyOdds(seq, date) {
    if (!state.daily.some(v => v.tracked && v.match.odd1 == null)) return;
    state.dailyOddsLoading = true;
    renderDaily();
    try {
        const odds = await api("/api/v2/daily/odds?date=" + date);
        if (seq !== dailySeq) return;
        state.daily.forEach(v => {
            const o = odds[v.match.externalId];
            if (o) [v.match.odd1, v.match.oddX, v.match.odd2] = o;
        });
    } catch (e) {
        console.warn("Kursy 1X2:", e.message);
    }
    if (seq === dailySeq) {
        state.dailyOddsLoading = false;
        renderDaily();
    }
}

function teamCell(stats) {
    if (!stats || !stats.matches) return `<span class="muted small">–</span>`;
    return `<span title="${stats.hits} z ${stats.matches} meczów w lidze">${stats.hits}/${stats.matches} <span class="muted small">(${pct(stats.hits, stats.matches)})</span></span>`;
}

const FOUR = ["1/2", "2/1", "1/X", "2/X"];
const meczeWord = (n) => n === 1 ? "mecz" : (n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14)) ? "mecze" : "meczów";

/** Rozbicie historii pary na 1/2, 2/1, 1/X, 2/X + kiedy ostatnio (ile spotkań temu, w jakiej lidze). */
function fourCell(v, league) {
    if (!v.pairMeetings) return `<span class="muted small">pierwszy mecz pary</span>`;
    const c = v.pairCounts || {};
    const sum = FOUR.reduce((a, e) => a + (c[e] || 0), 0);
    const nums = FOUR.map(e => `<span class="${c[e] ? "on" : ""}" title="${e}: ${c[e] || 0} z ${v.pairMeetings}">${c[e] || 0}</span>`).join("");
    let last = `<span class="muted small">nigdy</span>`;
    if (v.lastEvent) {
        const l = v.lastEvent;
        const ago = v.sinceLast === 0 ? "ostatni mecz" : `${v.sinceLast} ${meczeWord(v.sinceLast)} temu`;
        const otherLeague = l.league && l.league !== league ? ` · ${esc(l.league)}` : "";
        last = `<span class="small" title="${esc(l.home)} – ${esc(l.away)} ${esc(l.scoreHT)} / ${esc(l.scoreFT)}, ${esc(l.league)} ${esc(l.season)}">
            ostatnio <b>${esc(l.htft)}</b> ${fmtDate(l.kickoff)} (${ago}${otherLeague})</span>`;
    }
    return `<span class="four">${nums}</span> <b>${sum}/${v.pairMeetings}</b><br>${last}`;
}

const kolejekWord = (n) => n === 1 ? "kolejka" : (n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14)) ? "kolejki" : "kolejek";

/** Susza ligi przed pierwszym meczem dnia: ile kolejek / meczów bez wybranych zdarzeń i kiedy było ostatnie. */
function droughtLine(v) {
    const d = v.drought;
    if (!d) return `<span class="muted small">· brak meczów ligi w bazie</span>`;
    const part = (x, label) => {
        if (!x) return "";
        const last = x.lastEvent
            ? `ostatnio <b>${esc(x.lastEvent.htft)}</b> ${esc(x.lastEvent.home)} – ${esc(x.lastEvent.away)} ${fmtDate(x.lastEvent.kickoff)}`
            : "w bazie nigdy";
        return `<span class="drought ${x.rounds >= 5 ? "long" : ""}" title="${last.replace(/<[^>]+>/g, "")}">${label}: ${x.rounds} ${kolejekWord(x.rounds)} / ${x.matches} ${meczeWord(x.matches)}</span>
            <span class="muted small">${last}</span>`;
    };
    const ds = v.droughts || {};
    const stale = d.lastInDb && (new Date($("dDate").value) - new Date(d.lastInDb)) / 864e5 > 10
        ? ` <span class="warn small" title="Dociągnij wyniki, inaczej susza jest liczona na starych danych">⚠ baza ligi do ${fmtDate(d.lastInDb)}</span>` : "";
    const q = `${v.match.country}: ${v.match.league}`;
    return `<a class="small hist-link" href="#" onclick="openLeagueHistory(event, '${esc(q).replace(/'/g, "\\'")}')" title="Otwórz tę ligę w zakładce Historia (dni, sezony, porównanie)">📊 historia ligi</a>
        <div class="droughts">${part(ds.lamaki, "1/2+2/1")} ${part(ds.draws, "1/X+2/X")}${stale}</div>`;
}

window.openLeagueHistory = (e, q) => {
    e.preventDefault();
    e.stopPropagation();
    $("hSearch").value = q;
    showView("history");
};

function renderDaily() {
    let list = state.daily;
    if ($("dHitsOnly").checked) list = list.filter(v => v.pairHits > 0);
    const q = $("dSearch").value.trim();
    if (q) list = list.filter(v => matchesSearch(q, v.match.country, v.match.league));
    const minRounds = +$("dMinRounds").value || 0;
    const minPair = +$("dMinPair").value || 0;
    if (minRounds) list = list.filter(v => v.drought && v.drought.rounds >= minRounds);
    if (minPair) list = list.filter(v => v.tracked && (v.sinceLast == null ? v.pairMeetings : v.sinceLast) >= minPair);

    if (!list.length) {
        $("dailyResult").innerHTML = `<p class="muted">Brak meczów tego dnia${state.daily.length ? " spełniających filtry" : ""}.</p>`;
        return;
    }

    // grupowanie po lidze, ligi w kolejności pierwszego meczu
    const groups = new Map();
    list.forEach(v => {
        const k = v.match.country + ": " + v.match.league;
        if (!groups.has(k)) groups.set(k, []);
        groups.get(k).push(v);
    });

    const withHits = list.filter(v => v.pairHits > 0).length;
    const ev = [...state.events].join(", ") || "—";

    // sekcje według suszy ligi (kolejki bez zdarzenia przed pierwszym meczem dnia), od najdłuższej;
    // na dole "bez suszy" (0 kolejek), na samym końcu ligi bez danych w bazie
    const dryOf = (rows) => rows[0].tracked && rows[0].drought ? rows[0].drought.rounds : -1;
    const sections = new Map();
    [...groups.entries()]
        .sort((a, b) => dryOf(b[1]) - dryOf(a[1]) || (b[1][0].drought?.matches ?? 0) - (a[1][0].drought?.matches ?? 0))
        .forEach(([league, rows]) => {
            const k = dryOf(rows);
            if (!sections.has(k)) sections.set(k, []);
            sections.get(k).push([league, rows]);
        });
    const dryEv = $("dDryEvents").selectedOptions[0].textContent;
    const sectionTitle = (k, groupsIn) => {
        const n = groupsIn.reduce((a, [, r]) => a + r.length, 0);
        const what = `${groupsIn.length} ${groupsIn.length === 1 ? "liga" : "lig(i)"} · ${n} ${meczeWord(n)}`;
        if (k < 0) return `Ligi bez danych w bazie <span class="muted small">· ${what}</span>`;
        if (k === 0) return `Bez suszy <span class="muted small">· ostatnia kolejka miała ${esc(dryEv)} · ${what}</span>`;
        return `Susza ${k} ${kolejekWord(k)} <span class="muted small">· bez ${esc(dryEv)} · ${what}</span>`;
    };

    $("dailyResult").innerHTML = `
        <p class="muted small">${list.length} meczów · ${withHits} par miało wcześniej zdarzenie (${esc(ev)}) · kliknij mecz, aby zobaczyć historię pary${state.dailyOddsLoading ? " · kursy 1X2 ładują się…" : ""}</p>
        <table><thead><tr>
            <th>Godz.</th><th>Mecz</th><th class="num">Wynik</th><th class="num">1 · X · 2</th>
            <th class="num" title="Ile razy ta para miała zdarzenie / ile razy grała">Para</th>
            <th title="Wszystkie mecze tej pary w bazie (wszystkie ligi kraju): ile razy 1/2, 2/1, 1/X, 2/X">1/2 · 2/1 · 1/X · 2/X</th>
            <th>Ostatnie HT/FT pary</th>
            <th class="num" title="Gospodarz: zdarzenie / mecze w lidze">Gosp.</th>
            <th class="num" title="Gość: zdarzenie / mecze w lidze">Gość</th>
        </tr></thead><tbody>
        ${[...sections.entries()].map(([k, groupsIn]) => `
            <tr class="dry-section ${k >= 5 ? "long" : k <= 0 ? "none" : ""}"><td colspan="9">${sectionTitle(k, groupsIn)}</td></tr>
            ${groupsIn.map(([league, rows]) => `
            <tr class="league-head"><td colspan="9">${esc(league)} ${rows[0].tracked ? droughtLine(rows[0]) : `<span class="muted small">· liga spoza bazy</span>`}</td></tr>
            ${rows.map(v => {
                const m = v.match;
                const idx = state.daily.indexOf(v);
                return `<tr class="clickable ${v.pairHits ? "hit" : ""}" onclick="toggleDaily(${idx}, this)">
                    <td>${fmtTime(m.kickoff)}${m.status ? ` <span class="muted small">${esc(m.status)}</span>` : ""}</td>
                    <td><b>${esc(m.home)}</b> – <b>${esc(m.away)}</b></td>
                    <td class="num">${esc(m.score ?? "")}</td>
                    <td class="num odds">${m.odd1 ? `${m.odd1.toFixed(2)} · ${m.oddX.toFixed(2)} · ${m.odd2.toFixed(2)}` : ""}</td>
                    <td class="num">${v.tracked ? `<span class="pill ${v.pairHits ? "on" : ""}">${v.pairHits}/${v.pairMeetings}</span>` : ""}</td>
                    <td>${v.tracked ? fourCell(v, m.league) : ""}</td>
                    <td><span class="hist">${v.pairHistory.slice(0, 6).map(h =>
                        `<span class="${state.events.has(h.htft) ? "on" : ""}" title="${esc(h.season)} ${esc(h.home)} – ${esc(h.away)} ${esc(h.scoreHT)} / ${esc(h.scoreFT)}">${esc(h.htft)}</span>`).join("")}</span></td>
                    <td class="num">${v.tracked ? teamCell(v.homeStats) : ""}</td>
                    <td class="num">${v.tracked ? teamCell(v.awayStats) : ""}</td>
                </tr>`;
            }).join("")}`).join("")}`).join("")}
        </tbody></table>`;
}

window.toggleDaily = (idx, tr) => {
    if (tr.nextElementSibling?.classList.contains("detail")) return tr.nextElementSibling.remove();
    const v = state.daily[idx];
    const body = v.pairHistory.length
        ? `<table>${matchRows(v.pairHistory, true)}</table>`
        : `<span class="muted">Brak wcześniejszych meczów tej pary w bazie.</span>`;
    tr.insertAdjacentHTML("afterend", `<tr class="detail"><td colspan="9">${body}</td></tr>`);
};

$("dUpdate").onclick = async () => {
    const slugs = [...new Set(state.daily.filter(v => v.tracked).map(v => v.match.countrySlug))]
        .filter(s => state.countries.some(c => c.slug === s));
    if (!slugs.length) return alert("Brak meczów z Twoich lig tego dnia.");
    try {
        await api("/api/v2/scrape/start", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({countries: slugs, latestOnly: true}),
        });
        showView("scraper");
        pollStatus();
    } catch (e) {
        alert(e.message);
    }
};

// ---------------- Historia ----------------

const H_RANGES = {yesterday: ["Wczoraj", 1, 1], week: ["Ostatni tydzień", 7, 1], two: ["2 tygodnie", 14, 1], month: ["30 dni", 30, 1]};

function setHistoryRange(key) {
    const [, days, endOffset] = H_RANGES[key];
    const to = new Date();
    to.setDate(to.getDate() - endOffset);
    const from = new Date(to);
    from.setDate(from.getDate() - days + 1);
    $("hFrom").value = isoDay(from);
    $("hTo").value = isoDay(to);
    state.historyRange = key;
    loadHistory();
}

function renderHistoryRange() {
    const box = $("hRange");
    box.innerHTML = "";
    Object.entries(H_RANGES).forEach(([k, [label]]) =>
        box.appendChild(chip(label, state.historyRange === k, () => setHistoryRange(k))));
}

$("hFrom").onchange = $("hTo").onchange = () => { state.historyRange = null; loadHistory(); };
let hSearchTimer;
$("hSearch").oninput = () => { clearTimeout(hSearchTimer); hSearchTimer = setTimeout(loadHistory, 300); };
$("hFrom2").onchange = $("hTo2").onchange = () => loadHistory();
// domyślnie B = taki sam okres tuż przed A
function ensureCompareRange() {
    if (!$("hCompare").checked || $("hFrom2").value) return;
    const a1 = new Date($("hFrom").value + "T12:00:00"), a2 = new Date($("hTo").value + "T12:00:00");
    const len = Math.round((a2 - a1) / 864e5) + 1;
    const b2 = new Date(a1); b2.setDate(b2.getDate() - 1);
    const b1 = new Date(b2); b1.setDate(b1.getDate() - len + 1);
    $("hFrom2").value = isoDay(b1);
    $("hTo2").value = isoDay(b2);
}

{
    const u = new URLSearchParams(location.search);
    if (u.get("search")) $("hSearch").value = u.get("search");
    if (u.get("from")) $("hFrom").value = u.get("from");
    if (u.get("to")) $("hTo").value = u.get("to");
    if (u.get("compare")) {
        $("hCompare").checked = true;
        $("hCmpFromBox").hidden = $("hCmpToBox").hidden = false;
    }
}

$("hCompare").onchange = () => {
    const on = $("hCompare").checked;
    $("hCmpFromBox").hidden = $("hCmpToBox").hidden = !on;
    loadHistory();
};

let historySeq = 0;

async function loadHistory() {
    if (!$("hFrom").value) {
        const to = new Date();
        to.setDate(to.getDate() - 1);
        const from = new Date(to);
        from.setDate(from.getDate() - 6);
        $("hFrom").value = isoDay(from);
        $("hTo").value = isoDay(to);
        state.historyRange = "week";
    }
    renderHistoryRange();
    ensureCompareRange();
    const seq = ++historySeq;
    $("historyDays").innerHTML = `<p class="muted">Liczę…</p>`;
    $("historyLeagues").innerHTML = "";
    try {
        const p = new URLSearchParams({from: $("hFrom").value, to: $("hTo").value, onlyMine: false});
        if ($("hSearch").value.trim()) p.set("search", $("hSearch").value.trim());
        const compare = $("hCompare").checked && $("hFrom2").value && $("hTo2").value;
        const p2 = new URLSearchParams(p);
        if (compare) { p2.set("from", $("hFrom2").value); p2.set("to", $("hTo2").value); }
        const sp = new URLSearchParams();
        if ($("hSearch").value.trim()) sp.set("search", $("hSearch").value.trim());
        const [h, h2, seasons] = await Promise.all([
            api("/api/v2/history?" + p),
            compare ? api("/api/v2/history?" + p2) : null,
            api("/api/v2/history/seasons?" + sp),
        ]);
        if (seq !== historySeq) return;
        state.history = h;
        state.historyB = h2;
        state.seasons = seasons;
        renderHistory();
        renderCompare();
        renderSeasons();
    } catch (e) {
        if (seq === historySeq) $("historyDays").innerHTML = `<p class="muted">Błąd: ${esc(e.message)}</p>`;
    }
}

function fourTds(counts, matches, bold) {
    const sum2 = (counts["1/2"] || 0) + (counts["2/1"] || 0);
    const sumX = (counts["1/X"] || 0) + (counts["2/X"] || 0);
    const cell = (n) => `<td class="num">${n ? (bold ? `<b>${n}</b>` : n) : `<span class="muted">0</span>`}</td>`;
    return FOUR.map(e => cell(counts[e] || 0)).join("")
        + `<td class="num hl">${sum2} <span class="muted small">${pct(sum2, matches)}</span></td>`
        + `<td class="num hl">${sumX} <span class="muted small">${pct(sumX, matches)}</span></td>`
        + `<td class="num hl"><b>${sum2 + sumX}</b> <span class="muted small">${pct(sum2 + sumX, matches)}</span></td>`;
}

const FOUR_HEAD = `${FOUR.map(e => `<th class="num">${e}</th>`).join("")}
    <th class="num hl">1/2+2/1</th><th class="num hl">1/X+2/X</th><th class="num hl">Razem 4</th>`;

const WEEKDAYS = ["nd", "pn", "wt", "śr", "cz", "pt", "sb"];

function renderHistory() {
    const h = state.history;
    $("historyLeagues").hidden = !h.matches;
    if (!h.matches) {
        $("historyDays").innerHTML = `<p class="muted">Brak zakończonych meczów${$("hSearch").value.trim() ? ` pasujących do „${esc($("hSearch").value.trim())}”` : ""} w bazie w tym zakresie.
            Jeśli to świeże dni, kliknij „Dociągnij brakujące”.</p>`;
        return;
    }
    $("historyDays").innerHTML = `
        <h2>Dzień po dniu</h2>
        <p class="muted small">${h.matches} meczów w bazie · kliknij dzień, aby zobaczyć mecze ze zdarzeniami · liczone z wyników zapisanych w bazie</p>
        <table class="h-days"><thead><tr><th>Dzień</th><th class="num">Mecze</th>${FOUR_HEAD}</tr></thead><tbody>
        ${h.days.map((d, i) => `<tr class="${d.events.length ? "clickable" : "zero"}" ${d.events.length ? `onclick="toggleHistoryDay(${i}, this)"` : ""}>
            <td>${d.date} <span class="muted small">${WEEKDAYS[new Date(d.date + "T12:00:00").getDay()]}</span></td>
            <td class="num">${d.matches}</td>${fourTds(d.counts, d.matches, true)}</tr>`).join("")}
        <tr><td><b>Razem</b></td><td class="num"><b>${h.matches}</b></td>${fourTds(h.counts, h.matches, true)}</tr>
        </tbody></table>`;

    // podpowiedzi do wyszukiwarki - ligi z bieżącego zakresu (tylko bez aktywnego wyszukiwania, żeby lista nie malała)
    if (!$("hSearch").value.trim()) {
        $("hLeagueList").innerHTML = h.leagues.map(l => `<option value="${esc(l.league)}">`).join("");
    }

    const searching = !!$("hSearch").value.trim();
    const leagues = h.leagues.filter(l => searching || l.events.length);
    $("historyLeagues").hidden = !leagues.length;
    $("historyLeagues").innerHTML = leagues.length ? `
        <h2>Ligi w tym zakresie</h2>
        <p class="muted small">${searching ? "ligi pasujące do wyszukiwania" : "tylko ligi, w których padło któreś z 4 zdarzeń"} · kliknij ligę, aby zobaczyć drużyny i mecze</p>
        <table><thead><tr><th>Liga</th><th class="num">Mecze</th>${FOUR_HEAD}</tr></thead><tbody>
        ${leagues.map(l => `<tr class="${l.events.length ? "clickable" : "zero"}" ${l.events.length ? `onclick="toggleHistoryLeague(${h.leagues.indexOf(l)}, this)"` : ""}>
            <td>${esc(l.league)}</td><td class="num">${l.matches}</td>${fourTds(l.counts, l.matches, false)}</tr>`).join("")}
        </tbody></table>` : "";
}

/** Tabela meczów ze zdarzeniami; showLeague=false w obrębie jednej ligi (wtedy kolumna z datą). */
function eventMatchesTable(events, showLeague) {
    const rows = events.map(({country, match: m}) => `<tr>
        <td>${showLeague ? fmtTime(m.kickoff) : `${fmtDate(m.kickoff)} <span class="muted small">${fmtTime(m.kickoff)}</span>`}</td>
        ${showLeague ? `<td>${esc(country)}: ${esc(m.league)}</td>` : ""}
        <td><b>${esc(m.home)}</b> – <b>${esc(m.away)}</b></td>
        <td class="num">${esc(m.scoreHT ?? "")}</td><td class="num">${esc(m.scoreFT ?? "")}</td>
        <td class="event">${esc(m.htft)}</td>
        <td class="muted small">${esc(eventWho(m))}</td></tr>`).join("");
    return `<table><thead><tr><th>${showLeague ? "Godz." : "Data"}</th>${showLeague ? "<th>Liga</th>" : ""}<th>Mecz</th>
        <th class="num">HT</th><th class="num">FT</th><th>HT/FT</th><th>Kto</th></tr></thead><tbody>${rows}</tbody></table>`;
}

/** Krótki opis zdarzenia: kto prowadził do przerwy i co się stało. */
function eventWho(m) {
    const [ht, ft] = m.htft.split("/");
    const leader = ht === "1" ? m.home : m.away;
    if (ft === "X") return `${leader} prowadził(a) do przerwy, remis`;
    return `${leader} prowadził(a), wygrał(a) ${ft === "1" ? m.home : m.away}`;
}

window.toggleHistoryLeague = (i, tr) => {
    if (tr.nextElementSibling?.classList.contains("detail")) return tr.nextElementSibling.remove();
    const l = state.history.leagues[i];
    // drużyny, które brały udział w zdarzeniach (gospodarz lub gość), od najczęstszych
    const teams = new Map();
    l.events.forEach(({match: m}) => [m.home, m.away].forEach(t => teams.set(t, (teams.get(t) || 0) + 1)));
    const teamList = [...teams.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
        .map(([t, n]) => `<span class="pill ${n > 1 ? "on" : ""}">${esc(t)}${n > 1 ? ` ×${n}` : ""}</span>`).join(" ");
    tr.insertAdjacentHTML("afterend", `<tr class="detail"><td colspan="9">
        <p class="small"><b>Drużyny:</b> ${teamList}</p>
        ${eventMatchesTable(l.events, false)}</td></tr>`);
};

const sum4 = (c) => FOUR.reduce((a, e) => a + (c[e] || 0), 0);

/** Porównanie zakresu A z zakresem B: razem i per liga (ligi z obu zakresów). */
function renderCompare() {
    const box = $("historyCompare");
    const a = state.history, b = state.historyB;
    box.hidden = !b;
    if (!b) return;
    const label = (h) => h.from === h.to ? h.from : `${h.from} – ${h.to}`;
    const byLeague = new Map();
    a.leagues.forEach(l => byLeague.set(l.league, {a: l}));
    b.leagues.forEach(l => byLeague.set(l.league, {...(byLeague.get(l.league) || {}), b: l}));
    const empty = {matches: 0, counts: {}};
    const side = (x) => FOUR.map(e => `<td class="num">${x.counts[e] || `<span class="muted">0</span>`}</td>`).join("")
        + `<td class="num hl"><b>${sum4(x.counts)}</b> <span class="muted small">${pct(sum4(x.counts), x.matches)}</span></td>`;
    const delta = (x, y) => {
        const d = sum4(x.counts) - sum4(y.counts);
        return `<td class="num"><b class="${d > 0 ? "up" : d < 0 ? "down" : ""}">${d > 0 ? "+" : ""}${d}</b></td>`;
    };
    const row = (name, x, y, cls = "") => `<tr class="${cls}"><td>${name}</td>
        <td class="num">${x.matches}</td>${side(x)}<td class="sep"></td><td class="num">${y.matches}</td>${side(y)}${delta(x, y)}</tr>`;
    const leagues = [...byLeague.entries()]
        .filter(([, v]) => sum4((v.a || empty).counts) + sum4((v.b || empty).counts) > 0)
        .sort((x, y) => (sum4((y[1].a || empty).counts) + sum4((y[1].b || empty).counts))
            - (sum4((x[1].a || empty).counts) + sum4((x[1].b || empty).counts)) || x[0].localeCompare(y[0]));
    const head4 = `<th class="num">Mecze</th>${FOUR.map(e => `<th class="num">${e}</th>`).join("")}<th class="num hl">Razem 4</th>`;
    box.innerHTML = `<h2>Porównanie</h2>
        <p class="muted small">A: <b>${label(a)}</b> vs B: <b>${label(b)}</b>${$("hSearch").value.trim() ? ` · ligi: „${esc($("hSearch").value.trim())}”` : ""} · różnica = A − B (razem 4 zdarzenia)</p>
        <table class="compare"><thead>
            <tr><th></th><th colspan="6">A: ${label(a)}</th><th class="sep"></th><th colspan="6">B: ${label(b)}</th><th></th></tr>
            <tr><th>Liga</th>${head4}<th class="sep"></th>${head4}<th class="num">A − B</th></tr>
        </thead><tbody>
            ${row("<b>Razem</b>", a, b, "total")}
            ${leagues.map(([name, v]) => row(esc(name), v.a || empty, v.b || empty)).join("")}
        </tbody></table>`;
}

/** Sezony: wiersz = liga, kolumny = sezony (razem 4 zdarzenia / mecze); kliknięcie rozwija rozbicie na zdarzenia. */
function renderSeasons() {
    const list = state.seasons || [];
    const box = $("historySeasons");
    if (!list.length) { box.innerHTML = ""; return; }
    const years = [...new Set(list.flatMap(l => l.seasons.map(s => s.year)))].sort((a, b) => b - a).slice(0, 6);
    const searching = !!$("hSearch").value.trim();
    box.innerHTML = `<h2>Sezony</h2>
        <p class="muted small">${list.length} lig${searching ? "" : " · wpisz ligę/kraj w „Szukaj ligi”, żeby zawęzić"} ·
            w komórce: 1/2+2/1+1/X+2/X / mecze i % · <span class="cur-tag">trwa</span> = bieżący sezon (dotychczas) · kliknij ligę, aby rozbić na zdarzenia</p>
        <table class="seasons"><thead><tr><th>Liga</th>${years.map(y => `<th class="num">${y}</th>`).join("")}</tr></thead><tbody>
        ${list.map((l, i) => `<tr class="clickable" onclick="toggleSeasons(${i}, this)"><td>${esc(l.league)}</td>
            ${years.map(y => {
                const s = l.seasons.find(x => x.year === y);
                if (!s) return `<td class="num muted">–</td>`;
                return `<td class="num ${s.current ? "cur" : ""}" title="${esc(s.label)}: ${FOUR.map(e => `${e} ${s.counts[e]}`).join(", ")}">
                    <b>${sum4(s.counts)}</b>/${s.matches} <span class="muted small">${pct(sum4(s.counts), s.matches)}</span></td>`;
            }).join("")}</tr>`).join("")}
        </tbody></table>`;
}

window.toggleSeasons = (i, tr) => {
    if (tr.nextElementSibling?.classList.contains("detail")) return tr.nextElementSibling.remove();
    const l = state.seasons[i];
    const rows = l.seasons.map(s => `<tr class="${s.current ? "cur-row" : ""}">
        <td>${esc(s.label)} ${s.current ? `<span class="cur-tag">trwa · do ${fmtDate(s.lastMatch)}</span>` : ""}</td>
        <td class="num">${s.matches}</td>${fourTds(s.counts, s.matches, true)}</tr>`).join("");
    tr.insertAdjacentHTML("afterend", `<tr class="detail"><td colspan="7"><table>
        <thead><tr><th>Sezon</th><th class="num">Mecze</th>${FOUR_HEAD}</tr></thead><tbody>${rows}</tbody></table></td></tr>`);
};

window.toggleHistoryDay = (i, tr) => {
    if (tr.nextElementSibling?.classList.contains("detail")) return tr.nextElementSibling.remove();
    const d = state.history.days[i];
    tr.insertAdjacentHTML("afterend", `<tr class="detail"><td colspan="9">${eventMatchesTable(d.events, true)}</td></tr>`);
};

async function waitForRecorder() {
    for (let i = 0; i < 120; i++) {
        await new Promise(res => setTimeout(res, 5000));
        const {pending} = await api("/api/v2/history/pending");
        if (pending) $("hInfo").textContent = `Zapisuję w tle - zostało lig: ${pending}`;
        if (!pending || i % 6 === 5) loadHistory();
        if (!pending) {
            $("hInfo").textContent = "Gotowe - baza aktualna.";
            return;
        }
    }
}

$("hRefresh").onclick = async () => {
    const btn = $("hRefresh");
    btn.disabled = true;
    $("hInfo").textContent = "Pobieram mecze z API dzień po dniu…";
    try {
        const p = new URLSearchParams({from: $("hFrom").value, to: $("hTo").value});
        const r = await api("/api/v2/history/refresh?" + p, {method: "POST"});
        $("hInfo").textContent = `Sprawdzono ${r.days} dni, zapisuję brakujące mecze w tle…`;
        await waitForRecorder();
    } catch (e) {
        $("hInfo").textContent = "Błąd: " + e.message;
    } finally {
        btn.disabled = false;
    }
};

// ---------------- Lamaki ----------------

// ustawienia i wpisane kursy zostają w przeglądarce (to tylko wygoda - bez nich widok działa na danych z API)
const store = {
    get(key, def) {
        try {
            return JSON.parse(localStorage.getItem(key)) ?? def;
        } catch {
            return def;
        }
    },
    set(key, value) {
        try {
            localStorage.setItem(key, JSON.stringify(value));
        } catch {
        }
    },
};

const money = (v) => v == null ? "–" : v.toFixed(2) + " zł";

const RANGES = {today: "Dziś", tomorrow: "Jutro", after: "Pojutrze", week: "Najbliższe dni"};
state.strategyRange = RANGES[new URLSearchParams(location.search).get("range")] ? new URLSearchParams(location.search).get("range") : "today";

function renderRange() {
    const box = $("sRange");
    box.innerHTML = "";
    Object.entries(RANGES).forEach(([k, label]) => box.appendChild(chip(label, state.strategyRange === k, () => {
        state.strategyRange = k;
        renderRange();
        renderStrategy();
    })));
}

/** Mecze do zagrania (jeszcze nierozpoczęte) z wybranego zakresu dni. */
function playable(list) {
    const offset = {today: 0, tomorrow: 1, after: 2}[state.strategyRange];
    const d = new Date();
    d.setDate(d.getDate() + (offset ?? 0));
    const day = offset == null ? null : isoDay(d);
    return list.filter(c => (!c.status || c.status === "NS" || c.status === "TBD") && (!day || fmtDate(c.kickoff) === day));
}

let strategySeq = 0;

async function loadStrategy() {
    const seq = ++strategySeq;
    $("strategyUpcoming").innerHTML = `<p class="muted">Szukam meczów i kursów…</p>`;
    const p = new URLSearchParams();
    if ($("sMin").value) p.set("minCommon", $("sMin").value);
    if ($("sFrom").value) p.set("historyFrom", $("sFrom").value);
    if ($("sAhead").value) p.set("daysAhead", $("sAhead").value);
    try {
        const v = await api("/api/v2/strategy?" + p);
        if (seq !== strategySeq) return;
        state.strategy = v;
        if (!$("sFrom").value) $("sFrom").value = v.settings.historyFrom;
        if (!$("sTarget").value) $("sTarget").value = store.get("strategy.target", v.target);
        if (!$("sCap").value) $("sCap").value = store.get("strategy.cap", v.cap);
        renderStrategy();
        fillGoals(false);
    } catch (e) {
        if (seq === strategySeq) $("strategyUpcoming").innerHTML = `<p class="muted">Błąd: ${esc(e.message)}</p>`;
    }
}

["sMin", "sFrom", "sAhead"].forEach(id => $(id).onchange = loadStrategy);
$("sReload").onclick = loadStrategy;
$("sTarget").onchange = () => { store.set("strategy.target", +$("sTarget").value); renderStrategy(); };
$("sCap").onchange = () => { store.set("strategy.cap", +$("sCap").value); renderStrategy(); };

const sunkOf = (league) => store.get("strategy.sunk." + league, 0);

/** Kursy meczu: wpisane ręcznie (np. z Superbetu/STS) mają pierwszeństwo przed najlepszymi z API. */
function oddsOf(c) {
    const typed = store.get("strategy.odds." + c.externalId, {});
    return Object.fromEntries(state.strategy.settings.events.map(e => [e, typed[e] ?? c.odds[e]?.odd ?? null]));
}

/**
 * Stawki jak w arkuszu: łącznie (cel + włożone) / (podatek × kurs łączny − 1), rozdzielone tak,
 * żeby każde z trafień dało tę samą wygraną. Kurs łączny = 1 / Σ(1/kurs).
 */
function stakes(odds, sunk) {
    const values = Object.values(odds);
    if (!values.length || values.some(o => !(o > 1))) return null;
    const comb = 1 / values.reduce((a, o) => a + 1 / o, 0);
    const factor = state.strategy.tax * comb - 1;
    if (factor <= 0) return {comb, total: null, per: {}};
    const total = (+$("sTarget").value + sunk) / factor;
    return {comb, total, per: Object.fromEntries(Object.entries(odds).map(([e, o]) => [e, total * comb / o]))};
}

function stakeCells(c) {
    const s = stakes(oddsOf(c), sunkOf(c.league));
    if (!s) return `<td class="num muted">–</td><td class="muted small">wpisz kursy</td>`;
    if (s.total == null) return `<td class="num">${s.comb.toFixed(2)}</td><td class="warn small">kurs za niski</td>`;
    const after = sunkOf(c.league) + s.total;
    const per = Object.entries(s.per).map(([e, v]) => `${esc(e)}: <span class="stake">${money(v)}</span>`).join("<br>");
    return `<td class="num">${s.comb.toFixed(2)}</td>
        <td class="stakes">${per}<br><span class="muted small">razem ${money(s.total)}</span>
        ${after > +$("sCap").value ? `<br><span class="warn small">po tym zakładzie seria przekroczy limit (${money(after)})</span>` : ""}</td>`;
}

const goalsSum = (score) => score ? score.split("-").reduce((a, x) => a + +x, 0) : null;

/**
 * Podwójny lamak: drużyna, która wygrała mecz, strzeliła pierwszego gola, a do przerwy przegrywała.
 * null = brak kolejności goli (jeszcze nie pobrana albo niezgodna z wynikiem).
 */
function isDouble(h) {
    const winner = {"2/1": "H", "1/2": "A"}[h.htft];
    if (!winner || !h.goalOrder || h.goalOrder === "?") return null;
    return h.goalOrder[0] === winner;
}

/** Wspólne zdarzenia pary + podsumowanie: ile z nich miało over 1.5 do przerwy i ile było podwójnych. */
function patternsCell(hits) {
    const n = hits.length;
    const ht15 = hits.filter(h => goalsSum(h.scoreHT) >= 2).length;
    const known = hits.filter(h => isDouble(h) !== null);
    const doubles = known.filter(isDouble).length;
    const ratio = (k, of) => `<b class="${of && k === of ? "ok" : ""}">${k}/${of}</b>`;
    return `<span class="hist">${hits.map(h => {
            const tags = (goalsSum(h.scoreHT) >= 2 ? " o1.5" : "") + (isDouble(h) ? " 2×" : "");
            return `<span class="on" title="${esc(h.season)} ${esc(h.home)} – ${esc(h.away)} · HT ${esc(h.scoreHT)} · FT ${esc(h.scoreFT)}${h.goalOrder && h.goalOrder !== "?" ? " · gole: " + esc(h.goalOrder) : ""}">${esc(h.htft)} ${fmtDate(h.kickoff).slice(2, 7)}${tags}</span>`;
        }).join("")}</span>
        <div class="small muted">HT over 1.5: ${ratio(ht15, n)} · podwójny: ${ratio(doubles, known.length)}${known.length < n ? ` <span title="brak kolejności goli">(+${n - known.length} bez danych)</span>` : ""}</div>`;
}

function renderStrategy() {
    const v = state.strategy;
    if (!v) return;
    const ev = v.settings.events;

    // ---- mecze do zagrania, z podziałem na ligi ----
    const up = playable(v.upcoming);
    const groups = new Map();
    up.forEach(c => groups.has(c.league) ? groups.get(c.league).push(c) : groups.set(c.league, [c]));
    const cols = 6 + ev.length;
    const when = {today: "dziś", tomorrow: "jutro", after: "pojutrze", week: `w najbliższych ${v.settings.daysAhead} dniach`}[state.strategyRange];
    const showDate = state.strategyRange === "week";

    $("strategyUpcoming").innerHTML = `
        <h2>Mecze do zagrania ${esc(when)}${up.length ? ` · ${up.length} w ${groups.size} ${groups.size === 1 ? "lidze" : "ligach"}` : ""}</h2>
        <p class="muted small">Nierozpoczęte mecze par, które od ${esc(v.settings.historyFrom)} miały co najmniej ${v.settings.minCommon}× ${esc(ev.join(" / "))}
            · kursy podpowiedziane z API (najwyższe u bukmacherów z API) - wpisz swoje ze Superbetu/STS, zostaną zapamiętane
            · stawki liczone dla celu ${money(+$("sTarget").value)} i kwoty już włożonej w serię danej ligi</p>
        ${v.warning ? `<p class="warn small">${esc(v.warning)}</p>` : ""}
        ${up.length ? `<table><thead><tr>
            <th>${showDate ? "Data" : "Godz."}</th><th>Mecz</th><th class="num" title="Wspólne zdarzenia pary / wszystkie mecze pary od daty">Wspólne</th>
            <th>Wspólne zdarzenia</th>${ev.map(e => `<th class="num">${esc(e)}</th>`).join("")}
            <th class="num">Kurs łączny</th><th>Stawki</th></tr></thead><tbody>
            ${[...groups.entries()].map(([league, rows]) => {
                const l = v.leagues.find(x => x.name === league);
                return `<tr class="league-head"><td colspan="${cols}">${esc(league)}
                    <span class="muted small">· seria: ${l ? l.sinceLastHit : "?"} meczów bez trafienia · włożone w serię: ${money(sunkOf(league))}</span></td></tr>
                ${rows.map(c => `<tr data-i="${up.indexOf(c)}">
                    <td>${showDate ? fmtDate(c.kickoff) + " " : ""}${fmtTime(c.kickoff)}</td>
                    <td><b>${esc(c.home)}</b> – <b>${esc(c.away)}</b></td>
                    <td class="num"><span class="pill on">${c.common}/${c.meetings}</span></td>
                    <td>${patternsCell(c.pairHits)}</td>
                    ${ev.map(e => `<td class="num"><input class="odd" type="number" step="0.5" min="1" data-e="${esc(e)}"
                        value="${oddsOf(c)[e] ?? ""}" title="${c.odds[e] ? `API: ${c.odds[e].odd} (${esc(c.odds[e].bookmaker)})` : "brak kursu w API"}"></td>`).join("")}
                    ${stakeCells(c)}
                </tr>`).join("")}`;
            }).join("")}
            </tbody></table>` : `<p class="muted">Brak meczów do zagrania ${esc(when)}.${state.strategyRange !== "week" ? " Sprawdź „Najbliższe dni”." : ""}</p>`}`;

    $("strategyUpcoming").querySelectorAll("input.odd").forEach(input => input.oninput = () => {
        const tr = input.closest("tr");
        const c = up[+tr.dataset.i];
        const typed = store.get("strategy.odds." + c.externalId, {});
        input.value ? typed[input.dataset.e] = +input.value : delete typed[input.dataset.e];
        store.set("strategy.odds." + c.externalId, typed);
        const cells = tr.querySelectorAll("td");
        cells[cells.length - 1].remove();
        cells[cells.length - 2].remove();
        tr.insertAdjacentHTML("beforeend", stakeCells(c));
    });

    // ---- ligi ----
    $("strategyLeagues").innerHTML = `
        <h2>Ligi</h2>
        <p class="muted small">„Kwalifikujące się” = mecze par, które miały już wcześniej co najmniej ${v.settings.minCommon} wspólne zdarzenia
            · „Seria” = ile takich meczów minęło od ostatniego trafienia · „Włożone w serię” wpisujesz sam (0 po trafieniu)</p>
        <table><thead><tr><th>Liga</th><th class="num">Mecze od ${esc(v.settings.historyFrom.slice(0, 4))}</th><th class="num">Zdarzenie w lidze</th>
            <th class="num">Kwalifikujące się</th><th class="num">Trafione</th><th class="num">Seria</th><th>Ostatnie trafienie</th>
            <th class="num">Włożone w serię</th></tr></thead><tbody>
            ${v.leagues.map(l => `<tr>
                <td>${esc(l.name)}</td><td class="num">${l.matches}</td>
                <td class="num">${pct(l.eventHits, l.matches)}</td>
                <td class="num">${l.qualifying}</td>
                <td class="num">${l.qualifyingHits} <span class="muted small">(${pct(l.qualifyingHits, l.qualifying)})</span></td>
                <td class="num"><b>${l.sinceLastHit}</b></td>
                <td>${l.lastHit ? `${fmtDate(l.lastHit.kickoff)} ${esc(l.lastHit.home)} – ${esc(l.lastHit.away)} <span class="event">${esc(l.lastHit.htft)}</span>` : `<span class="muted">–</span>`}</td>
                <td class="num"><input class="sunk" type="number" min="0" step="1" data-league="${esc(l.name)}" value="${sunkOf(l.name) || ""}" placeholder="0"></td>
            </tr>`).join("")}
        </tbody></table>`;

    $("strategyLeagues").querySelectorAll("input.sunk").forEach(input => input.onchange = () => {
        store.set("strategy.sunk." + input.dataset.league, +input.value || 0);
        renderStrategy();
    });

    // ---- rozstrzygnięte ----
    const rec = v.recent;
    $("strategyRecent").innerHTML = `
        <h2>Rozstrzygnięte w ostatnich ${v.settings.daysBack} dniach</h2>
        ${rec.length ? `<table><thead><tr><th>Data</th><th>Liga</th><th>Mecz</th><th class="num">Wspólne przed</th>
            <th class="num">HT</th><th class="num">FT</th><th>HT/FT</th><th></th></tr></thead><tbody>
            ${rec.map(r => `<tr>
                <td>${fmtDate(r.match.kickoff)}</td><td>${esc(r.league)}</td>
                <td>${esc(r.match.home)} – ${esc(r.match.away)}</td><td class="num">${r.commonBefore}</td>
                <td class="num">${esc(r.match.scoreHT ?? "")}</td><td class="num">${esc(r.match.scoreFT ?? "")}</td>
                <td class="${r.hit ? "event" : ""}">${esc(r.match.htft)}</td>
                <td>${r.hit ? `<span class="ok">trafiony</span>` : `<span class="muted">nie</span>`}</td>
            </tr>`).join("")}
            </tbody></table>` : `<p class="muted">Brak rozstrzygniętych meczów spełniających warunek. Jeśli wczoraj grały Twoje ligi, kliknij „Pobierz nowe wyniki”.</p>`}`;
}

let strategyPoll;
let strategyJobRunning = false;
let goalsTried = false;

/** Dociąga kolejność goli w lamakach (podwójne lamaki); force = także gdy na liście niczego nie brakuje. */
async function fillGoals(force) {
    const v = state.strategy;
    const missing = v && v.upcoming.some(c => c.pairHits.some(h => h.goalOrder == null));
    if (!force && (goalsTried || !missing)) return;
    goalsTried = true;
    const info = $("sJob").textContent;
    $("sJob").textContent = "Uzupełniam kolejność goli w lamakach (podwójne lamaki)…";
    try {
        const r = await api("/api/v2/strategy/goals?historyFrom=" + ($("sFrom").value || ""), {method: "POST"});
        $("sJob").textContent = info + (r.filled ? ` · kolejność goli: +${r.filled}` : "");
    } catch (e) {
        $("sJob").textContent = "Nie udało się pobrać kolejności goli: " + e.message;
    }
    loadStrategy();
}

async function pollStrategyJob() {
    clearTimeout(strategyPoll);
    const s = await api("/api/v2/scrape/status");
    $("sUpdate").disabled = s.running;
    if (s.running) {
        $("sJob").textContent = `Pobieram wyniki… sezony ${s.seasonsDone}/${s.seasonsTotal} · nowe mecze: ${s.matchesSaved}`;
        strategyPoll = setTimeout(pollStrategyJob, 2000);
    } else if (s.finishedAt) {
        $("sJob").textContent = `Ostatnie pobieranie: ${fmtDate(s.finishedAt)} ${fmtTime(s.finishedAt)} · nowe mecze: ${s.matchesSaved}`
            + (s.errors ? ` · błędy: ${s.errors} (szczegóły w zakładce Scraper)` : "");
        if (strategyJobRunning) fillGoals(true);
    }
    strategyJobRunning = s.running;
}

$("sUpdate").onclick = async () => {
    try {
        await api("/api/v2/strategy/update", {method: "POST"});
    } catch (e) {
        return alert(e.message);
    }
    strategyJobRunning = true;
    pollStrategyJob();
};

// ---------------- Scraper ----------------

async function loadScraperConfig() {
    const cfg = await api("/api/v2/scrape/config");
    state.countries = cfg.countries;
    state.selectedLeagues = new Set(store.get("scraper.leagues", []));
    renderCountries();
}

// kraje bez listy lig (BetExplorer z auto-wyborem) zaznacza się w całości, pozostałe - po lidze
const hasLeagues = (c) => c.leagues.length > 0;

function saveSelection() {
    store.set("scraper.leagues", [...state.selectedLeagues]);
    const n = state.selectedLeagues.size + state.selectedCountries.size;
    $("selCount").textContent = n ? `zaznaczono: ${state.selectedLeagues.size} lig${state.selectedCountries.size ? ` + ${state.selectedCountries.size} krajów` : ""}` : "";
}

function renderCountries() {
    const q = $("cSearch").value.trim().toLowerCase();
    const visible = state.countries.filter(c => !q || c.name.toLowerCase().includes(q) || c.slug.includes(q)
        || c.leagues.some(l => l.name.toLowerCase().includes(q)));
    $("countryList").innerHTML = visible.map(c => hasLeagues(c)
        ? `<div class="country-card">
            <button class="country-name" data-slug="${esc(c.slug)}" title="Zaznacz / odznacz wszystkie ligi">${esc(c.name)}
                <span class="muted small">od ${c.fromYear ?? "?"}</span></button>
            ${c.leagues.map(l => `<label class="check"><input type="checkbox" data-ref="${esc(l.ref)}" ${state.selectedLeagues.has(l.ref) ? "checked" : ""}>
                ${l.tier ? `<span class="tier">${l.tier}</span>` : ""}${esc(l.name)}</label>`).join("")}
          </div>`
        : `<div class="country-card"><label class="check"><input type="checkbox" data-slug="${esc(c.slug)}" ${state.selectedCountries.has(c.slug) ? "checked" : ""}>
            <b>${esc(c.name)}</b> <span class="muted small">od ${c.fromYear ?? "?"} · ligi wybierane automatycznie</span></label></div>`).join("")
        || `<p class="muted small">Brak krajów pasujących do „${esc(q)}”.</p>`;

    $("countryList").querySelectorAll("input[data-ref]").forEach(i => i.onchange = () => {
        i.checked ? state.selectedLeagues.add(i.dataset.ref) : state.selectedLeagues.delete(i.dataset.ref);
        saveSelection();
    });
    $("countryList").querySelectorAll("input[data-slug]").forEach(i => i.onchange = () => {
        i.checked ? state.selectedCountries.add(i.dataset.slug) : state.selectedCountries.delete(i.dataset.slug);
        saveSelection();
    });
    $("countryList").querySelectorAll("button.country-name").forEach(b => b.onclick = () => {
        const refs = state.countries.find(c => c.slug === b.dataset.slug).leagues.map(l => l.ref);
        const all = refs.every(r => state.selectedLeagues.has(r));
        refs.forEach(r => all ? state.selectedLeagues.delete(r) : state.selectedLeagues.add(r));
        renderCountries();
    });
    saveSelection();
}

function selectTier(tier) {
    state.countries.forEach(c => c.leagues.filter(l => l.tier === tier).forEach(l => state.selectedLeagues.add(l.ref)));
    renderCountries();
}

$("cSearch").oninput = renderCountries;
$("btnTier1").onclick = () => selectTier(1);
$("btnTier2").onclick = () => selectTier(2);
$("btnNone").onclick = () => { state.selectedLeagues.clear(); state.selectedCountries.clear(); renderCountries(); };

/** Kraje, które trzeba odpytać dla bieżącego zaznaczenia. */
function selectedSlugs() {
    const slugs = new Set(state.selectedCountries);
    state.countries.forEach(c => { if (c.leagues.some(l => state.selectedLeagues.has(l.ref))) slugs.add(c.slug); });
    return [...slugs];
}

$("btnPlan").onclick = async () => {
    const slugs = selectedSlugs();
    $("planPanel").hidden = false;
    $("planBody").innerHTML = `<p class="muted">Sprawdzam dostępne sezony…</p>`;
    const plans = slugs.length
        ? (await Promise.all(slugs.map(s => api("/api/v2/scrape/plan?country=" + encodeURIComponent(s))))).flat()
        : await api("/api/v2/scrape/plan");
    const wanted = (s) => !state.selectedLeagues.size || state.selectedCountries.has(s.countrySlug) || state.selectedLeagues.has(s.leagueRef);
    $("planBody").innerHTML = `<table><thead><tr><th>Kraj</th><th>Ligi</th><th>Sezony</th><th>Inne dostępne ligi</th></tr></thead><tbody>
        ${plans.map(p => {
            const byLeague = {};
            p.seasons.filter(wanted).forEach(s => (byLeague[s.league] ??= []).push(s.seasonLabel));
            return `<tr><td>${esc(p.country)}</td>
                <td>${p.error ? `<span style="color:var(--danger)">${esc(p.error)}</span>` : esc(p.selectedLeagues.join(", "))}</td>
                <td style="white-space:normal">${Object.entries(byLeague).map(([l, s]) => `<b>${esc(l)}</b>: ${esc(s.join(", "))}`).join("<br>")}</td>
                <td class="muted small" style="white-space:normal">${esc(p.availableLeagues.filter(l => !p.selectedLeagues.includes(l)).slice(0, 8).join(", "))}</td></tr>`;
        }).join("")}</tbody></table>`;
};

async function startScrape(body) {
    try {
        await api("/api/v2/scrape/start", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({...body, latestOnly: $("latestOnly").checked}),
        });
    } catch (e) {
        alert(e.message);
    }
    pollStatus();
}

$("btnStart").onclick = () => {
    if (!state.selectedLeagues.size && !state.selectedCountries.size) {
        return alert("Zaznacz co najmniej jedną ligę albo użyj „Pobierz wszystko”.");
    }
    startScrape({countries: [...state.selectedCountries], leagues: [...state.selectedLeagues]});
};

$("btnStartAll").onclick = () => {
    const leagues = state.countries.reduce((a, c) => a + Math.max(c.leagues.length, 1), 0);
    const what = $("latestOnly").checked ? "bieżące sezony" : "wszystkie sezony";
    if (confirm(`Pobrać ${what} wszystkich lig (${leagues}) ze wszystkich krajów (${state.countries.length})?`)) {
        startScrape({countries: [], leagues: []});
    }
};

$("btnStop").onclick = async () => { await api("/api/v2/scrape/cancel", {method: "POST"}); pollStatus(); };

let pollTimer;
let wasRunning = false;

$("raBtn").onclick = async () => {
    try {
        await api("/api/v2/scrape/start", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({countries: [], leagues: [], latestOnly: true}),
        });
    } catch (e) {
        alert(e.message);
    }
    pollStatus();
};

/** Po zakończeniu pobierania odświeża aktualną zakładkę, żeby od razu było widać nowe wyniki. */
function reloadCurrentView() {
    if (state.view === "daily") loadDaily();
    if (state.view === "history") loadHistory();
    if (state.view === "strategy") loadStrategy();
    if (state.view === "analysis") refresh();
}

async function pollStatus() {
    clearTimeout(pollTimer);
    const s = await api("/api/v2/scrape/status");
    $("jobState").textContent = s.running ? "w trakcie" : (s.finishedAt ? "zakończony" : "bezczynny");
    $("jobState").classList.toggle("running", s.running);
    $("jobBar").style.width = s.seasonsTotal ? (100 * s.seasonsDone / s.seasonsTotal) + "%" : "0";
    $("jobInfo").textContent = s.startedAt
        ? `Sezony: ${s.seasonsDone}/${s.seasonsTotal} · nowe mecze: ${s.matchesSaved} · błędy: ${s.errors}` + (s.current ? ` · teraz: ${s.current}` : "")
        : "";
    $("jobLog").textContent = s.log.join("\n");
    $("btnStart").disabled = s.running;
    $("btnStartAll").disabled = s.running;
    $("btnStop").disabled = !s.running;
    $("raBtn").disabled = s.running;
    $("raInfo").textContent = s.running
        ? `pobieram: ${s.seasonsDone}/${s.seasonsTotal || "?"} sezonów · nowe mecze: ${s.matchesSaved}`
        : (wasRunning ? `gotowe · nowe mecze: ${s.matchesSaved}${s.errors ? ` · błędy: ${s.errors}` : ""}` : "");
    if (wasRunning && !s.running) reloadCurrentView();
    wasRunning = s.running;
    if (s.running) pollTimer = setTimeout(pollStatus, 2000);
}

// ---------------- start ----------------

renderEvents();
renderRange();
loadScraperConfig();
showView(new URLSearchParams(location.search).get("view") || "daily");
pollStatus();
