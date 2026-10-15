package com.example.daily;

import java.time.LocalDateTime;

/**
 * Mecz z listy dnia na BetExplorerze. Wynik jest null, dopóki mecz się nie zaczął.
 * status: pusty dla meczu przed startem, "FIN" po końcu, minuta w trakcie, "POSTP." itp.
 */
public record DailyMatch(String externalId, String countrySlug, String country, String league, String path,
                         LocalDateTime kickoff, String status, String home, String away, String score,
                         Double odd1, Double oddX, Double odd2) {
}
