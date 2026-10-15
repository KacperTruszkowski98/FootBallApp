package com.example.scraper;

import com.example.source.ResultsSource;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
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

    public synchronized JobStatus start(Collection<String> slugs, boolean latestOnly) {
        if (status.running()) {
            throw new IllegalStateException("Scraping już trwa");
        }

        List<String> configured = source.countries().stream().map(ResultsSource.ConfiguredCountry::slug).toList();
        List<String> countries = (slugs == null || slugs.isEmpty()) ? configured : List.copyOf(slugs);
        countries.stream().filter(s -> !configured.contains(s)).findFirst().ifPresent(s -> {
            throw new IllegalArgumentException("Brak kraju w konfiguracji: " + s);
        });

        cancelRequested.set(false);
        status = new JobStatus(true, LocalDateTime.now(), null, "Wyszukiwanie lig i sezonów...",
                0, 0, 0, 0, new LinkedList<>());
        runner.submit(() -> run(countries, latestOnly));
        return status;
    }

    public void cancel() {
        cancelRequested.set(true);
        appendLog("Zażądano zatrzymania - kończę bieżący etap...");
    }

    public JobStatus status() {
        return status;
    }

    private void run(List<String> countries, boolean latestOnly) {
        ExecutorService pool = Executors.newFixedThreadPool(properties.threads());
        try {
            List<LeagueSeason> tasks = new ArrayList<>();
            appendLog("Źródło: " + source.name());
            for (String country : countries) {
                if (cancelRequested.get()) break;
                CountryPlan plan = source.plan(country);
                if (plan.error() != null) {
                    appendLog("❌ " + country + ": " + plan.error());
                    continue;
                }
                List<LeagueSeason> seasons = latestOnly ? latestPerLeague(plan.seasons()) : plan.seasons();
                appendLog("🔎 " + plan.country() + ": " + plan.selectedLeagues() + " - sezonów: " + seasons.size());
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

    private static List<LeagueSeason> latestPerLeague(List<LeagueSeason> seasons) {
        return seasons.stream()
                .collect(java.util.stream.Collectors.toMap(LeagueSeason::leagueRef, s -> s,
                        (a, b) -> a.startYear() >= b.startYear() ? a : b, java.util.LinkedHashMap::new))
                .values().stream().toList();
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
