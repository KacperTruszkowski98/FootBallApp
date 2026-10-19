package com.example.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.List;

/**
 * Ustawienia zakładki "Lamaki" (sekcja "strategy" w application.yaml).
 *
 * @param events     zdarzenia HT/FT, na które gramy (domyślnie lamaki 1/2 i 2/1)
 * @param minCommon  ile razy para musiała już mieć to zdarzenie, żeby mecz się kwalifikował
 * @param historyFrom od kiedy liczymy wspólne zdarzenia par
 * @param target     zysk na czysto z jednej serii (zł)
 * @param cap        limit włożonych pieniędzy w jedną serię (zł)
 * @param tax        mnożnik po podatku od stawki (0.88 = 12% podatku)
 * @param daysAhead  na ile dni do przodu szukać meczów
 * @param daysBack   ile dni wstecz pokazywać rozstrzygnięte typy
 */
@ConfigurationProperties(prefix = "strategy")
public record StrategyProperties(List<String> events, Integer minCommon, LocalDate historyFrom, Double target,
                                 Double cap, Double tax, Integer daysAhead, Integer daysBack, List<League> leagues) {

    public StrategyProperties {
        if (events == null || events.isEmpty()) events = List.of("1/2", "2/1");
        if (minCommon == null) minCommon = 2;
        if (historyFrom == null) historyFrom = LocalDate.of(2022, 1, 1);
        if (target == null) target = 100.0;
        if (cap == null) cap = 3500.0;
        if (tax == null) tax = 0.88;
        if (daysAhead == null) daysAhead = 7;
        if (daysBack == null) daysBack = 7;
        if (leagues == null) leagues = List.of();
    }

    /**
     * @param slug    kraj z api-football-leagues.yaml - do dociągania wyników
     * @param country kraj i liga dokładnie tak, jak są zapisane w bazie
     * @param name    krótka nazwa do wyświetlenia
     */
    public record League(String slug, String country, String league, String name) {
        public String label() {
            return name == null || name.isBlank() ? country + " " + league : name;
        }
    }
}
