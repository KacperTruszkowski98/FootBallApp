package com.example.apifootball;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wspólne przekształcenia danych z API-Football na nasz model (kraj, liga, etap, runda, data).
 */
final class ApiFootballNames {

    static final ZoneId LOCAL_ZONE = ZoneId.of("Europe/Warsaw");

    private static final Pattern ROUND = Pattern.compile("^(.*?)\\s*-\\s*(\\d+)$");
    private static final Pattern SPLIT_LEAGUE = Pattern.compile("\\s*-\\s*(Apertura|Clausura)$", Pattern.CASE_INSENSITIVE);

    private ApiFootballNames() {
    }

    /** "Costa-Rica" -> "Costa Rica". */
    static String country(String apiCountry) {
        return apiCountry == null ? null : apiCountry.replace('-', ' ');
    }

    /** "Division Profesional - Apertura" -> "Division Profesional" (Apertura/Clausura to w API osobne ligi). */
    static String league(String apiLeague) {
        return apiLeague == null ? null : SPLIT_LEAGUE.matcher(apiLeague).replaceFirst("");
    }

    static Integer roundNumber(String round) {
        if (round == null) return null;
        Matcher m = ROUND.matcher(round.trim());
        return m.matches() ? Integer.parseInt(m.group(2)) : null;
    }

    static LocalDateTime kickoff(JsonNode fixture) {
        String date = fixture.path("fixture").path("date").asText(null);
        if (date == null) return null;
        return OffsetDateTime.parse(date).atZoneSameInstant(LOCAL_ZONE).toLocalDateTime();
    }

    static String externalId(JsonNode fixture) {
        return "af:" + fixture.path("fixture").path("id").asText();
    }

    static Integer intOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asInt();
    }
}
