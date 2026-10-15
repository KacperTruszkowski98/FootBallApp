package com.example.scraper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Na podstawie strony kraju (/football/{kraj}/) wybiera ligi i listę ich sezonów.
 * Strona kraju grupuje ligi po sezonach ("2025/2026", "2025"...) w kolejności od najwyższej ligi,
 * a slug ligi potrafi się zmieniać między sezonami - dlatego linki bierzemy stąd, a nie składamy z szablonu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeagueDiscoveryService {

    private static final Pattern SEASON_HEADER = Pattern.compile("^(\\d{4})(?:/(\\d{4}))?$");

    private final BetExplorerClient client;
    private final ScraperProperties properties;

    public Optional<ScraperProperties.CountryConfig> findConfig(String slug) {
        return properties.countries().stream().filter(c -> c.slug().equalsIgnoreCase(slug)).findFirst();
    }

    public CountryPlan plan(ScraperProperties.CountryConfig config) {
        try {
            Document doc = client.get("/football/" + config.slug() + "/");
            String country = countryName(doc, config.slug());
            List<SeasonGroup> groups = parseGroups(doc);

            List<String> available = groups.stream()
                    .flatMap(g -> g.links().keySet().stream())
                    .distinct()
                    .toList();

            List<String> selected = selectLeagues(config, available);

            int from = config.fromYear() == null ? 0 : config.fromYear();
            int to = config.toYear() == null ? Integer.MAX_VALUE : config.toYear();

            List<LeagueSeason> seasons = new ArrayList<>();
            for (SeasonGroup group : groups) {
                if (group.startYear() < from || group.startYear() > to) continue;
                for (String league : selected) {
                    String path = group.links().get(league);
                    if (path != null) {
                        seasons.add(new LeagueSeason(config.slug(), country, league, league,
                                group.label(), group.startYear(), path));
                    }
                }
            }
            seasons.sort(Comparator.comparing(LeagueSeason::league).thenComparing(LeagueSeason::startYear));

            return new CountryPlan(config.slug(), country, selected, available, seasons, null);
        } catch (Exception e) {
            log.error("Nie udało się odczytać kraju {}", config.slug(), e);
            return new CountryPlan(config.slug(), config.slug(), List.of(), List.of(), List.of(), e.getMessage());
        }
    }

    private List<String> selectLeagues(ScraperProperties.CountryConfig config, List<String> available) {
        if (config.leagues() != null && !config.leagues().isEmpty()) {
            return config.leagues().stream()
                    .filter(name -> {
                        boolean exists = available.contains(name);
                        if (!exists) log.warn("Liga '{}' nie istnieje dla {}. Dostępne: {}", name, config.slug(), available);
                        return exists;
                    })
                    .toList();
        }

        int top = config.top() != null ? config.top() : properties.defaultTopLeagues();
        List<Pattern> excluded = properties.excludedLeaguePatterns().stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .toList();

        return available.stream()
                .filter(name -> excluded.stream().noneMatch(p -> p.matcher(name).find()))
                .limit(top)
                .toList();
    }

    private List<SeasonGroup> parseGroups(Document doc) {
        List<SeasonGroup> groups = new ArrayList<>();

        for (Element tbody : doc.select("table.table-main tbody")) {
            Element th = tbody.selectFirst("th");
            if (th == null) continue;

            Matcher m = SEASON_HEADER.matcher(th.text().trim());
            if (!m.matches()) continue;

            Map<String, String> links = new LinkedHashMap<>();
            for (Element a : tbody.select("td a[href^=/football/]")) {
                links.putIfAbsent(a.text().trim(), a.attr("href"));
            }
            groups.add(new SeasonGroup(th.text().trim(), Integer.parseInt(m.group(1)), links));
        }
        return groups;
    }

    private String countryName(Document doc, String slug) {
        Element h1 = doc.selectFirst("h1.wrap-section__header__title");
        if (h1 != null && h1.text().contains(",")) {
            return h1.text().substring(0, h1.text().indexOf(',')).trim();
        }
        return slug;
    }

    private record SeasonGroup(String label, int startYear, Map<String, String> links) {
    }
}
