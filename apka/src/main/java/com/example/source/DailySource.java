package com.example.source;

import com.example.daily.DailyMatch;

import java.time.LocalDate;
import java.util.List;

/**
 * Lista meczów wybranego dnia (z wynikami, jeśli już są) z aktywnego źródła.
 */
public interface DailySource {

    List<DailyMatch> fetch(LocalDate date) throws Exception;
}
