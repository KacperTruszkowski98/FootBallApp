package com.example.daily;

import com.example.MatchEntity;
import com.example.MatchRepository;
import com.example.dto.MatchView;
import com.example.history.HistoryService;
import com.example.source.DailySource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/**
 * Mecze dnia + historia z naszej bazy: co dana para (i każda z drużyn) miała wcześniej w wybranym zdarzeniu.
 */
@Service
@RequiredArgsConstructor
public class DailyService {

    private static final int HISTORY_LIMIT = 50;
    /** Statystyki miesiąca liczone od tego roku - jak domyślnie w zakładce Miesiące. */
    static final int MONTH_FROM_YEAR = 2021;
    private static final java.time.Duration MONTH_CACHE_TTL = java.time.Duration.ofMinutes(30);
    private static final String WORLD = "World";

    private final DailySource source;
    private final MatchRepository repository;
    private final Map<Integer, MonthCache> monthCache = new java.util.concurrent.ConcurrentHashMap<>();

    public List<DailyMatchView> daily(LocalDate date, List<String> events, boolean onlyTracked) throws Exception {
        return daily(date, events, onlyTracked, HistoryService.EVENTS);
    }

    /** droughtEvents - zdarzenia, dla których liczymy suszę ligi i "ile spotkań temu" pary (domyślnie wszystkie 4). */
    public List<DailyMatchView> daily(LocalDate date, List<String> events, boolean onlyTracked,
                                      List<String> droughtEvents) throws Exception {
        Set<String> wanted = new HashSet<>();
        events.forEach(e -> wanted.add(e.toUpperCase()));
        Set<String> dry = new HashSet<>();
        droughtEvents.forEach(e -> dry.add(e.toUpperCase()));
        if (dry.isEmpty()) dry.addAll(HistoryService.EVENTS);

        Set<String> tracked = new HashSet<>();
        for (Object[] row : repository.findCompetitionKeys()) {
            tracked.add(key((String) row[0], (String) row[1]));
        }

        Map<String, List<MatchEntity>> byCountry = new HashMap<>();
        List<DailyMatchView> result = new ArrayList<>();
        MonthTeams month = monthTeams(date.getMonthValue());

        for (DailyMatch m : source.fetch(date)) {
            boolean isTracked = tracked.contains(key(m.country(), m.league()));
            if (onlyTracked && !isTracked) continue;

            if (!isTracked) {
                result.add(new DailyMatchView(m, false, 0, 0, List.of(), null, null, Map.of(), null, null, null, Map.of(),
                        month.stats(m.country(), m.home(), wanted), month.stats(m.country(), m.away(), wanted)));
                continue;
            }

            List<MatchEntity> history = byCountry.computeIfAbsent(m.country(),
                    c -> repository.findCompetitionMatches(c, null, 0));

            List<MatchEntity> pair = history.stream()
                    .filter(h -> h.getHTFT() != null && !m.externalId().equals(h.getExternalId()) && samePair(h, m.home(), m.away()))
                    .sorted(Comparator.comparing(MatchEntity::getKickoff, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                    .toList();
            int pairHits = (int) pair.stream().filter(h -> wanted.contains(h.getHTFT().toUpperCase())).count();

            // stałe rozbicie na 1/2, 2/1, 1/X, 2/X - niezależnie od wybranych chipów
            Map<String, Integer> pairCounts = new LinkedHashMap<>();
            HistoryService.EVENTS.forEach(e -> pairCounts.put(e, 0));
            Integer sinceLast = null;
            MatchEntity lastEvent = null;
            for (int i = 0; i < pair.size(); i++) {
                String htft = pair.get(i).getHTFT().toUpperCase();
                if (!pairCounts.containsKey(htft)) continue;
                pairCounts.merge(htft, 1, Integer::sum);
                if (lastEvent == null && dry.contains(htft)) {
                    lastEvent = pair.get(i);
                    sinceLast = i;
                }
            }

            result.add(new DailyMatchView(m, true, pair.size(), pairHits,
                    pair.stream().limit(HISTORY_LIMIT).map(MatchView::of).toList(),
                    teamStats(history, m.home(), m.league(), wanted),
                    teamStats(history, m.away(), m.league(), wanted),
                    pairCounts, sinceLast, lastEvent == null ? null : MatchView.of(lastEvent),
                    drought(history, m, dry),
                    droughts(history, m),
                    month.stats(m.country(), m.home(), wanted), month.stats(m.country(), m.away(), wanted)));
        }

        result.sort(Comparator
                .comparing((DailyMatchView v) -> v.match().kickoff(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(v -> v.match().country())
                .thenComparing(v -> v.match().league()));
        return result;
    }

    /**
     * Ile razy drużyna miała zdarzenia HT/FT w tym miesiącu kalendarzowym (wszystkie lata od {@link #MONTH_FROM_YEAR},
     * ligi i puchary) - to samo co ranking w zakładce Miesiące. Zapamiętane na 30 minut, bo to cały miesiąc z bazy.
     */
    private MonthTeams monthTeams(int month) {
        MonthCache cached = monthCache.get(month);
        if (cached != null && cached.at().plus(MONTH_CACHE_TTL).isAfter(java.time.Instant.now())) return cached.teams();
        MonthTeams teams = new MonthTeams();
        for (MatchEntity h : repository.findFinishedInMonth(month, LocalDate.of(MONTH_FROM_YEAR, 1, 1).atStartOfDay())) {
            if (h.getKickoff() == null) continue;
            teams.add(h.getCountry(), h.getHome(), h.getKickoff().getYear(), h.getHTFT());
            teams.add(h.getCountry(), h.getAway(), h.getKickoff().getYear(), h.getHTFT());
        }
        monthCache.put(month, new MonthCache(java.time.Instant.now(), teams));
        return teams;
    }

    private record MonthCache(java.time.Instant at, MonthTeams teams) {
    }

    /** drużyna -> rok -> HT/FT -> liczba meczów; osobno po kraju i po samej nazwie (dla pucharów międzynarodowych). */
    static final class MonthTeams {
        private final Map<String, Map<Integer, Map<String, Integer>>> byCountryTeam = new HashMap<>();
        private final Map<String, Map<Integer, Map<String, Integer>>> byTeam = new HashMap<>();

        void add(String country, String team, int year, String htft) {
            String e = htft.toUpperCase();
            byCountryTeam.computeIfAbsent(country + "\u0000" + team, k -> new TreeMap<>())
                    .computeIfAbsent(year, k -> new HashMap<>()).merge(e, 1, Integer::sum);
            byTeam.computeIfAbsent(team, k -> new TreeMap<>())
                    .computeIfAbsent(year, k -> new HashMap<>()).merge(e, 1, Integer::sum);
        }

        /** Mecz w pucharze międzynarodowym (kraj "World") - drużyna liczona ze wszystkich swoich rozgrywek. */
        MonthStats stats(String country, String team, Set<String> wanted) {
            var years = WORLD.equals(country) ? byTeam.get(team) : byCountryTeam.get(country + "\u0000" + team);
            if (years == null) return null;
            int matches = 0, hits = 0, yearsWithHit = 0;
            for (Map<String, Integer> y : years.values()) {
                int yearHits = 0;
                for (var en : y.entrySet()) {
                    matches += en.getValue();
                    if (wanted.contains(en.getKey())) yearHits += en.getValue();
                }
                hits += yearHits;
                if (yearHits > 0) yearsWithHit++;
            }
            return new MonthStats(matches, hits, yearsWithHit, years.size());
        }
    }

    /** Statystyki drużyny w miesiącu meczu: mecze, zdarzenia, w ilu latach było zdarzenie / w ilu grała. */
    public record MonthStats(int matches, int hits, int yearsWithHit, int years) {
    }

    public Map<String, double[]> odds(LocalDate date) throws Exception {
        return source.odds(date);
    }

    /**
     * Susza w lidze meczu: ile kolejek (sezon + etap + runda) i meczów przed tym meczem nie było żadnego ze zdarzeń.
     * Kolejka, w której padło ostatnie zdarzenie, się nie liczy.
     */
    static LeagueDrought drought(List<MatchEntity> history, DailyMatch m, Set<String> dry) {
        List<MatchEntity> league = history.stream()
                .filter(h -> h.getHTFT() != null && h.getKickoff() != null && m.league().equals(h.getLeague()))
                .filter(h -> !m.externalId().equals(h.getExternalId()))
                .filter(h -> m.kickoff() == null || h.getKickoff().isBefore(m.kickoff()))
                .sorted(Comparator.comparing(MatchEntity::getKickoff).thenComparing(MatchEntity::getId).reversed())
                .toList();
        if (league.isEmpty()) return null;

        Set<String> rounds = new HashSet<>();
        int matches = 0;
        MatchEntity last = null;
        for (MatchEntity h : league) {
            if (dry.contains(h.getHTFT().toUpperCase())) {
                last = h;
                break;
            }
            matches++;
            rounds.add(roundKey(h));
        }
        if (last != null) rounds.remove(roundKey(last));
        return new LeagueDrought(rounds.size(), matches, last == null ? null : MatchView.of(last),
                MatchView.of(league.get(0)).kickoff());
    }

    private static final Set<String> LAMAKI = Set.of("1/2", "2/1");
    private static final Set<String> DRAWS = Set.of("1/X", "2/X");

    /** Susza osobno dla lamaków (1/2+2/1) i dla 1/X+2/X - do pokazania obok siebie przy lidze. */
    static Map<String, LeagueDrought> droughts(List<MatchEntity> history, DailyMatch m) {
        Map<String, LeagueDrought> result = new LinkedHashMap<>();
        LeagueDrought l = drought(history, m, LAMAKI), d = drought(history, m, DRAWS);
        if (l != null) result.put("lamaki", l);
        if (d != null) result.put("draws", d);
        return result;
    }

    private static String roundKey(MatchEntity h) {
        String round = h.getRoundName() != null ? h.getRoundName()
                : h.getCurrentRound() != null ? String.valueOf(h.getCurrentRound()) : h.getKickoff().toLocalDate().toString();
        return h.getSeasonLabel() + "|" + h.getStage() + "|" + round;
    }

    private static TeamStats teamStats(List<MatchEntity> history, String team, String league, Set<String> wanted) {
        int matches = 0, hits = 0;
        for (MatchEntity h : history) {
            if (h.getHTFT() == null || !league.equals(h.getLeague())) continue;
            if (!team.equals(h.getHome()) && !team.equals(h.getAway())) continue;
            matches++;
            if (wanted.contains(h.getHTFT().toUpperCase())) hits++;
        }
        return new TeamStats(matches, hits);
    }

    private static boolean samePair(MatchEntity h, String a, String b) {
        return (a.equals(h.getHome()) && b.equals(h.getAway())) || (b.equals(h.getHome()) && a.equals(h.getAway()));
    }

    private static String key(String country, String league) {
        return country + "\u0000" + league;
    }

    /** lastEvent null = w bazie nie było zdarzenia; lastInDb - ostatni mecz ligi w bazie (czy dane są świeże). */
    public record LeagueDrought(int rounds, int matches, MatchView lastEvent, java.time.LocalDateTime lastInDb) {
    }

    public record TeamStats(int matches, int hits) {
    }

    public record DailyMatchView(DailyMatch match, boolean tracked, int pairMeetings, int pairHits,
                                 List<MatchView> pairHistory, TeamStats homeStats, TeamStats awayStats,
                                 Map<String, Integer> pairCounts, Integer sinceLast, MatchView lastEvent,
                                 LeagueDrought drought, Map<String, LeagueDrought> droughts,
                                 MonthStats homeMonth, MonthStats awayMonth) {
    }
}
