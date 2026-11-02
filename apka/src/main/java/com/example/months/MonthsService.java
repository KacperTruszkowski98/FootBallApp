package com.example.months;

import com.example.MatchEntity;
import com.example.MatchRepository;
import com.example.dto.MatchView;
import com.example.history.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Ranking drużyn w danym miesiącu kalendarzowym (np. listopad) we wszystkich latach od fromYear:
 * ile razy miały wybrane zdarzenia HT/FT, w ilu latach, rozbicie na lata.
 */
@Service
@RequiredArgsConstructor
public class MonthsService {

    public enum Sort {YEARS, HITS, PERCENT}

    private final MatchRepository repository;

    public MonthsView ranking(int month, List<String> events, int fromYear, int minMatches, String search,
                              Sort sort, int limit) {
        Set<String> wanted = new HashSet<>();
        events.forEach(e -> wanted.add(e.toUpperCase()));
        if (wanted.isEmpty()) wanted.addAll(HistoryService.EVENTS);
        String needle = search == null || search.isBlank() ? null : HistoryService.normalize(search);

        List<MatchEntity> matches = repository.findFinishedInMonth(month, LocalDate.of(fromYear, 1, 1).atStartOfDay());
        Map<String, TeamAcc> teams = new HashMap<>();
        SortedSet<Integer> years = new TreeSet<>();
        int total = 0, totalHits = 0;
        for (MatchEntity m : matches) {
            if (m.getKickoff() == null) continue;
            if (needle != null && !HistoryService.matchesSearch(needle, m.getCountry(), m.getLeague())) continue;
            int year = m.getKickoff().getYear();
            years.add(year);
            boolean hit = wanted.contains(m.getHTFT().toUpperCase());
            total++;
            if (hit) totalHits++;
            for (String team : List.of(m.getHome(), m.getAway())) {
                TeamAcc t = teams.computeIfAbsent(m.getCountry() + "\u0000" + team, k -> new TeamAcc(m.getCountry(), team));
                t.add(year, m, hit);
            }
        }

        Comparator<TeamAcc> order = switch (sort) {
            case HITS -> Comparator.comparingInt((TeamAcc t) -> -t.hits).thenComparingInt(t -> -t.yearsWithHit());
            case PERCENT -> Comparator.comparingDouble((TeamAcc t) -> -(double) t.hits / t.matches).thenComparingInt(t -> -t.hits);
            case YEARS -> Comparator.comparingInt((TeamAcc t) -> -t.yearsWithHit()).thenComparingInt(t -> -t.hits);
        };
        List<TeamRow> rows = teams.values().stream()
                .filter(t -> t.hits > 0 && t.matches >= minMatches)
                .sorted(order.thenComparing(t -> t.team))
                .limit(limit)
                .map(TeamAcc::row)
                .toList();
        return new MonthsView(month, new ArrayList<>(years), total, totalHits, rows);
    }

    private static class TeamAcc {
        final String country, team;
        int matches, hits;
        String league;
        java.time.LocalDateTime leagueAt;
        final Map<Integer, int[]> byYear = new TreeMap<>(); // rok -> [mecze, zdarzenia]
        final List<MatchEntity> events = new ArrayList<>();

        TeamAcc(String country, String team) {
            this.country = country;
            this.team = team;
        }

        void add(int year, MatchEntity m, boolean hit) {
            matches++;
            int[] y = byYear.computeIfAbsent(year, k -> new int[2]);
            y[0]++;
            if (hit) {
                hits++;
                y[1]++;
                events.add(m);
            }
            if (leagueAt == null || m.getKickoff().isAfter(leagueAt)) {
                league = m.getLeague();
                leagueAt = m.getKickoff();
            }
        }

        int yearsWithHit() {
            return (int) byYear.values().stream().filter(y -> y[1] > 0).count();
        }

        TeamRow row() {
            Map<Integer, YearCell> years = new TreeMap<>();
            byYear.forEach((y, v) -> years.put(y, new YearCell(v[0], v[1])));
            List<MatchView> ev = events.stream()
                    .sorted(Comparator.comparing(MatchEntity::getKickoff).reversed())
                    .map(MatchView::of).toList();
            return new TeamRow(team, country, league, matches, hits, yearsWithHit(), byYear.size(), years, ev);
        }
    }

    public record YearCell(int matches, int hits) {
    }

    public record TeamRow(String team, String country, String league, int matches, int hits, int yearsWithHit,
                          int yearsPlayed, Map<Integer, YearCell> years, List<MatchView> events) {
    }

    public record MonthsView(int month, List<Integer> years, int matches, int hits, List<TeamRow> teams) {
    }
}
