package com.example.scraper;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Konfiguracja automatycznego scrapera (sekcja "scraper" w application.yaml).
 */
@ConfigurationProperties(prefix = "scraper")
public record ScraperProperties(
        String source,
        ApiFootball apiFootball,
        Integer defaultTopLeagues,
        Integer threads,
        Integer requestDelayMs,
        List<String> excludedLeaguePatterns,
        List<CountryConfig> countries
) {

    public ScraperProperties {
        if (source == null || source.isBlank()) source = "api-football";
        if (apiFootball == null) apiFootball = new ApiFootball(null, null, null, null);
        if (defaultTopLeagues == null) defaultTopLeagues = 2;
        if (threads == null) threads = 4;
        if (requestDelayMs == null) requestDelayMs = 250;
        if (excludedLeaguePatterns == null) excludedLeaguePatterns = List.of();
        if (countries == null) countries = List.of();
    }

    public boolean isBetExplorer() {
        return "betexplorer".equalsIgnoreCase(source);
    }

    /**
     * Ustawienia API-Football (https://www.api-football.com).
     *
     * @param key            klucz z dashboard.api-football.com -> Account -> My Access
     * @param requestDelayMs minimalny odstęp między zapytaniami (limit Pro: 300/min)
     */
    public record ApiFootball(String key, String baseUrl, Integer requestDelayMs, List<ApiCountry> countries) {
        public ApiFootball {
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://v3.football.api-sports.io";
            if (requestDelayMs == null) requestDelayMs = 220;
            if (countries == null) countries = List.of();
        }
    }

    /**
     * @param slug     dowolny identyfikator kraju (używany w UI i przy starcie scrapingu)
     * @param name     nazwa kraju do wyświetlenia
     * @param fromYear pierwszy sezon (rok startu, 2024/2025 -> 2024)
     * @param leagues  id lig z API-Football (GET /leagues?country=...), od najwyższego poziomu
     * @param names    nazwy lig w tej samej kolejności (do listy w UI - bez odpytywania API)
     * @param tiers    poziom rozgrywek każdej ligi; brak = kolejność na liście (1, 2, ...)
     */
    public record ApiCountry(String slug, String name, Integer fromYear, Integer toYear, List<Integer> leagues,
                             List<String> names, List<Integer> tiers) {
        public ApiCountry {
            if (leagues == null) leagues = List.of();
            if (names == null) names = List.of();
            if (tiers == null) tiers = List.of();
        }

        public String leagueName(int index) {
            return index < names.size() ? names.get(index) : String.valueOf(leagues.get(index));
        }

        public int tier(int index) {
            return index < tiers.size() ? tiers.get(index) : index + 1;
        }
    }

    /**
     * Kraj dla źródła BetExplorer.
     *
     * @param slug     slug kraju z URL BetExplorera, np. "poland" dla /football/poland/
     * @param fromYear pierwszy rok startu sezonu (2024/2025 -> 2024); brakujące lata są po prostu pomijane
     * @param toYear   opcjonalnie ostatni rok startu sezonu
     * @param top      ile najwyższych lig wziąć automatycznie (nadpisuje defaultTopLeagues)
     * @param leagues  opcjonalnie dokładne nazwy lig z BetExplorera - wtedy auto-wybór jest pomijany
     */
    public record CountryConfig(String slug, Integer fromYear, Integer toYear, Integer top, List<String> leagues) {
    }
}
