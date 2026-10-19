package com.example.scraper;

import com.example.source.ResultsSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

@Component
@RequiredArgsConstructor
public class BetExplorerResultsSource implements ResultsSource {

    private final LeagueDiscoveryService discovery;
    private final SeasonScraper seasonScraper;
    private final ScraperProperties properties;

    @Override
    public String name() {
        return "betexplorer";
    }

    @Override
    public List<ConfiguredCountry> countries() {
        return properties.countries().stream()
                .map(c -> new ConfiguredCountry(c.slug(), c.slug(), c.fromYear(), c.leagues() == null ? List.of()
                        : c.leagues().stream().map(l -> new ConfiguredLeague(l, l, null)).toList()))
                .toList();
    }

    @Override
    public CountryPlan plan(String slug) {
        return discovery.findConfig(slug)
                .map(discovery::plan)
                .orElseThrow(() -> new IllegalArgumentException("Brak kraju w konfiguracji: " + slug));
    }

    @Override
    public ScrapeResult scrape(LeagueSeason season, ExecutorService executor, BooleanSupplier cancelled) throws Exception {
        return seasonScraper.scrape(season, executor, cancelled);
    }
}
