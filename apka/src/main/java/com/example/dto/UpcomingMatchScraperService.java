package com.example.dto;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UpcomingMatchScraperService {

    private static final String BASE_URL =
            "https://www.betexplorer.com/football/";

    public List<UpcomingMatch> scrapeUpcomingMatches(
            UpcomingMatchesRequest request
    ) {

        List<UpcomingMatch> matches = new ArrayList<>();

        String url = String.format(
                "%s?year=%d&month=%02d&day=%02d",
                BASE_URL,
                request.year(),
                request.month(),
                request.day()
        );

        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
            );

            BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions()
                            .setUserAgent(
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                            "Chrome/122.0.0.0 Safari/537.36"
                            )
            );

            Page page = context.newPage();

            page.setDefaultTimeout(60000);
            page.setDefaultNavigationTimeout(60000);

            System.out.println("🌍 OPEN: " + url);

            // =========================
            // NAVIGACJA
            // =========================

            page.navigate(url);

            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForTimeout(6000);

            // =========================
            // DEBUG (opcjonalnie)
            // =========================
            System.out.println("PAGE TITLE: " + page.title());

            // =========================
            // SCRAPING
            // =========================

            List<ElementHandle> rows =
                    page.querySelectorAll("tr");

            System.out.println("📋 ROWS: " + rows.size());

            String currentLeague = "";

            for (ElementHandle row : rows) {

                try {

                    String text = row.innerText().trim();

                    if (text.isEmpty()) continue;

                    // =========================
                    // LEAGUE DETECTION
                    // =========================
                    if (text.contains(":") && !text.contains(" - ")) {

                        currentLeague = text;

                        System.out.println("🏆 LEAGUE: " + currentLeague);

                        continue;
                    }

                    // =========================
                    // MATCH FILTER
                    // =========================
                    if (!text.contains(" - ")) {
                        continue;
                    }

                    String[] teams = text.split(" - ");

                    if (teams.length != 2) continue;

                    String home = teams[0]
                            .replaceAll("^\\d{1,2}:\\d{2}", "")
                            .trim();

                    String away = teams[1].trim();

                    if (home.isEmpty() || away.isEmpty()) {
                        continue;
                    }

                    // =========================
                    // TIME
                    // =========================
                    String time = "";

                    List<ElementHandle> tds =
                            row.querySelectorAll("td");

                    if (!tds.isEmpty()) {

                        String first = tds.get(0)
                                .innerText()
                                .trim();

                        if (first.matches("\\d{1,2}:\\d{2}")) {
                            time = first;
                        }
                    }

                    // =========================
                    // CREATE MATCH
                    // =========================

                    UpcomingMatch match = new UpcomingMatch();

                    match.setHome(home);
                    match.setAway(away);
                    match.setLeague(currentLeague);

                    match.setDate(String.format(
                            "%04d-%02d-%02d",
                            request.year(),
                            request.month(),
                            request.day()
                    ));

                    match.setTime(time);

                    matches.add(match);

                    System.out.println(
                            "⚽ " + home +
                                    " vs " + away +
                                    " | " + time +
                                    " | " + currentLeague
                    );

                } catch (Exception e) {

                    System.err.println(
                            "⚠️ ROW ERROR: " + e.getMessage()
                    );
                }
            }

            context.close();
            browser.close();

        } catch (Exception e) {

            System.err.println(
                    "❌ SCRAPER ERROR: " + e.getMessage()
            );
        }

        System.out.println("✅ TOTAL MATCHES: " + matches.size());

        return matches;
    }
}