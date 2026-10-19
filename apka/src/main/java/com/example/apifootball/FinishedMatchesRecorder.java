package com.example.apifootball;

import com.example.MatchRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.example.apifootball.ApiFootballNames.externalId;

/**
 * Zakończone mecze dnia z lig z konfiguracji zapisuje do bazy - stają się historyczne bez osobnego scrapingu.
 * Dociąga cały sezon ligi tak jak scraper (jedno zapytanie /fixtures na ligę, z ujednoliceniem etapów),
 * w tle, żeby nie spowalniać listy meczów; ligi, w których wszystko jest już w bazie, są pomijane.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinishedMatchesRecorder {

    private static final Set<String> FINISHED = Set.of("FT", "AET", "PEN");

    private final ApiFootballResultsSource results;
    private final MatchRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    void record(JsonNode fixtures, Set<Integer> trackedLeagues) {
        Map<String, List<String>> finishedBySeason = new LinkedHashMap<>();
        for (JsonNode f : fixtures) {
            int leagueId = f.path("league").path("id").asInt();
            if (!trackedLeagues.contains(leagueId)) continue;
            if (!FINISHED.contains(f.path("fixture").path("status").path("short").asText())) continue;
            finishedBySeason.computeIfAbsent(leagueId + ":" + f.path("league").path("season").asInt(), k -> new ArrayList<>())
                    .add(externalId(f));
        }
        if (finishedBySeason.isEmpty()) return;

        Set<String> existing = repository.findExistingExternalIds(
                finishedBySeason.values().stream().flatMap(List::stream).toList());
        finishedBySeason.forEach((season, ids) -> {
            if (existing.containsAll(ids) || !queued.add(season)) return;
            executor.submit(() -> {
                try {
                    String[] ref = season.split(":");
                    var r = results.scrapeSeason(Integer.parseInt(ref[0]), Integer.parseInt(ref[1]));
                    log.info("Mecze dnia -> baza: liga {} - nowe: {}", season, r.saved());
                } catch (Exception e) {
                    log.warn("Nie udało się zapisać zakończonych meczów ligi {}: {}", season, e.getMessage());
                } finally {
                    queued.remove(season);
                }
            });
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
