package com.example.scraper;

/**
 * Jeden sezon jednej ligi, np. Poland / Ekstraklasa / 2024/2025.
 *
 * @param leagueRef identyfikator ligi w źródle (BetExplorer: nazwa ligi, API-Football: id ligi)
 * @param path      odnośnik do sezonu w źródle (BetExplorer: ścieżka URL, API-Football: "idLigi:rok")
 */
public record LeagueSeason(String countrySlug, String country, String league, String leagueRef,
                           String seasonLabel, int startYear, String path) {

    public String describe() {
        return country + " / " + league + " / " + seasonLabel;
    }
}
