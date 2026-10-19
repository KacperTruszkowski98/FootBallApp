package com.example.scraper;

import com.example.source.ResultsSource;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uruchamia scraping w tle (jedno zadanie naraz) i trzyma jego postęp do podglądu z frontu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScrapeJobService {

    private static final int LOG_LIMIT = 200;

    private final ResultsSource source;
    private final ScraperProperties properties;

    private final ExecutorService runner = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private volatile JobStatus status = JobStatus.idle();

    /**
     * @param slugs      kraje do pobrania (puste = wszystkie z konfiguracji)
     * @param leagueRefs pojedyncze ligi z innych krajów; kraj podany w slugs pobiera się w całości.
     *                   Nic nie podane = wszystkie kraje i ligi z konfiguracji
     */
    public synchronized JobStatus start(Collection<String> slugs, Collection<String> leagueRefs, boolean latestOnly) {
        if (status.running()) {
            throw new IllegalStateException("Scraping już trwa");
        }

        List<ResultsSource.ConfiguredCountry> config = source.countries();
        List<String> configured = config.stream().map(ResultsSource.ConfiguredCountry::slug).toList();
        Set<String> leagues = leagueRefs == null ? Set.of() : Set.copyOf(leagueRefs);

        // kraj -> ligi do pobrania (pusty zbiór = wszystkie ligi kraju)
        Map<String, Set<String>> selected = new LinkedHashMap<>();
        (slugs == null ? List.<String>of() : slugs).forEach(s -> selected.put(s, Set.of()));
        for (ResultsSource.ConfiguredCountry c : config) {
            if (selected.containsKey(c.slug())) continue;
            Set<String> refs = new LinkedHashSet<>();
            c.leagues().stream().map(ResultsSource.ConfiguredLeague::ref).filter(leagues::contains).forEach(refs::add);
            if (!refs.isEmpty()) selected.put(c.slug(), refs);
        }
        if (selected.isEmpty()) configured.forEach(s -> selected.put(s, Set.of()));
        selected.keySet().stream().filter(s -> !configured.contains(s)).findFirst().ifPresent(s -> {
            throw new IllegalArgumentException("Brak kraju w konfiguracji: " + s);
        });

        cancelRequested.set(false);
        status = new JobStatus(true, LocalDateTime.now(), null, "Wyszukiwanie lig i sezonów...",
                0, 0, 0, 0, new LinkedList<>());
        runner.submit(() -> run(selected, latestOnly));
        return status;
    }

    public void cancel() {
        cancelRequested.set(true);
        appendLog("Zażądano zatrzymania - kończę bieżący etap...");
    }

    public JobStatus status() {
        return status;
    }

    private void run(Map<String, Set<String>> countries, boolean latestOnly) {
        ExecutorService pool = Executors.newFixedThreadPool(properties.threads());
        try {
            List<LeagueSeason> tasks = new ArrayList<>();
            appendLog("Źródło: " + source.name());
            for (String country : countries.keySet()) {
                Set<String> leagues = countries.get(country);
                if (cancelRequested.get()) break;
                CountryPlan plan = source.plan(country);
                if (plan.error() != null) {
                    appendLog("❌ " + country + ": " + plan.error());
                    continue;
                }
                List<LeagueSeason> seasons = plan.seasons().stream()
                        .filter(s -> leagues.isEmpty() || leagues.contains(s.leagueRef()))
                        .toList();
                if (latestOnly) seasons = recentPerLeague(seasons);
                appendLog("🔎 " + plan.country() + ": " + (leagues.isEmpty() ? plan.selectedLeagues()
                        : seasons.stream().map(LeagueSeason::league).distinct().toList()) + " - sezonów: " + seasons.size());
                tasks.addAll(seasons);
            }
            update(s -> s.withTotal(tasks.size()));

            for (LeagueSeason season : tasks) {
                if (cancelRequested.get()) break;
                update(s -> s.withCurrent(season.describe()));
                try {
                    ResultsSource.ScrapeResult r = source.scrape(season, pool, cancelRequested::get);
                    appendLog("✅ " + season.describe() + " " + r.stages() + " - nowe: " + r.saved()
                            + ", pominięte: " + r.skipped() + (r.failed() > 0 ? ", błędy: " + r.failed() : ""));
                    update(s -> s.withSeasonDone(r.saved(), r.failed()));
                } catch (Exception e) {
                    appendLog("❌ " + season.describe() + ": " + e.getMessage());
                    update(s -> s.withSeasonDone(0, 1));
                }
            }
            appendLog(cancelRequested.get() ? "⏹ Zatrzymano" : "🏁 Zakończono");
        } catch (Exception e) {
            log.error("Scraping przerwany", e);
            appendLog("❌ " + e.getMessage());
        } finally {
            pool.shutdownNow();
            update(JobStatus::finished);
        }
    }

    /**
     * Dwa ostatnie sezony każdej ligi: API potrafi wystawić przyszły sezon (jeszcze bez meczów),
     * zanim skończy się bieżący - sam najnowszy pominąłby wtedy świeże wyniki.
     */
    private static List<LeagueSeason> recentPerLeague(List<LeagueSeason> seasons) {
        java.util.Map<String, Integer> latest = new java.util.HashMap<>();
        seasons.forEach(s -> latest.merge(s.leagueRef(), s.startYear(), Math::max));
        return seasons.stream().filter(s -> s.startYear() >= latest.get(s.leagueRef()) - 1).toList();
    }

    private synchronized void update(java.util.function.UnaryOperator<JobStatus> fn) {
        status = fn.apply(status);
    }

    private synchronized void appendLog(String line) {
        log.info(line);
        LinkedList<String> lines = new LinkedList<>(status.log());
        lines.addFirst(LocalDateTime.now().toLocalTime().withNano(0) + "  " + line);
        while (lines.size() > LOG_LIMIT) lines.removeLast();
        status = status.withLog(lines);
    }

    @PreDestroy
    void shutdown() {
        cancelRequested.set(true);
        runner.shutdownNow();
    }

    public record JobStatus(boolean running, LocalDateTime startedAt, LocalDateTime finishedAt, String current,
                            int seasonsTotal, int seasonsDone, int matchesSaved, int errors, List<String> log) {

        static JobStatus idle() {
            return new JobStatus(false, null, null, null, 0, 0, 0, 0, List.of());
        }

        JobStatus withTotal(int total) {
            return new JobStatus(running, startedAt, finishedAt, current, total, seasonsDone, matchesSaved, errors, log);
        }

        JobStatus withCurrent(String c) {
            return new JobStatus(running, startedAt, finishedAt, c, seasonsTotal, seasonsDone, matchesSaved, errors, log);
        }

        JobStatus withSeasonDone(int saved, int failed) {
            return new JobStatus(running, startedAt, finishedAt, current, seasonsTotal, seasonsDone + 1,
                    matchesSaved + saved, errors + failed, log);
        }

        JobStatus withLog(List<String> l) {
            return new JobStatus(running, startedAt, finishedAt, current, seasonsTotal, seasonsDone, matchesSaved, errors, l);
        }

        JobStatus finished() {
            return new JobStatus(false, startedAt, LocalDateTime.now(), null, seasonsTotal, seasonsDone, matchesSaved, errors, log);
        }
    }
}
