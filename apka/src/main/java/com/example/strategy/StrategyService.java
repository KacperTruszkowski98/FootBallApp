package com.example.strategy;

import com.example.MatchEntity;
import com.example.MatchRepository;
import com.example.analytics.AnalyticsService.MatchView;
import com.example.apifootball.ApiFootballFixtures;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Zakładka "Lamaki": mecze par, które od historyFrom miały już co najmniej minCommon razy wybrane zdarzenie,
 * stan serii w każdej lidze i nadchodzące mecze spełniające warunek (z kursami HT/FT z API).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final Set<String> SKIPPED_STATUSES = Set.of("FT", "AET", "PEN", "PST", "CANC", "ABD", "AWD", "WO");
    private static final int PAIR_HISTORY_LIMIT = 20;

    private final StrategyProperties properties;
    private final MatchRepository repository;
    private final ApiFootballFixtures fixtures;

    public StrategyView view(Settings settings) {
        LocalDate today = LocalDate.now(ZONE);
        Set<String> events = new HashSet<>(settings.events());

        List<LeagueState> leagues = new ArrayList<>();
        List<Pick> recent = new ArrayList<>();
        Map<String, LeagueScan> scans = new HashMap<>();

        for (StrategyProperties.League league : properties.leagues()) {
            List<MatchEntity> history = repository
                    .findCompetitionMatches(league.country(), league.league(), settings.historyFrom().getYear() - 1).stream()
                    .filter(m -> m.getHTFT() != null && m.getKickoff() != null
                            && !m.getKickoff().toLocalDate().isBefore(settings.historyFrom()))
                    .sorted(Comparator.comparing(MatchEntity::getKickoff).thenComparing(MatchEntity::getId))
                    .toList();

            LeagueScan scan = scan(history, events, settings.minCommon(), today.minusDays(settings.daysBack()));
            scans.put(key(league.country(), league.league()), scan);
            leagues.add(new LeagueState(league.label(), league.country(), league.league(), history.size(), scan.eventHits(),
                    scan.qualifying(), scan.qualifyingHits(), scan.sinceLastHit(),
                    scan.lastHit() == null ? null : MatchView.of(scan.lastHit())));
            scan.recent().forEach(r -> recent.add(new Pick(league.label(), MatchView.of(r.match()), r.commonBefore(), r.hit())));
        }

        List<Candidate> upcoming = new ArrayList<>();
        String warning = null;
        try {
            for (int d = 0; d < settings.daysAhead(); d++) {
                for (ApiFootballFixtures.Fixture f : fixtures.byDate(today.plusDays(d))) {
                    if (SKIPPED_STATUSES.contains(f.status())) continue;
                    LeagueScan scan = scans.get(key(f.country(), f.league()));
                    if (scan == null) continue;
                    String pair = pairKey(f.home(), f.away());
                    List<MatchEntity> hits = scan.pairHits().getOrDefault(pair, List.of());
                    if (hits.size() < settings.minCommon()) continue;

                    StrategyProperties.League league = leagueFor(f.country(), f.league());
                    upcoming.add(new Candidate(league.label(), f.externalId(), f.kickoff(), f.status(), f.home(), f.away(),
                            hits.size(), scan.meetings().getOrDefault(pair, 0), scan.sinceLastHit(),
                            hits.reversed().stream().limit(PAIR_HISTORY_LIMIT).map(MatchView::of).toList(),
                            odds(f.externalId(), settings.events())));
                }
            }
        } catch (Exception e) {
            log.warn("Nie udało się pobrać terminarza", e);
            warning = "Nie udało się pobrać terminarza z API-Football: " + e.getMessage();
        }

        upcoming.sort(Comparator.comparing(Candidate::kickoff, Comparator.nullsLast(Comparator.naturalOrder())));
        recent.sort(Comparator.comparing((Pick p) -> p.match().kickoff()).reversed());
        return new StrategyView(settings, properties.target(), properties.cap(), properties.tax(),
                leagues, upcoming, recent, warning);
    }

    /**
     * Uzupełnia kolejność goli w meczach ligi strategii, które miały wybrane zdarzenie (potrzebne do "podwójnych lamaków").
     * Pobiera tylko brakujące - kolejne wywołania kosztują tyle zapytań, ile przybyło nowych zdarzeń.
     */
    public GoalsResult fillGoalOrders(Settings settings) throws Exception {
        Set<String> events = new HashSet<>(settings.events());
        Map<String, MatchEntity> missing = new LinkedHashMap<>();
        for (StrategyProperties.League league : properties.leagues()) {
            repository.findCompetitionMatches(league.country(), league.league(), settings.historyFrom().getYear() - 1).stream()
                    .filter(m -> m.getGoalOrder() == null && m.getHTFT() != null && events.contains(m.getHTFT().toUpperCase())
                            && m.getExternalId() != null && m.getExternalId().startsWith("af:")
                            && m.getKickoff() != null && !m.getKickoff().toLocalDate().isBefore(settings.historyFrom()))
                    .forEach(m -> missing.put(m.getExternalId(), m));
        }
        if (missing.isEmpty()) return new GoalsResult(0, 0);

        Map<String, String> orders = fixtures.goalOrders(missing.keySet());
        List<MatchEntity> changed = new ArrayList<>();
        orders.forEach((id, order) -> {
            MatchEntity m = missing.get(id);
            if (m != null) {
                m.setGoalOrder(order);
                changed.add(m);
            }
        });
        repository.saveAll(changed);
        return new GoalsResult(changed.size(), (int) changed.stream().filter(m -> "?".equals(m.getGoalOrder())).count());
    }

    /** Kraje do dociągnięcia wyników (slugi z api-football-leagues.yaml). */
    public List<String> countrySlugs() {
        return properties.leagues().stream().map(StrategyProperties.League::slug).distinct().toList();
    }

    public Settings defaults() {
        return new Settings(properties.events(), properties.minCommon(), properties.historyFrom(),
                properties.daysAhead(), properties.daysBack());
    }

    /**
     * Przechodzi chronologicznie po meczach ligi. Mecz "kwalifikuje się", jeśli para miała PRZED nim co najmniej
     * minCommon zdarzeń - tak samo jak wyglądałoby to w dniu meczu. Seria = kwalifikujące się mecze od ostatniego trafienia.
     */
    static LeagueScan scan(List<MatchEntity> chronological, Set<String> events, int minCommon, LocalDate recentFrom) {
        Map<String, List<MatchEntity>> pairHits = new HashMap<>();
        Map<String, Integer> meetings = new HashMap<>();
        List<RecentPick> recent = new ArrayList<>();
        int eventHits = 0, qualifying = 0, qualifyingHits = 0, sinceLastHit = 0;
        MatchEntity lastHit = null;

        for (MatchEntity m : chronological) {
            String pair = pairKey(m.getHome(), m.getAway());
            int before = pairHits.getOrDefault(pair, List.of()).size();
            boolean hit = events.contains(m.getHTFT().toUpperCase());

            if (before >= minCommon) {
                qualifying++;
                if (hit) {
                    qualifyingHits++;
                    sinceLastHit = 0;
                    lastHit = m;
                } else {
                    sinceLastHit++;
                }
                if (!m.getKickoff().toLocalDate().isBefore(recentFrom)) recent.add(new RecentPick(m, before, hit));
            }

            meetings.merge(pair, 1, Integer::sum);
            if (hit) {
                eventHits++;
                pairHits.computeIfAbsent(pair, k -> new ArrayList<>()).add(m);
            }
        }
        return new LeagueScan(pairHits, meetings, eventHits, qualifying, qualifyingHits, sinceLastHit, lastHit, recent);
    }

    private Map<String, ApiFootballFixtures.BestOdd> odds(String externalId, List<String> events) {
        try {
            Map<String, ApiFootballFixtures.BestOdd> all = fixtures.htFtOdds(externalId);
            Map<String, ApiFootballFixtures.BestOdd> wanted = new LinkedHashMap<>();
            events.forEach(e -> {
                if (all.containsKey(e)) wanted.put(e, all.get(e));
            });
            return wanted;
        } catch (Exception e) {
            log.warn("Brak kursów HT/FT dla {}: {}", externalId, e.getMessage());
            return Map.of();
        }
    }

    private StrategyProperties.League leagueFor(String country, String league) {
        return properties.leagues().stream()
                .filter(l -> l.country().equals(country) && l.league().equals(league))
                .findFirst().orElseThrow();
    }

    static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "\u0000" + b : b + "\u0000" + a;
    }

    private static String key(String country, String league) {
        return country + "\u0000" + league;
    }

    record LeagueScan(Map<String, List<MatchEntity>> pairHits, Map<String, Integer> meetings, int eventHits,
                      int qualifying, int qualifyingHits, int sinceLastHit, MatchEntity lastHit, List<RecentPick> recent) {
    }

    record RecentPick(MatchEntity match, int commonBefore, boolean hit) {
    }

    public record Settings(List<String> events, int minCommon, LocalDate historyFrom, int daysAhead, int daysBack) {
        public Settings {
            events = events.stream().map(String::toUpperCase).toList();
        }
    }

    /**
     * @param matches        mecze ligi od historyFrom
     * @param eventHits      ile z nich miało zdarzenie
     * @param qualifying     mecze par z co najmniej minCommon wcześniejszymi zdarzeniami
     * @param qualifyingHits ile z nich miało zdarzenie
     * @param sinceLastHit   kwalifikujące się mecze od ostatniego trafienia (bieżąca seria)
     */
    public record LeagueState(String name, String country, String league, int matches, int eventHits, int qualifying,
                              int qualifyingHits, int sinceLastHit, MatchView lastHit) {
    }

    public record Candidate(String league, String externalId, LocalDateTime kickoff, String status, String home,
                            String away, int common, int meetings, int leagueSeries, List<MatchView> pairHits,
                            Map<String, ApiFootballFixtures.BestOdd> odds) {
    }

    public record Pick(String league, MatchView match, int commonBefore, boolean hit) {
    }

    /** filled = mecze z uzupełnioną kolejnością goli, unknown = w tym niezgodne z wynikiem ("?"). */
    public record GoalsResult(int filled, int unknown) {
    }

    public record StrategyView(Settings settings, double target, double cap, double tax, List<LeagueState> leagues,
                               List<Candidate> upcoming, List<Pick> recent, String warning) {
    }
}
