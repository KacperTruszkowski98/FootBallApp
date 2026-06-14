package com.example;

import com.example.dto.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;

@RequiredArgsConstructor
@Service
public class BetExplorerService {


    private static final String BASE_URL = "https://www.betexplorer.com";
    private static final int THREADS = 5;
    private final MatchCalculatorService calculator;
    private final MatchRepository matchRepository;

    public List<Match> scrapeSeason(ScrapRequest request) {
        List<Match> matches = Collections.synchronizedList(new ArrayList<>());

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );

            Page page = browser.newPage();
            page.navigate(BASE_URL + request.url());
            page.waitForLoadState(LoadState.NETWORKIDLE);

            List<ElementHandle> rows = page.querySelectorAll("table.table-main tbody tr");
            String currentRound = "";

            ExecutorService executor = Executors.newFixedThreadPool(THREADS);
            List<Future<?>> futures = new ArrayList<>();

            for (ElementHandle row : rows) {
                ElementHandle th = row.querySelector("th");
                if (th != null) {
                    currentRound = th.innerText().trim();
                    continue;
                }

                ElementHandle link = row.querySelector("a.in-match");
                if (link == null) continue;

                String[] teams = link.innerText().split(" - ");
                if (teams.length != 2) continue;

                String home = teams[0].trim();
                String away = teams[1].trim();
                String matchUrl = BASE_URL + link.getAttribute("href");

                List<ElementHandle> cells = row.querySelectorAll("td");
                if (cells.size() < 6) continue;

                String score = cells.get(1).innerText().trim();
                String date = cells.get(5).innerText().trim();

                final String roundCopy = currentRound, homeCopy = home, awayCopy = away,
                        scoreCopy = score, dateCopy = date, urlCopy = matchUrl;

                futures.add(executor.submit(() -> {
                    try (Playwright pw = Playwright.create()) {
                        Browser br = pw.chromium().launch(
                                new BrowserType.LaunchOptions().setHeadless(true)
                        );

                        Match match = new Match();
                        match.setHome(homeCopy);
                        match.setAway(awayCopy);
                        match.setScore(scoreCopy);
                        match.setDate(dateCopy);
                        match.setRoundName(roundCopy);
                        match.setCurrentRound(extractRoundNumber(roundCopy));
                        match.setStartSeasonYear(request.startSeasonYear());
                        match.setCountry(request.country());

                        // 🔽 1. SCRAPING GOLI
                        List<Goal> goals = scrapeGoals(br, urlCopy);
                        match.setGoals(goals);

                        // 🔥 2. LICZENIE WSZYSTKIEGO (HT, FT, WIN, HTFT)
                        calculator.calculateMatch(match);

                        // 🔍 DEBUG
                        System.out.println(
                                "✅ DONE: " + homeCopy + " vs " + awayCopy +
                                        " | HT: " + match.getScoreHT() +
                                        " | FT: " + match.getScoreFT() +
                                        " | HTFT: " + match.getHTFT() +
                                        " | WIN HT: " + match.getWinHT() +
                                        " | WIN FT: " + match.getWinFT()
                        );

                        matches.add(match);

                        br.close();
                    } catch (Exception e) {
                        System.err.println("⚠️ Thread error: " + e.getMessage());
                    }
                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    System.err.println("⚠️ Future error: " + e.getMessage());
                }
            }

            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.MINUTES);
            browser.close();

        } catch (Exception e) {
            System.err.println("❌ BŁĄD GŁÓWNY: " + e.getMessage());
        }

        List<MatchEntity> entities = matches.stream()
                .map(this::mapToEntity)
                .toList();

        matchRepository.saveAll(entities);

