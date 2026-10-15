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
    daily: [],
};

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;"}[c]));
const pct = (a, b) => b ? (100 * a / b).toFixed(1) + "%" : "–";
const fmtDate = (d) => d ? d.slice(0, 10) : "";
const fmtTime = (d) => d ? d.slice(11, 16) : "";
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
$("dTracked").onchange = loadDaily;
$("dHitsOnly").onchange = renderDaily;
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
    $("dailyResult").innerHTML = `<p class="muted">Pobieram mecze z BetExplorera…</p>`;
    const p = new URLSearchParams({date: $("dDate").value, onlyTracked: $("dTracked").checked});
    state.events.forEach(e => p.append("events", e));
    try {
        const list = await api("/api/v2/daily?" + p);
        if (seq !== dailySeq) return;
        state.daily = list;
        renderDaily();
    } catch (e) {
        if (seq === dailySeq) $("dailyResult").innerHTML = `<p class="muted">Błąd: ${esc(e.message)}</p>`;
    }
}

function teamCell(stats) {
    if (!stats || !stats.matches) return `<span class="muted small">–</span>`;
    return `<span title="${stats.hits} z ${stats.matches} meczów w lidze">${stats.hits}/${stats.matches} <span class="muted small">(${pct(stats.hits, stats.matches)})</span></span>`;
}

