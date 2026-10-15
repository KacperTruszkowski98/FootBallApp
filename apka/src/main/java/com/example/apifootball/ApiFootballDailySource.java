package com.example.apifootball;

import com.example.daily.DailyMatch;
import com.example.scraper.ScraperProperties;
import com.example.source.DailySource;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.apifootball.ApiFootballNames.*;

/**
 * Mecze dnia z API-Football: jedno zapytanie /fixtures?date=..., plus kursy 1X2 dla lig z konfiguracji.
 * Wynik jest cache'owany kilka minut, żeby przełączanie filtrów na froncie nie zjadało limitu zapytań.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiFootballDailySource implements DailySource {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int MATCH_WINNER_BET = 1;
    private static final int PREFERRED_BOOKMAKER = 8; // Bet365
    private static final Set<String> NOT_STARTED = Set.of("TBD", "NS");
    private static final Set<String> FINISHED = Set.of("FT", "AET", "PEN");

    private final ApiFootballClient client;
    private final ScraperProperties properties;
    private final Map<LocalDate, Cached> cache = new ConcurrentHashMap<>();

    @Override
    public List<DailyMatch> fetch(LocalDate date) throws Exception {
        Cached cached = cache.get(date);
        if (cached != null && cached.at().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.matches();
        }

        Map<Integer, String> trackedLeagues = new HashMap<>();
        properties.apiFootball().countries().forEach(c -> c.leagues().forEach(id -> trackedLeagues.put(id, c.slug())));

        JsonNode fixtures = client.get("/fixtures", Map.of("date", date.toString(), "timezone", LOCAL_ZONE.getId()));
        Map<String, double[]> odds = fetchOdds(date, fixtures, trackedLeagues.keySet());

        List<DailyMatch> matches = new ArrayList<>();
        for (JsonNode f : fixtures) {
            int leagueId = f.path("league").path("id").asInt();
            String country = country(f.path("league").path("country").asText());
            String externalId = externalId(f);
            double[] o = odds.get(externalId);
            Integer gh = intOrNull(f.path("goals").path("home"));
            Integer ga = intOrNull(f.path("goals").path("away"));

            matches.add(new DailyMatch(
                    externalId,
                    trackedLeagues.getOrDefault(leagueId, country == null ? null : country.toLowerCase().replace(' ', '-')),
                    country,
                    league(f.path("league").path("name").asText()),
                    null,
                    kickoff(f),
                    status(f.path("fixture").path("status")),
                    f.path("teams").path("home").path("name").asText(),
                    f.path("teams").path("away").path("name").asText(),
                    gh == null || ga == null ? null : gh + ":" + ga,
                    o == null ? null : o[0],
                    o == null ? null : o[1],
                    o == null ? null : o[2]
            ));
        }

        cache.put(date, new Cached(Instant.now(), matches));
        return matches;
    }

    /** Kursy tylko dla lig z konfiguracji - jedno (stronicowane) zapytanie na ligę grającą tego dnia. */
    private Map<String, double[]> fetchOdds(LocalDate date, JsonNode fixtures, Set<Integer> tracked) {
        Map<Integer, Integer> seasonByLeague = new LinkedHashMap<>();
        for (JsonNode f : fixtures) {
            int leagueId = f.path("league").path("id").asInt();
            if (tracked.contains(leagueId)) seasonByLeague.putIfAbsent(leagueId, f.path("league").path("season").asInt());
        }

        Map<String, double[]> result = new HashMap<>();
        seasonByLeague.forEach((leagueId, season) -> {
            try {
                for (JsonNode item : client.getAllPages("/odds", Map.of("league", leagueId, "season", season,
                        "date", date.toString(), "bet", MATCH_WINNER_BET))) {
                    double[] o = matchWinner(item.path("bookmakers"));
                    if (o != null) result.put(externalId(item), o);
                }
            } catch (Exception e) {
                log.warn("Brak kursów dla ligi {}: {}", leagueId, e.getMessage());
            }
        });
        return result;
    }

    private static double[] matchWinner(JsonNode bookmakers) {
        JsonNode chosen = null;
        for (JsonNode b : bookmakers) {
            if (chosen == null || b.path("id").asInt() == PREFERRED_BOOKMAKER) chosen = b;
        }
        if (chosen == null) return null;

        for (JsonNode bet : chosen.path("bets")) {
            if (bet.path("id").asInt() != MATCH_WINNER_BET) continue;
            double[] o = new double[3];
            for (JsonNode v : bet.path("values")) {
                double odd = v.path("odd").asDouble();
                switch (v.path("value").asText()) {
                    case "Home" -> o[0] = odd;
                    case "Draw" -> o[1] = odd;
                    case "Away" -> o[2] = odd;
                    default -> { }
                }
            }
            return o[0] > 0 && o[1] > 0 && o[2] > 0 ? o : null;
        }
        return null;
    }

    /** Pusty przed meczem, "FIN" po końcu, minuta w trakcie, skrót API w pozostałych przypadkach (np. PST). */
    private static String status(JsonNode status) {
        String code = status.path("short").asText();
        if (NOT_STARTED.contains(code)) return null;
        if (FINISHED.contains(code)) return "FIN";
        if (Set.of("1H", "2H", "ET", "P", "LIVE").contains(code) && !status.path("elapsed").isNull()) {
            return status.path("elapsed").asInt() + "'";
        }
        return code;
    }

    private record Cached(Instant at, List<DailyMatch> matches) {
    }
}
