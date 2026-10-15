package com.example.scraper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Pobiera strony BetExplorera jako statyczny HTML (bez przeglądarki) z ponawianiem i opóźnieniem.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BetExplorerClient {

    public static final String BASE_URL = "https://www.betexplorer.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final int MAX_ATTEMPTS = 4;

    private final ScraperProperties properties;

    public Document get(String pathOrUrl) throws IOException {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : BASE_URL + pathOrUrl;
        IOException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                sleep(properties.requestDelayMs());
                Connection connection = Jsoup.connect(url)
                        .userAgent(USER_AGENT)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .timeout(30_000)
                        .maxBodySize(0);
                if (url.contains("/gres/ajax/")) {
                    // endpointy AJAX zwracają pustą odpowiedź bez Referera
                    connection.referrer(BASE_URL + "/football/").header("X-Requested-With", "XMLHttpRequest");
                }
                return connection.get();
            } catch (IOException e) {
                last = e;
                log.warn("GET {} nieudany (próba {}/{}): {}", url, attempt, MAX_ATTEMPTS, e.getMessage());
                sleep(1000L * attempt * attempt);
            }
        }
        throw last;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