function renderDaily() {
    let list = state.daily;
    if ($("dHitsOnly").checked) list = list.filter(v => v.pairHits > 0);

    if (!list.length) {
        $("dailyResult").innerHTML = `<p class="muted">Brak meczów${$("dTracked").checked ? " w Twoich ligach" : ""} tego dnia.</p>`;
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

    $("dailyResult").innerHTML = `
        <p class="muted small">${list.length} meczów · ${withHits} par miało wcześniej zdarzenie (${esc(ev)}) · kliknij mecz, aby zobaczyć historię pary</p>
        <table><thead><tr>
            <th>Godz.</th><th>Mecz</th><th class="num">Wynik</th><th class="num">1 · X · 2</th>
            <th class="num" title="Ile razy ta para miała zdarzenie / ile razy grała">Para</th>
            <th>Ostatnie HT/FT pary</th>
            <th class="num" title="Gospodarz: zdarzenie / mecze w lidze">Gosp.</th>
            <th class="num" title="Gość: zdarzenie / mecze w lidze">Gość</th>
        </tr></thead><tbody>
        ${[...groups.entries()].map(([league, rows]) => `
            <tr class="league-head"><td colspan="8">${esc(league)} ${rows[0].tracked ? "" : `<span class="muted small">· liga spoza bazy</span>`}</td></tr>
            ${rows.map(v => {
                const m = v.match;
                const idx = state.daily.indexOf(v);
                return `<tr class="clickable ${v.pairHits ? "hit" : ""}" onclick="toggleDaily(${idx}, this)">
                    <td>${fmtTime(m.kickoff)}${m.status ? ` <span class="muted small">${esc(m.status)}</span>` : ""}</td>
                    <td><b>${esc(m.home)}</b> – <b>${esc(m.away)}</b></td>
                    <td class="num">${esc(m.score ?? "")}</td>
                    <td class="num odds">${m.odd1 ? `${m.odd1.toFixed(2)} · ${m.oddX.toFixed(2)} · ${m.odd2.toFixed(2)}` : ""}</td>
                    <td class="num">${v.tracked ? `<span class="pill ${v.pairHits ? "on" : ""}">${v.pairHits}/${v.pairMeetings}</span>` : ""}</td>
                    <td><span class="hist">${v.pairHistory.slice(0, 6).map(h =>
                        `<span class="${state.events.has(h.htft) ? "on" : ""}" title="${esc(h.season)} ${esc(h.home)} – ${esc(h.away)} ${esc(h.scoreHT)} / ${esc(h.scoreFT)}">${esc(h.htft)}</span>`).join("")}</span></td>
                    <td class="num">${v.tracked ? teamCell(v.homeStats) : ""}</td>
                    <td class="num">${v.tracked ? teamCell(v.awayStats) : ""}</td>
                </tr>`;
            }).join("")}`).join("")}
        </tbody></table>`;
}

window.toggleDaily = (idx, tr) => {
    if (tr.nextElementSibling?.classList.contains("detail")) return tr.nextElementSibling.remove();
    const v = state.daily[idx];
    const body = v.pairHistory.length
        ? `<table>${matchRows(v.pairHistory, true)}</table>`
        : `<span class="muted">Brak wcześniejszych meczów tej pary w bazie.</span>`;
    tr.insertAdjacentHTML("afterend", `<tr class="detail"><td colspan="8">${body}</td></tr>`);
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

// ---------------- Scraper ----------------

async function loadScraperConfig() {
    const cfg = await api("/api/v2/scrape/config");
    state.countries = cfg.countries;
    renderCountries();
}

function renderCountries() {
    $("countryList").innerHTML = state.countries.map(c => `
        <label><input type="checkbox" value="${esc(c.slug)}" ${state.selectedCountries.has(c.slug) ? "checked" : ""}>
        <span>${esc(c.slug)} <span class="muted small">od ${c.fromYear ?? "?"}${c.leagues ? " · " + esc(c.leagues.join(", ")) : ""}</span></span></label>`).join("");
    $("countryList").querySelectorAll("input").forEach(i => i.onchange = () => {
        i.checked ? state.selectedCountries.add(i.value) : state.selectedCountries.delete(i.value);
    });
}

$("btnAll").onclick = () => { state.countries.forEach(c => state.selectedCountries.add(c.slug)); renderCountries(); };
$("btnNone").onclick = () => { state.selectedCountries.clear(); renderCountries(); };

$("btnPlan").onclick = async () => {
    const slugs = [...state.selectedCountries];
    $("planPanel").hidden = false;
    $("planBody").innerHTML = `<p class="muted">Sprawdzam BetExplorer…</p>`;
    const plans = slugs.length
        ? (await Promise.all(slugs.map(s => api("/api/v2/scrape/plan?country=" + encodeURIComponent(s))))).flat()
        : await api("/api/v2/scrape/plan");
    $("planBody").innerHTML = `<table><thead><tr><th>Kraj</th><th>Ligi</th><th>Sezony</th><th>Inne dostępne ligi</th></tr></thead><tbody>
        ${plans.map(p => {
            const byLeague = {};
            p.seasons.forEach(s => (byLeague[s.league] ??= []).push(s.seasonLabel));
            return `<tr><td>${esc(p.country)}</td>
                <td>${p.error ? `<span style="color:var(--danger)">${esc(p.error)}</span>` : esc(p.selectedLeagues.join(", "))}</td>
                <td style="white-space:normal">${Object.entries(byLeague).map(([l, s]) => `<b>${esc(l)}</b>: ${esc(s.join(", "))}`).join("<br>")}</td>
                <td class="muted small" style="white-space:normal">${esc(p.availableLeagues.filter(l => !p.selectedLeagues.includes(l)).slice(0, 8).join(", "))}</td></tr>`;
        }).join("")}</tbody></table>`;
};

$("btnStart").onclick = async () => {
    try {
        await api("/api/v2/scrape/start", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({countries: [...state.selectedCountries], latestOnly: $("latestOnly").checked}),
        });
    } catch (e) {
        alert(e.message);
    }
    pollStatus();
};

$("btnStop").onclick = async () => { await api("/api/v2/scrape/cancel", {method: "POST"}); pollStatus(); };

let pollTimer;

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
    $("btnStop").disabled = !s.running;
    if (s.running) pollTimer = setTimeout(pollStatus, 2000);
}

// ---------------- start ----------------

renderEvents();
loadScraperConfig();
showView(new URLSearchParams(location.search).get("view") || "daily");
pollStatus();
