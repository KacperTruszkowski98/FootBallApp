package com.example.source;

import com.example.scraper.CountryPlan;
import com.example.scraper.LeagueSeason;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/**
 * Źródło historycznych wyników (BetExplorer albo API-Football) - wybierane przez scraper.source.
 */
public interface ResultsSource {

    String name();

    /** Kraje z konfiguracji aktywnego źródła. */
    List<ConfiguredCountry> countries();

    /** Ligi i sezony do pobrania dla kraju (bez zapisu do bazy). */
    CountryPlan plan(String slug);

    /** Pobiera i zapisuje mecze jednego sezonu ligi; mecze już zapisane są pomijane. */
    ScrapeResult scrape(LeagueSeason season, ExecutorService executor, BooleanSupplier cancelled) throws Exception;

    record ConfiguredCountry(String slug, Integer fromYear, List<String> leagues) {
    }

    record ScrapeResult(int saved, int skipped, int failed, List<String> stages) {
    }
}
