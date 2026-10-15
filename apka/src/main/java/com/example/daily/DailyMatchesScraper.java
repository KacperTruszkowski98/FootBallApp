package com.example.daily;

import com.example.scraper.BetExplorerClient;
import com.example.source.DailySource;
import lombok.RequiredArgsConstructor;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lista meczów danego dnia. Strona /football/?year=..&day=.. ładuje mecze JS-em z homepage-data.php,
 * więc pytamy bezpośrednio ten endpoint (zwraca gotowy HTML).
 */
@Component
@RequiredArgsConstructor
public class DailyMatchesScraper implements DailySource {

    private static final ZoneOffset SITE_OFFSET = ZoneOffset.ofHours(1);
    private static final ZoneId LOCAL_ZONE = ZoneId.of("Europe/Warsaw");

    private final BetExplorerClient client;

    @Override
    public List<DailyMatch> fetch(LocalDate date) throws IOException {
        int tzHours = LOCAL_ZONE.getRules().getOffset(date.atTime(12, 0)).getTotalSeconds() / 3600;
        String url = String.format("/gres/ajax/homepage-data.php?tab=all&year=%d&month=%d&day=%d&betType=1x2&tz=%d:00&start=0&end=all",
                date.getYear(), date.getMonthValue(), date.getDayOfMonth(), tzHours);
        Document doc = client.get(url);

        List<DailyMatch> matches = new ArrayList<>();
        for (Element block : doc.select("ul.leagues-list")) {
            String countrySlug = block.attr("data-country");
            Element meta = block.selectFirst("a.myleague[data-league-name]");
            Element leagueLink = block.selectFirst("li.js-tournament a[href^=/football/]");
            if (meta == null) continue;

            for (Element li : block.select("li.table-main__tournamentLiContent[data-event-id]")) {
                Element link = li.selectFirst("a[data-live-cell=matchlink]");
                Element home = li.selectFirst(".table-main__participantHome p");
                Element away = li.selectFirst(".table-main__participantAway p");
                if (link == null || home == null || away == null) continue;

                List<Double> odds = li.select(".table-main__odds [data-odd]").stream()
                        .map(b -> parseDouble(b.attr("data-odd")))
                        .toList();

                matches.add(new DailyMatch(
                        li.attr("data-event-id"),
                        countrySlug,
                        meta.attr("data-country-name").trim(),
                        meta.attr("data-league-name").trim(),
                        leagueLink == null ? null : leagueLink.attr("href"),
                        kickoff(li.selectFirst("[data-dt]")),
                        status(li.selectFirst(".table-main__matchStatus")),
                        home.text().trim(),
                        away.text().trim(),
                        score(li.selectFirst("[data-live-cell=score]")),
                        odds.size() == 3 ? odds.get(0) : null,
                        odds.size() == 3 ? odds.get(1) : null,
                        odds.size() == 3 ? odds.get(2) : null
                ));
            }
        }
        return matches;
    }

    private static String score(Element el) {
        if (el == null) return null;
        String s = el.children().stream().map(Element::text).map(String::trim).collect(Collectors.joining(""));
        return s.matches("\\d+[-:]\\d+") ? s.replace('-', ':') : null;
    }

    private static String status(Element el) {
        return el == null ? null : el.text().trim();
    }

    private static LocalDateTime kickoff(Element el) {
        if (el == null) return null;
        try {
            String[] p = el.attr("data-dt").split(",");
            return LocalDateTime.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]),
                            Integer.parseInt(p[3]), Integer.parseInt(p[4]))
                    .atOffset(SITE_OFFSET).atZoneSameInstant(LOCAL_ZONE).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private static Double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }
}