        return matches;
    }

    private List<Goal> scrapeGoals(Browser browser, String url) {
        List<Goal> goals = new ArrayList<>();
        Page page = browser.newPage();

        try {
            page.navigate(url);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(90000));

            List<ElementHandle> shooterItems = page.querySelectorAll("ul.list-details--shooters li.list-details__item");
            System.out.println("🔍 ITEMÓW: " + shooterItems.size());

            for (int i = 0; i < shooterItems.size(); i++) {
                ElementHandle item = shooterItems.get(i);
                ElementHandle table = item.querySelector("table.table-main");

                if (table != null) {
                    List<ElementHandle> rows = table.querySelectorAll("tr");
                    String team = i == 0 ? "home" : "away";

                    System.out.println("\n📋 DRUŻYNA " + i + " (" + team + "):");

                    for (ElementHandle row : rows) {
                        List<ElementHandle> cells = row.querySelectorAll("td");
                        System.out.println("  Wiersz: " + cells.size() + " komórek");

                        for (int j = 0; j < cells.size(); j++) {
                            System.out.println("    TD[" + j + "]: '" + cells.get(j).innerText().trim() + "'");
                        }

                        // 🔥 INTELIGENTNE WYKRYWANIE MINUTY I STRELCÓW
                        String minute = "";
                        String scorer = "";

                        // Szukaj minuty (cyfry + kropka/kropka+)
                        for (ElementHandle cell : cells) {
                            String text = cell.innerText().trim();
                            if (text.matches("\\d+(\\+\\d+)?\\.?")) {
                                minute = text;
                                break;
                            }
                        }

                        // Szukaj strzelca (tekst po minucie, długi)
                        for (ElementHandle cell : cells) {
                            String text = cell.innerText().trim();
                            if (text.length() > 2 && !text.matches("\\d+(\\+\\d+)?\\.?") &&
                                    !text.matches("\\d+:\\d+")) {
                                scorer = text;
                                break;
                            }
                        }

                        if (!minute.isEmpty() && !scorer.isEmpty()) {
                            Goal goal = new Goal();
                            goal.setMinute(minute);
                            goal.setScorer(scorer);
                            goal.setTeam(team);
                            goals.add(goal);

                            String emoji = "home".equals(team) ? "🏠" : "✈️";
                            System.out.println("    " + emoji + " OK! [" + minute + "] " + scorer);
                        }
                    }
                }
            }

            System.out.println("✅ RAZEM: " + goals.size() + " goli");

        } catch (Exception e) {
            System.err.println("❌ BŁĄD: " + e.getMessage());
        } finally {
            page.close();
        }

        return goals;
    }

    private Integer extractRoundNumber(String text) {
        if (text == null) return null;

        String number = text.replaceAll("[^0-9]", "");
        if (number.isEmpty()) return null;

        return Integer.parseInt(number);
    }

    private String mapResult(MatchResult result) {
        if (result == null) return "?";

        return switch (result) {
            case HOME -> "1";
            case AWAY -> "2";
            case DRAW -> "X";
            default -> "?";
        };
    }

    private String calculateHTFT(MatchResult ht, MatchResult ft) {
        return mapResult(ht) + "/" + mapResult(ft);
    }

    private MatchResult resolveWinner(int homeGoals, int awayGoals) {
        if (homeGoals > awayGoals) {
            return MatchResult.HOME;
        }
        if (awayGoals > homeGoals) {
            return MatchResult.AWAY;
        }
        return MatchResult.DRAW;
    }


    public MatchEntity mapToEntity(Match dto) {
        MatchEntity entity = new MatchEntity();

        entity.setHome(dto.getHome());
        entity.setAway(dto.getAway());
        entity.setDate(dto.getDate());
        entity.setScore(dto.getScore());
        entity.setCurrentRound(dto.getCurrentRound());
        entity.setRoundName(dto.getRoundName());
        entity.setHTFT(dto.getHTFT());
        entity.setStartSeasonYear(dto.getStartSeasonYear());

        entity.setScoreHT(dto.getScoreHT());
        entity.setScoreFT(dto.getScoreFT());

        entity.setWinHT(dto.getWinHT());
        entity.setWinFT(dto.getWinFT());
        entity.setCountry(dto.getCountry());

        return entity;
    }

    public ResultDto recalculate(ResultRequest request) {

        List<MatchEntity> matches = matchRepository
                .findByCountryAndStartSeasonYear(
                        request.country(),
                        Integer.parseInt(request.season())
                );

        Map<String, List<EventOccurrenceDto>> grouped = new HashMap<>();

        for (MatchEntity match : matches) {
            String normalized = normalize(match.getHTFT());
            if (normalized == null) continue;

            EventOccurrenceDto occurrence = new EventOccurrenceDto();
            occurrence.setHome(match.getHome());
            occurrence.setAway(match.getAway());
            occurrence.setEvent(match.getHTFT());
            if(nonNull(match.getCurrentRound())) {
                occurrence.setRound(match.getCurrentRound());
            }

            grouped.computeIfAbsent(normalized, k -> new ArrayList<>())
                    .add(occurrence);
        }

        List<EventGroupDto> groups = grouped.entrySet().stream()
                .map(entry -> {

                    List<EventOccurrenceDto> sortedOccurrences = entry.getValue().stream()
                            .sorted(Comparator.comparingInt(EventOccurrenceDto::getRound))
                            .toList();

                    EventGroupDto dto = new EventGroupDto();
                    dto.setGroupName(entry.getKey());
                    dto.setOccurrences(sortedOccurrences);
                    dto.setCount(sortedOccurrences.size()); // 👈 zliczanie

                    return dto;
                })
                // opcjonalnie sortowanie grup np. po liczbie wystąpień
                .sorted(Comparator.comparingLong(EventGroupDto::getCount).reversed())
                .toList();

        ResultDto result = new ResultDto();
        result.setSeasonYear(Integer.parseInt(request.season()));
        result.setGroups(groups);

        return result;
    }

    private String normalize(String htft) {
        if (htft == null) return null;

        String val = htft.toLowerCase();

        return switch (val) {
            case "1/2", "2/1" -> "1/2 + 2/1";

//            case "1/x", "2/x" -> "1/x + 2/x";

            case "1/x", "2/x" -> null;

            case "x/1", "x/2" -> null;

            case "x/x" -> null;

            case "1/1", "2/2" -> null;

            default -> null;
        };
    }

    public List<SeasonSummaryDto> compareAllSeasons(String country) {

        List<MatchEntity> matches = matchRepository.findByCountry(country);

        Map<Integer, Map<String, Long>> grouped = matches.stream()
                .map(m -> {
                    String normalized = normalize(m.getHTFT());
                    if (normalized == null) return null;

                    return new AbstractMap.SimpleEntry<>(m.getStartSeasonYear(), normalized);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.groupingBy(
                                Map.Entry::getValue,
                                Collectors.counting()
                        )
                ));

        return grouped.entrySet().stream()
                .map(seasonEntry -> {

                    Integer season = seasonEntry.getKey();

                    List<EventGroupDto> groups = seasonEntry.getValue().entrySet().stream()
                            .map(e -> {
                                EventGroupDto dto = new EventGroupDto();
                                dto.setGroupName(e.getKey());
                                dto.setCount(e.getValue().intValue());
                                return dto;
                            })
                            .sorted(Comparator.comparingLong(EventGroupDto::getCount).reversed())
                            .toList();

                    SeasonSummaryDto dto = new SeasonSummaryDto();
                    dto.setCountry(country);
                    dto.setSeason(season);
                    dto.setGroups(groups);

                    return dto;
                })
                .sorted(Comparator.comparingInt(SeasonSummaryDto::getSeason))
                .toList();
    }

    public List<String> getCountries() {
        return matchRepository.findDistinctCountries();
    }
}