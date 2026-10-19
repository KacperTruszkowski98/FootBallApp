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

    /** leagues puste = źródło samo wybiera ligi (BetExplorer bez listy lig w konfiguracji). */
    record ConfiguredCountry(String slug, String name, Integer fromYear, List<ConfiguredLeague> leagues) {
    }

    /**
     * @param ref  identyfikator ligi w źródle - ten sam co {@link com.example.scraper.LeagueSeason#leagueRef()}
     * @param tier poziom rozgrywek (1, 2) albo null, gdy nieznany
     */
    record ConfiguredLeague(String ref, String name, Integer tier) {
    }

    record ScrapeResult(int saved, int skipped, int failed, List<String> stages) {
    }
}
