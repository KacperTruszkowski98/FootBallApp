package com.example.scraper;

import com.example.BetExplorerService;
import com.example.MatchCalculatorService;
import com.example.MatchEntity;
import com.example.MatchRepository;
import com.example.dto.Goal;
import com.example.dto.Match;
import com.example.source.ResultsSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pobiera wszystkie mecze jednego sezonu ligi, z podziałem na etapy (Apertura, Clausura, Main, Play Offs...).
 * Mecze już zapisane w bazie (po externalId) są pomijane, więc ponowne uruchomienie dociąga tylko nowe wyniki.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeasonScraper {

    private static final String DEFAULT_STAGE = "Main";
    private static final Pattern SCORE = Pattern.compile("^(\\d+):(\\d+)(?:\\s*(ET|PEN\\.?))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAIR = Pattern.compile("(\\d+):(\\d+)");
    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern MINUTE = Pattern.compile("^\\d+(\\+\\d+)?\\.?$");
    private static final ZoneOffset SITE_OFFSET = ZoneOffset.ofHours(1);
    private static final ZoneId LOCAL_ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final BetExplorerClient client;
    private final MatchRepository repository;
    private final MatchCalculatorService calculator;
    private final BetExplorerService betExplorerService;

    public ResultsSource.ScrapeResult scrape(LeagueSeason season, ExecutorService executor, BooleanSupplier cancelled) throws Exception {
        String resultsPath = season.path() + "results/";
        Document first = client.get(resultsPath);

        Map<String, String> stages = new LinkedHashMap<>();
        for (Element a : first.select("a.list-tabs__item__in[href^=?stage=]")) {
            stages.putIfAbsent(a.text().trim(), a.attr("href"));
        }

        int saved = 0, skipped = 0, failed = 0;

        if (stages.isEmpty()) {
            stages.put(DEFAULT_STAGE, null);
        }

        for (Map.Entry<String, String> stage : stages.entrySet()) {
            if (cancelled.getAsBoolean()) break;

            List<MatchRow> rows;
            try {
                rows = parseRows(stage.getValue() == null ? first : client.get(resultsPath + stage.getValue()));
            } catch (Exception e) {
                failed++;
                log.warn("{} [{}]: {}", season.describe(), stage.getKey(), e.getMessage());
                continue;
            }

            // globalnie po ID meczu: baraże bywają podpięte pod dwie ligi naraz (np. Premiership i Championship)
            Set<String> existing = rows.isEmpty() ? Set.of()
                    : repository.findExistingExternalIds(rows.stream().map(MatchRow::externalId).toList());
            Set<String> queued = new HashSet<>();

            List<Future<MatchEntity>> futures = new ArrayList<>();
            for (MatchRow row : rows) {
                if (existing.contains(row.externalId()) || !queued.add(row.externalId())
                        || !SCORE.matcher(row.score()).matches()) {
                    skipped++;
                    continue;
                }
                futures.add(executor.submit(() -> cancelled.getAsBoolean() ? null : buildEntity(season, stage.getKey(), row)));
            }

            List<MatchEntity> entities = new ArrayList<>();
            for (Future<MatchEntity> f : futures) {
                try {
                    MatchEntity e = f.get();
                    if (e != null) entities.add(e);
                } catch (Exception e) {
                    failed++;
                    log.warn("{} [{}]: {}", season.describe(), stage.getKey(), e.getMessage());
                }
            }

            repository.saveAll(entities);
            saved += entities.size();
        }

        return new ResultsSource.ScrapeResult(saved, skipped, failed, List.copyOf(stages.keySet()));
    }

    private List<MatchRow> parseRows(Document doc) {
        List<MatchRow> rows = new ArrayList<>();
        String round = "";

        for (Element tr : doc.select("table.table-main tr")) {
            Element th = tr.selectFirst("th.h-text-left");
            if (th != null) {
                round = th.text().trim();
                continue;
            }

            Element link = tr.selectFirst("a.in-match");
            if (link == null) continue;

            List<Element> spans = link.select("span");
            if (spans.size() < 2) continue;

            List<Element> cells = tr.select("td");
            if (cells.size() < 2) continue;

            String href = link.attr("href");
            String[] parts = href.split("/");
            rows.add(new MatchRow(
                    parts[parts.length - 1],
                    href,
                    spans.get(0).text().trim(),
                    spans.get(1).text().trim(),
                    cells.get(1).text().trim(),
                    round
            ));
        }
        return rows;
    }

    private MatchEntity buildEntity(LeagueSeason season, String stage, MatchRow row) throws Exception {
        Document page = client.get(row.path());

        Match match = new Match();
        match.setHome(row.home());
        match.setAway(row.away());
        match.setScore(row.score());
        match.setRoundName(row.round());
        match.setCurrentRound(firstNumber(row.round()));
        match.setStartSeasonYear(season.startYear());
        match.setCountry(season.country());

        LocalDateTime kickoff = parseKickoff(page);
        if (kickoff != null) match.setDate(kickoff.format(DATE));

        applyHalfTime(match, page, row.score());

        MatchEntity entity = betExplorerService.mapToEntity(match);
        entity.setExternalId(row.externalId());
        entity.setLeague(season.league());
        entity.setStage(stage);
        entity.setSeasonLabel(season.seasonLabel());
        entity.setKickoff(kickoff);
        return entity;
    }

    /**
     * Wynik do przerwy bierzemy z "(0:1, 2:0)" pod wynikiem meczu. Gdy go brak - liczymy z minut goli,
     * ale tylko jeśli liczba goli zgadza się z wynikiem końcowym.
     */
    private void applyHalfTime(Match match, Document page, String score) {
        Matcher ft = SCORE.matcher(score);
        ft.matches();
        int homeFT = Integer.parseInt(ft.group(1));
        int awayFT = Integer.parseInt(ft.group(2));

        Element partialEl = page.getElementById("js-partial");
        List<int[]> partials = new ArrayList<>();
        if (partialEl != null) {
            Matcher m = PAIR.matcher(partialEl.text());
            while (m.find()) partials.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
        }

        if (!partials.isEmpty()) {
            int[] ht = partials.get(0);
            if (partials.size() >= 2) {
                // wynik po 90 minutach, bez dogrywki
                homeFT = ht[0] + partials.get(1)[0];
                awayFT = ht[1] + partials.get(1)[1];
            }
            calculator.applyScores(match, ht[0], ht[1], homeFT, awayFT);
            return;
        }

        List<Goal> goals = parseGoals(page);
        if (goals.size() == homeFT + awayFT) {
            match.setGoals(goals);
            calculator.calculateMatch(match);
        } else {
            match.setScoreFT(homeFT + "-" + awayFT);
        }
    }

    private List<Goal> parseGoals(Document page) {
        List<Goal> goals = new ArrayList<>();
        List<Element> teams = page.select("ul.list-details--shooters > li.list-details__item");

        for (int i = 0; i < teams.size() && i < 2; i++) {
            String team = i == 0 ? "home" : "away";
            for (Element tr : teams.get(i).select("tr")) {
                String minute = null, scorer = null;
                for (Element td : tr.select("td")) {
                    String text = td.text().trim();
                    if (minute == null && MINUTE.matcher(text).matches()) minute = text;
                    else if (scorer == null && text.length() > 2) scorer = text;
                }
                if (minute != null && scorer != null) goals.add(new Goal(minute, scorer, team));
            }
        }
        return goals;
    }

    private LocalDateTime parseKickoff(Document page) {
        Element el = page.getElementById("match-date");
        if (el == null || !el.hasAttr("data-dt")) return null;
        try {
            String[] p = el.attr("data-dt").split(",");
            // data-dt jest w stałym UTC+1 (bez czasu letniego) - przeliczamy na czas polski, tak jak pokazuje strona
            return LocalDateTime.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]),
                            Integer.parseInt(p[3]), Integer.parseInt(p[4]))
                    .atOffset(SITE_OFFSET)
                    .atZoneSameInstant(LOCAL_ZONE)
                    .toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer firstNumber(String text) {
        Matcher m = FIRST_NUMBER.matcher(text == null ? "" : text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private record MatchRow(String externalId, String path, String home, String away, String score, String round) {
    }
}
