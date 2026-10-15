package com.example.analytics;

import com.example.MatchEntity;
import com.example.MatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analizy na meczach pobranych przez automatyczny scraper (rekordy z wypełnioną ligą).
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    public static final List<String> HTFT_VALUES = List.of("1/1", "1/X", "1/2", "X/1", "X/X", "X/2", "2/1", "2/X", "2/2");

    private final MatchRepository repository;

    public List<CountryCompetitions> competitions() {
        Map<String, Map<String, LeagueInfo>> byCountry = new TreeMap<>();

        for (Object[] row : repository.findCompetitionKeys()) {
            String country = (String) row[0];
            String league = (String) row[1];
            String stage = (String) row[2];
            int startYear = (Integer) row[3];
            String label = (String) row[4];
            long count = (Long) row[5];

            LeagueInfo info = byCountry.computeIfAbsent(country, k -> new TreeMap<>())
                    .computeIfAbsent(league, k -> new LeagueInfo(k, new TreeSet<>(), new TreeMap<>(), new long[1]));
            if (stage != null) info.stages().add(stage);
            info.seasons().put(startYear, label);
            info.counter()[0] += count;
        }

        return byCountry.entrySet().stream()
                .map(e -> new CountryCompetitions(e.getKey(), List.copyOf(e.getValue().values())))
                .toList();
    }

    public List<MatchView> matches(Filter filter) {
        return load(filter).stream()
                .map(MatchView::of)
                .sorted(MatchView.CHRONO)
                .toList();
    }

    /** Rozkład HT/FT w każdym sezonie (opcjonalnie osobno dla każdego etapu). */
    public List<SeasonSummary> summary(Filter filter, boolean splitStages) {
        Map<String, List<MatchEntity>> grouped = load(filter).stream()
                .filter(m -> m.getHTFT() != null)
                .collect(Collectors.groupingBy(
                        m -> m.getStartSeasonYear() + "|" + m.getSeasonLabel() + "|" + (splitStages ? m.getStage() : ""),
                        TreeMap::new, Collectors.toList()));

        return grouped.entrySet().stream().map(e -> {
            String[] key = e.getKey().split("\\|", -1);
            Map<String, Long> counts = new LinkedHashMap<>();
            HTFT_VALUES.forEach(v -> counts.put(v, 0L));
            e.getValue().forEach(m -> counts.merge(m.getHTFT().toUpperCase(), 1L, Long::sum));
            long selected = filter.events().isEmpty() ? 0 :
                    e.getValue().stream().filter(m -> filter.matchesEvent(m.getHTFT())).count();
            return new SeasonSummary(key[1], Integer.parseInt(key[0]), key[2].isEmpty() ? null : key[2],
                    e.getValue().size(), selected, counts);
        }).toList();
    }

    /**
     * "Wspólne mecze": pary drużyn, które razem zaliczyły wybrane zdarzenie (np. 1/2 lub 2/1),
     * niezależnie od tego, kto był gospodarzem.
     */
    public List<PairStats> pairs(Filter filter, int minCount) {
        List<MatchEntity> all = load(filter);

        Map<String, Integer> meetings = new HashMap<>();
        for (MatchEntity m : all) {
            if (m.getHTFT() != null) meetings.merge(pairKey(m), 1, Integer::sum);
        }

        Map<String, List<MatchEntity>> hits = all.stream()
                .filter(m -> filter.matchesEvent(m.getHTFT()))
                .collect(Collectors.groupingBy(AnalyticsService::pairKey));

        return hits.entrySet().stream()
                .filter(e -> e.getValue().size() >= minCount)
                .map(e -> {
                    String[] teams = e.getKey().split("\u0000");
                    List<MatchView> occurrences = e.getValue().stream().map(MatchView::of).sorted(MatchView.CHRONO).toList();
                    return new PairStats(teams[0], teams[1], occurrences.size(), meetings.getOrDefault(e.getKey(), 0), occurrences);
                })
                .sorted(Comparator.comparingInt(PairStats::count).reversed()
                        .thenComparing(Comparator.comparingDouble(PairStats::ratio).reversed()))
                .toList();
    }

    /** Ile razy każda drużyna miała wybrane zdarzenie. */
    public List<TeamStats> teams(Filter filter) {
        Map<String, int[]> stats = new HashMap<>();
        for (MatchEntity m : load(filter)) {
            if (m.getHTFT() == null) continue;
            boolean hit = filter.matchesEvent(m.getHTFT());
            for (String team : List.of(m.getHome(), m.getAway())) {
                int[] s = stats.computeIfAbsent(team, k -> new int[2]);
                s[0]++;
                if (hit) s[1]++;
            }
        }
        return stats.entrySet().stream()
                .map(e -> new TeamStats(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .filter(t -> t.hits() > 0)
                .sorted(Comparator.comparingInt(TeamStats::hits).reversed().thenComparing(TeamStats::team))
                .toList();
    }

    private List<MatchEntity> load(Filter filter) {
        String league = filter.league() == null || filter.league().isBlank() ? null : filter.league();
        return repository.findCompetitionMatches(filter.country(), league, filter.fromSeason()).stream()
                .filter(m -> filter.stages().isEmpty() || filter.stages().contains(m.getStage()))
                .toList();
    }

    private static String pairKey(MatchEntity m) {
        return m.getHome().compareTo(m.getAway()) <= 0
                ? m.getHome() + "\u0000" + m.getAway()
                : m.getAway() + "\u0000" + m.getHome();
    }

    // ---------- modele ----------

    public record Filter(String country, String league, List<String> stages, List<String> events, int fromSeason) {
        public Filter {
            stages = stages == null ? List.of() : stages;
            events = events == null ? List.of() : events.stream().map(String::toUpperCase).toList();
        }

        boolean matchesEvent(String htft) {
            return htft != null && events.contains(htft.toUpperCase());
        }
    }

    public record LeagueInfo(String league, SortedSet<String> stages, SortedMap<Integer, String> seasons,
                             @com.fasterxml.jackson.annotation.JsonIgnore long[] counter) {
        @com.fasterxml.jackson.annotation.JsonProperty("matches")
        public long matches() {
            return counter[0];
        }
    }

    public record CountryCompetitions(String country, List<LeagueInfo> leagues) {
    }

    public record SeasonSummary(String season, int startYear, String stage, long matches, long selected,
                                Map<String, Long> htft) {
    }

    public record PairStats(String teamA, String teamB, int count, int meetings, List<MatchView> occurrences) {
        public double ratio() {
            return meetings == 0 ? 0 : (double) count / meetings;
        }
    }

    public record TeamStats(String team, int matches, int hits) {
    }

    public record MatchView(long id, String season, int startYear, String league, String stage, String round,
                            Integer roundNumber, LocalDateTime kickoff, String home, String away,
                            String scoreHT, String scoreFT, String htft) {

        static final Comparator<MatchView> CHRONO = Comparator
                .comparing(MatchView::kickoff, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(MatchView::id);

        public static MatchView of(MatchEntity m) {
            return new MatchView(m.getId(), m.getSeasonLabel(), m.getStartSeasonYear(), m.getLeague(), m.getStage(),
                    m.getRoundName(), m.getCurrentRound(), m.getKickoff(), m.getHome(), m.getAway(),
                    m.getScoreHT(), m.getScoreFT(), m.getHTFT());
        }
    }
}
