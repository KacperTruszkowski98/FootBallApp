package com.example.apifootball;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.apifootball.ApiFootballNames.*;

/**
 * Terminarz dnia (bez kursów 1X2 - jedno zapytanie na dzień) i kursy HT/FT pojedynczego meczu.
 * Oba wyniki są cache'owane, żeby odświeżanie zakładki "Lamaki" nie zjadało limitu zapytań.
 */
@Component
@RequiredArgsConstructor
public class ApiFootballFixtures {

    private static final Duration FIXTURES_TTL = Duration.ofMinutes(30);
    private static final Duration ODDS_TTL = Duration.ofHours(1);
    private static final int HTFT_BET = 7;
    private static final int GOALS_BATCH = 20;

    /** Nasze oznaczenia HT/FT -> wartości zakładu "HT/FT Double" w API. */
    private static final Map<String, String> HTFT_VALUES = Map.of(
            "1/1", "Home/Home", "1/X", "Home/Draw", "1/2", "Home/Away",
            "X/1", "Draw/Home", "X/X", "Draw/Draw", "X/2", "Draw/Away",
            "2/1", "Away/Home", "2/X", "Away/Draw", "2/2", "Away/Away");

    private final ApiFootballClient client;
    private final Map<LocalDate, Cached<List<Fixture>>> fixtures = new ConcurrentHashMap<>();
    private final Map<String, Cached<Map<String, BestOdd>>> odds = new ConcurrentHashMap<>();

    /** Wszystkie mecze dnia (czas polski), z nazwami kraju i ligi w tej samej postaci co w bazie. */
    public List<Fixture> byDate(LocalDate date) throws Exception {
        Cached<List<Fixture>> cached = fixtures.get(date);
        if (cached != null && cached.fresh(FIXTURES_TTL)) return cached.value();

        List<Fixture> list = new ArrayList<>();
        for (JsonNode f : client.get("/fixtures", Map.of("date", date.toString(), "timezone", LOCAL_ZONE.getId()))) {
            list.add(new Fixture(
                    externalId(f),
                    country(f.path("league").path("country").asText()),
                    league(f.path("league").path("name").asText()),
                    kickoff(f),
                    f.path("fixture").path("status").path("short").asText(),
                    f.path("teams").path("home").path("name").asText(),
                    f.path("teams").path("away").path("name").asText()));
        }
        fixtures.put(date, new Cached<>(Instant.now(), list));
        return list;
    }

    /** Najwyższy kurs każdego zdarzenia HT/FT spośród bukmacherów w API (pusta mapa, gdy kursów jeszcze nie wystawiono). */
    public Map<String, BestOdd> htFtOdds(String externalId) throws Exception {
        Cached<Map<String, BestOdd>> cached = odds.get(externalId);
        if (cached != null && cached.fresh(ODDS_TTL)) return cached.value();

        Map<String, String> byApiValue = new HashMap<>();
        HTFT_VALUES.forEach((ours, api) -> byApiValue.put(api, ours));

        Map<String, BestOdd> best = new HashMap<>();
        String fixtureId = externalId.substring(externalId.indexOf(':') + 1);
        for (JsonNode item : client.get("/odds", Map.of("fixture", fixtureId, "bet", HTFT_BET))) {
            for (JsonNode bookmaker : item.path("bookmakers")) {
                for (JsonNode bet : bookmaker.path("bets")) {
                    if (bet.path("id").asInt() != HTFT_BET) continue;
                    for (JsonNode v : bet.path("values")) {
                        String event = byApiValue.get(v.path("value").asText());
                        double odd = v.path("odd").asDouble();
                        if (event != null && odd > (best.containsKey(event) ? best.get(event).odd() : 0)) {
                            best.put(event, new BestOdd(odd, bookmaker.path("name").asText()));
                        }
                    }
                }
            }
        }
        odds.put(externalId, new Cached<>(Instant.now(), best));
        return best;
    }

    /**
     * Kolejność goli w 90 minutach dla zakończonych meczów: "H"/"A" po kolei (gol samobójczy API przypisuje drużynie,
     * której się liczy). Gdy liczba goli z wydarzeń nie zgadza się z wynikiem - "?". Po 20 meczów na zapytanie.
     */
    public Map<String, String> goalOrders(Collection<String> externalIds) throws Exception {
        Map<String, String> result = new HashMap<>();
        List<String> ids = externalIds.stream().map(id -> id.substring(id.indexOf(':') + 1)).toList();
        for (int i = 0; i < ids.size(); i += GOALS_BATCH) {
            String batch = String.join("-", ids.subList(i, Math.min(i + GOALS_BATCH, ids.size())));
            for (JsonNode f : client.get("/fixtures", Map.of("ids", batch))) {
                String home = f.path("teams").path("home").path("name").asText();
                StringBuilder order = new StringBuilder();
                for (JsonNode e : f.path("events")) {
                    if (!"Goal".equals(e.path("type").asText()) || "Missed Penalty".equals(e.path("detail").asText())
                            || e.path("time").path("elapsed").asInt() > 90) continue;
                    order.append(home.equals(e.path("team").path("name").asText()) ? 'H' : 'A');
                }
                Integer ftHome = intOrNull(f.path("score").path("fulltime").path("home"));
                Integer ftAway = intOrNull(f.path("score").path("fulltime").path("away"));
                boolean consistent = ftHome != null && ftAway != null
                        && order.chars().filter(c -> c == 'H').count() == ftHome
                        && order.chars().filter(c -> c == 'A').count() == ftAway;
                result.put(externalId(f), consistent ? order.toString() : "?");
            }
        }
        return result;
    }

    /** status = skrót statusu z API (NS, 1H, FT, PST...). */
    public record Fixture(String externalId, String country, String league, LocalDateTime kickoff, String status,
                          String home, String away) {
    }

    public record BestOdd(double odd, String bookmaker) {
    }

    private record Cached<T>(Instant at, T value) {
        boolean fresh(Duration ttl) {
            return at.plus(ttl).isAfter(Instant.now());
        }
    }
}
