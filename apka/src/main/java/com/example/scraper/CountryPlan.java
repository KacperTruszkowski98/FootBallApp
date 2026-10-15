package com.example.scraper;

import java.util.List;

/**
 * Co scraper zamierza pobrać dla kraju: wybrane ligi i znalezione sezony (lata bez sezonu po prostu nie występują).
 */
public record CountryPlan(String slug, String country, List<String> selectedLeagues,
                          List<String> availableLeagues, List<LeagueSeason> seasons, String error) {
}
