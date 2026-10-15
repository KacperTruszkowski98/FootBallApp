package com.example.daily;

import com.example.MatchEntity;
import com.example.MatchRepository;
import com.example.analytics.AnalyticsService;
import com.example.analytics.AnalyticsService.MatchView;
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

    private static final int HISTORY_LIMIT = 10;

    private final DailySource source;
    private final MatchRepository repository;

    public List<DailyMatchView> daily(LocalDate date, List<String> events, boolean onlyTracked) throws Exception {
        Set<String> wanted = new HashSet<>();
        events.forEach(e -> wanted.add(e.toUpperCase()));

        Set<String> tracked = new HashSet<>();
        for (Object[] row : repository.findCompetitionKeys()) {
            tracked.add(key((String) row[0], (String) row[1]));
        }

        Map<String, List<MatchEntity>> byCountry = new HashMap<>();
        List<DailyMatchView> result = new ArrayList<>();

        for (DailyMatch m : source.fetch(date)) {
            boolean isTracked = tracked.contains(key(m.country(), m.league()));
            if (onlyTracked && !isTracked) continue;

            if (!isTracked) {
                result.add(new DailyMatchView(m, false, 0, 0, List.of(), null, null));
                continue;
            }

            List<MatchEntity> history = byCountry.computeIfAbsent(m.country(),
                    c -> repository.findCompetitionMatches(c, null, 0));

            List<MatchEntity> pair = history.stream()
                    .filter(h -> h.getHTFT() != null && !m.externalId().equals(h.getExternalId()) && samePair(h, m.home(), m.away()))
                    .sorted(Comparator.comparing(MatchEntity::getKickoff, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                    .toList();
            int pairHits = (int) pair.stream().filter(h -> wanted.contains(h.getHTFT().toUpperCase())).count();

            result.add(new DailyMatchView(m, true, pair.size(), pairHits,
                    pair.stream().limit(HISTORY_LIMIT).map(AnalyticsService.MatchView::of).toList(),
                    teamStats(history, m.home(), m.league(), wanted),
                    teamStats(history, m.away(), m.league(), wanted)));
        }

        result.sort(Comparator
                .comparing((DailyMatchView v) -> v.match().kickoff(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(v -> v.match().country())
                .thenComparing(v -> v.match().league()));
        return result;
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

    public record TeamStats(int matches, int hits) {
    }

    public record DailyMatchView(DailyMatch match, boolean tracked, int pairMeetings, int pairHits,
                                 List<MatchView> pairHistory, TeamStats homeStats, TeamStats awayStats) {
    }
}
