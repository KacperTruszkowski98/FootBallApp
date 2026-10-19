package com.example.apifootball;

import com.example.BetExplorerService;
import com.example.MatchCalculatorService;
import com.example.MatchEntity;
import com.example.MatchRepository;
import com.example.dto.Match;
import com.example.scraper.CountryPlan;
import com.example.scraper.LeagueSeason;
import com.example.scraper.ScraperProperties;
import com.example.source.ResultsSource;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

import static com.example.apifootball.ApiFootballNames.*;

/**
 * Historia wyników z API-Football. Cały sezon ligi (z wynikiem do przerwy) to jedno zapytanie /fixtures.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiFootballResultsSource implements ResultsSource {

    /** Mecze zakończone; AET/PEN mają w score.fulltime wynik po 90 minutach. */
    private static final Set<String> FINISHED = Set.of("FT", "AET", "PEN");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final java.time.Duration LEAGUE_CACHE_TTL = java.time.Duration.ofHours(6);

    private final ApiFootballClient client;
    private final ScraperProperties properties;
    private final MatchRepository repository;
    private final MatchCalculatorService calculator;
    private final BetExplorerService betExplorerService;
    private final Map<Integer, CachedLeague> leagueCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public String name() {
        return "api-football";
    }

    @Override
    public List<ConfiguredCountry> countries() {
        return properties.apiFootball().countries().stream()
                .map(c -> new ConfiguredCountry(c.slug(), c.name() == null ? c.slug() : c.name(), c.fromYear(),
                        java.util.stream.IntStream.range(0, c.leagues().size())
                                .mapToObj(i -> new ConfiguredLeague(String.valueOf(c.leagues().get(i)), c.leagueName(i), c.tier(i)))
                                .toList()))
                .toList();
    }

    @Override
    public CountryPlan plan(String slug) {
        ScraperProperties.ApiCountry config = properties.apiFootball().countries().stream()
                .filter(c -> c.slug().equalsIgnoreCase(slug))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Brak kraju w konfiguracji: " + slug));

        int from = config.fromYear() == null ? 0 : config.fromYear();
        int to = config.toYear() == null ? Integer.MAX_VALUE : config.toYear();
        String country = slug;
        List<String> selected = new ArrayList<>();
        List<LeagueSeason> seasons = new ArrayList<>();

        try {
            for (Integer leagueId : config.leagues()) {
                JsonNode response = fetchLeague(leagueId);
                if (response.isEmpty()) {
                    log.warn("Liga {} nie istnieje w API-Football", leagueId);
                    continue;
                }
                JsonNode item = response.get(0);
                country = country(item.path("country").path("name").asText(slug));
                String apiName = item.path("league").path("name").asText();
                selected.add(apiName + " (" + leagueId + ")");

                for (JsonNode s : item.path("seasons")) {
                    int year = s.path("year").asInt();
                    if (year < from || year > to) continue;
                    seasons.add(new LeagueSeason(config.slug(), country, league(apiName), String.valueOf(leagueId),
                            seasonLabel(s), year, leagueId + ":" + year));
                }
            }
        } catch (Exception e) {
            log.error("Nie udało się odczytać lig dla {}", slug, e);
            return new CountryPlan(slug, country, selected, List.of(), List.of(), e.getMessage());
        }

        seasons.sort(Comparator.comparing(LeagueSeason::leagueRef).thenComparing(LeagueSeason::startYear));
        return new CountryPlan(slug, country, selected, List.of(), seasons, null);
    }

    @Override
    public ScrapeResult scrape(LeagueSeason season, ExecutorService executor, BooleanSupplier cancelled) throws Exception {
        String[] ref = season.path().split(":");
        JsonNode fixtures = client.get("/fixtures", Map.of("league", ref[0], "season", ref[1]));

        List<JsonNode> finished = new ArrayList<>();
        fixtures.forEach(f -> {
            if (FINISHED.contains(f.path("fixture").path("status").path("short").asText())) finished.add(f);
        });

        Set<String> existing = finished.isEmpty() ? Set.of()
                : repository.findExistingExternalIds(finished.stream().map(ApiFootballNames::externalId).toList());

        Map<String, String> stageById = StageNormalizer.normalize(fixtures);
        List<MatchEntity> entities = new ArrayList<>();
        Set<String> stages = new LinkedHashSet<>();
        int skipped = fixtures.size() - finished.size();

        for (JsonNode f : finished) {
            String stage = stageById.get(externalId(f));
            stages.add(stage);
            if (existing.contains(externalId(f))) {
                skipped++;
                continue;
            }
            entities.add(toEntity(season, f, stage));
        }

        repository.saveAll(entities);
        return new ScrapeResult(entities.size(), skipped, 0, List.copyOf(stages));
    }

    private MatchEntity toEntity(LeagueSeason season, JsonNode f, String stage) {
        String round = f.path("league").path("round").asText();
        JsonNode score = f.path("score");
        Integer htHome = intOrNull(score.path("halftime").path("home"));
        Integer htAway = intOrNull(score.path("halftime").path("away"));
        Integer ftHome = intOrNull(score.path("fulltime").path("home"));
        Integer ftAway = intOrNull(score.path("fulltime").path("away"));
        if (ftHome == null || ftAway == null) {
            ftHome = intOrNull(f.path("goals").path("home"));
            ftAway = intOrNull(f.path("goals").path("away"));
        }

        Match match = new Match();
        match.setHome(f.path("teams").path("home").path("name").asText());
        match.setAway(f.path("teams").path("away").path("name").asText());
        match.setRoundName(round);
        match.setCurrentRound(roundNumber(round));
        match.setStartSeasonYear(season.startYear());
        match.setCountry(season.country());
        match.setScore(ftHome + ":" + ftAway);

        LocalDateTime kickoff = kickoff(f);
        if (kickoff != null) match.setDate(kickoff.format(DATE));

        if (htHome != null && htAway != null && ftHome != null && ftAway != null) {
            calculator.applyScores(match, htHome, htAway, ftHome, ftAway);
        } else if (ftHome != null && ftAway != null) {
            // brak wyniku do przerwy w danych - bez HT/FT
            match.setScoreFT(ftHome + "-" + ftAway);
        }

        MatchEntity entity = betExplorerService.mapToEntity(match);
        entity.setExternalId(externalId(f));
        entity.setLeague(season.league());
        entity.setStage(stage);
        entity.setSeasonLabel(season.seasonLabel());
        entity.setKickoff(kickoff);
        return entity;
    }

    /** Dane ligi (z listą sezonów) - cache'owane, żeby kolejne uruchomienia nie odpytywały wszystkich lig od nowa. */
    private JsonNode fetchLeague(int leagueId) throws Exception {
        CachedLeague cached = leagueCache.get(leagueId);
        if (cached != null && cached.at().plus(LEAGUE_CACHE_TTL).isAfter(java.time.Instant.now())) {
            return cached.response();
        }
        JsonNode response = client.get("/leagues", Map.of("id", leagueId));
        leagueCache.put(leagueId, new CachedLeague(java.time.Instant.now(), response));
        return response;
    }

    private record CachedLeague(java.time.Instant at, JsonNode response) {
    }

    /** 2024 z sezonem sierpień-maj -> "2024/2025", sezon w jednym roku kalendarzowym -> "2024". */
    private static String seasonLabel(JsonNode season) {
        int year = season.path("year").asInt();
        try {
            int startYear = LocalDate.parse(season.path("start").asText()).getYear();
            int endYear = LocalDate.parse(season.path("end").asText()).getYear();
            return startYear == endYear ? String.valueOf(year) : year + "/" + (year + 1);
        } catch (Exception e) {
            return String.valueOf(year);
        }
    }
}
