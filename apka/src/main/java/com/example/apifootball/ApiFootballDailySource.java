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
 * Mecze dnia z API-Football. Lista meczów to jedno zapytanie /fixtures?date=... (odświeżane co kilka minut tylko dla
 * dzisiejszego dnia). Kursy 1X2 to po kilka zapytań na ligę, więc pobierane są osobno ({@link #odds}) i tylko raz -
 * kolejne wywołania dociągają wyłącznie ligi, których jeszcze nie ma (np. po błędzie 429 albo gdy kursów nie było).
 * Zakończone mecze z lig z konfiguracji trafiają do bazy przez {@link FinishedMatchesRecorder}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiFootballDailySource implements DailySource {

    private static final Duration TODAY_TTL = Duration.ofMinutes(5);
    private static final Duration FUTURE_TTL = Duration.ofMinutes(30);
    private static final Duration PAST_TTL = Duration.ofHours(12);
    /** Liga bez kursów (bukmacherzy jeszcze ich nie wystawili) - ponowna próba najwcześniej po tym czasie. */
    private static final Duration EMPTY_ODDS_RETRY = Duration.ofHours(2);
    private static final int MATCH_WINNER_BET = 1;
    private static final int PREFERRED_BOOKMAKER = 8; // Bet365
    private static final Set<String> NOT_STARTED = Set.of("TBD", "NS");
    private static final Set<String> FINISHED = Set.of("FT", "AET", "PEN");

    private final ApiFootballClient client;
    private final ScraperProperties properties;
    private final FinishedMatchesRecorder recorder;
    private final Map<LocalDate, Cached> cache = new ConcurrentHashMap<>();
    private final Map<LocalDate, DayOdds> odds = new ConcurrentHashMap<>();

    @Override
    public List<DailyMatch> fetch(LocalDate date) throws Exception {
        Cached cached = cache.get(date);
        if (cached != null && cached.at().plus(ttl(date)).isAfter(Instant.now())) {
            return withOdds(date, cached.matches());
        }

        Map<Integer, String> trackedLeagues = trackedLeagues();
        JsonNode fixtures = client.get("/fixtures", Map.of("date", date.toString(), "timezone", LOCAL_ZONE.getId()));

        List<DailyMatch> matches = new ArrayList<>();
        for (JsonNode f : fixtures) {
            int leagueId = f.path("league").path("id").asInt();
            String country = country(f.path("league").path("country").asText());
            Integer gh = intOrNull(f.path("goals").path("home"));
            Integer ga = intOrNull(f.path("goals").path("away"));

            matches.add(new DailyMatch(
                    externalId(f),
                    trackedLeagues.getOrDefault(leagueId, country == null ? null : country.toLowerCase().replace(' ', '-')),
                    country,
                    league(f.path("league").path("name").asText()),
                    null,
                    kickoff(f),
                    status(f.path("fixture").path("status")),
                    f.path("teams").path("home").path("name").asText(),
                    f.path("teams").path("away").path("name").asText(),
                    gh == null || ga == null ? null : gh + ":" + ga,
                    null, null, null
            ));
        }

        cache.put(date, new Cached(Instant.now(), matches, fixtures));
        if (!date.isAfter(LocalDate.now(LOCAL_ZONE))) recorder.record(fixtures, trackedLeagues.keySet());
        return withOdds(date, matches);
    }

    /**
     * Kursy 1X2 dla lig z konfiguracji - jedno (stronicowane) zapytanie na ligę grającą tego dnia, każda liga tylko raz.
     * Równoległe wywołania dla tego samego dnia czekają na siebie zamiast pytać API drugi raz.
     */
    @Override
    public Map<String, double[]> odds(LocalDate date) throws Exception {
        DayOdds day = odds.computeIfAbsent(date, d -> new DayOdds());
        synchronized (day) {
            Cached cached = cache.get(date);
            JsonNode fixtures = cached != null ? cached.raw() : null;
            if (fixtures == null) {
                fetch(date);
                fixtures = cache.get(date).raw();
            }

            Set<Integer> tracked = trackedLeagues().keySet();
            Map<Integer, Integer> seasonByLeague = new LinkedHashMap<>();
            for (JsonNode f : fixtures) {
                int leagueId = f.path("league").path("id").asInt();
                if (tracked.contains(leagueId)) seasonByLeague.putIfAbsent(leagueId, f.path("league").path("season").asInt());
            }

            boolean past = date.isBefore(LocalDate.now(LOCAL_ZONE));
            seasonByLeague.forEach((leagueId, season) -> {
                if (day.done.contains(leagueId)) return;
                Instant tried = day.emptyAt.get(leagueId);
                if (tried != null && tried.plus(EMPTY_ODDS_RETRY).isAfter(Instant.now())) return;
                try {
                    int found = 0;
                    for (JsonNode item : client.getAllPages("/odds", Map.of("league", leagueId, "season", season,
                            "date", date.toString(), "bet", MATCH_WINNER_BET))) {
                        double[] o = matchWinner(item.path("bookmakers"));
                        if (o != null) {
                            day.byMatch.put(externalId(item), o);
                            found++;
                        }
                    }
                    if (found > 0 || past) day.done.add(leagueId);
                    else day.emptyAt.put(leagueId, Instant.now());
                } catch (Exception e) {
                    // np. 429 - liga zostaje do pobrania przy następnym wejściu
                    log.warn("Brak kursów dla ligi {}: {}", leagueId, e.getMessage());
                }
            });
            return Map.copyOf(day.byMatch);
        }
    }

    private List<DailyMatch> withOdds(LocalDate date, List<DailyMatch> matches) {
        DayOdds day = odds.get(date);
        if (day == null || day.byMatch.isEmpty()) return matches;
        return matches.stream().map(m -> {
            double[] o = day.byMatch.get(m.externalId());
            return o == null ? m : new DailyMatch(m.externalId(), m.countrySlug(), m.country(), m.league(), m.path(),
                    m.kickoff(), m.status(), m.home(), m.away(), m.score(), o[0], o[1], o[2]);
        }).toList();
    }

    private Map<Integer, String> trackedLeagues() {
        Map<Integer, String> tracked = new HashMap<>();
        properties.apiFootball().countries().forEach(c -> c.leagues().forEach(id -> tracked.put(id, c.slug())));
        return tracked;
    }

    private static Duration ttl(LocalDate date) {
        LocalDate today = LocalDate.now(LOCAL_ZONE);
        return date.isBefore(today) ? PAST_TTL : date.isAfter(today) ? FUTURE_TTL : TODAY_TTL;
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

    private record Cached(Instant at, List<DailyMatch> matches, JsonNode raw) {
    }

    private static final class DayOdds {
        final Map<String, double[]> byMatch = new ConcurrentHashMap<>();
        final Set<Integer> done = ConcurrentHashMap.newKeySet();
        final Map<Integer, Instant> emptyAt = new ConcurrentHashMap<>();
    }
}
