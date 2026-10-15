package com.example.source;

import com.example.apifootball.ApiFootballDailySource;
import com.example.apifootball.ApiFootballResultsSource;
import com.example.daily.DailyMatchesScraper;
import com.example.scraper.BetExplorerResultsSource;
import com.example.scraper.ScraperProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wybór aktywnego źródła danych: scraper.source = api-football (domyślnie) | betexplorer.
 */
@Configuration
public class SourceConfig {

    @Bean
    @Primary
    public ResultsSource activeResultsSource(ScraperProperties properties,
                                             BetExplorerResultsSource betExplorer,
                                             ApiFootballResultsSource apiFootball) {
        return properties.isBetExplorer() ? betExplorer : apiFootball;
    }

    @Bean
    @Primary
    public DailySource activeDailySource(ScraperProperties properties,
                                         DailyMatchesScraper betExplorer,
                                         ApiFootballDailySource apiFootball) {
        return properties.isBetExplorer() ? betExplorer : apiFootball;
    }
}
