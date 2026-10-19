package com.example.source;

import com.example.daily.DailyMatch;

import java.time.LocalDate;
import java.util.List;

/**
 * Lista meczów wybranego dnia (z wynikami, jeśli już są) z aktywnego źródła.
 */
public interface DailySource {

    List<DailyMatch> fetch(LocalDate date) throws Exception;

    /**
     * Kursy 1X2 meczów dnia (externalId -> [1, X, 2]) - osobno, bo w API-Football to po kilka zapytań na ligę
     * i trwa nawet minutę. Źródła, które dają kursy razem z meczami (BetExplorer), zwracają pustą mapę.
     */
    default java.util.Map<String, double[]> odds(LocalDate date) throws Exception {
        return java.util.Map.of();
    }
}
