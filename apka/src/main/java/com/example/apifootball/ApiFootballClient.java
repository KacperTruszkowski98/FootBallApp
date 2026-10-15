package com.example.apifootball;

import com.example.scraper.ScraperProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Klient REST API-Football v3. Pilnuje limitu zapytań na minutę i zgłasza błędy zwracane w polu "errors".
 */
@Slf4j
@Component
public class ApiFootballClient {

    private static final int MAX_ATTEMPTS = 4;

    private final ScraperProperties.ApiFootball config;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private long lastRequestAt;

    public ApiFootballClient(ScraperProperties properties, ObjectMapper mapper) {
        this.config = properties.apiFootball();
        this.mapper = mapper;
    }

    /** Zwraca pole "response" z odpowiedzi. */
    public JsonNode get(String path, Map<String, ?> params) throws IOException, InterruptedException {
        return call(path, params).path("response");
    }

    /** Dla endpointów stronicowanych (np. /odds) - skleja "response" ze wszystkich stron. */
    public List<JsonNode> getAllPages(String path, Map<String, ?> params) throws IOException, InterruptedException {
        List<JsonNode> all = new ArrayList<>();
        int page = 1, total;
        do {
            Map<String, Object> p = new LinkedHashMap<>(params);
            if (page > 1) p.put("page", page);
            JsonNode body = call(path, p);
            body.path("response").forEach(all::add);
            total = body.path("paging").path("total").asInt(1);
        } while (++page <= total);
        return all;
    }

    private JsonNode call(String path, Map<String, ?> params) throws IOException, InterruptedException {
        if (config.key() == null || config.key().isBlank()) {
            throw new IllegalStateException("Brak klucza API-Football - uzupełnij scraper.api-football.key w application.yaml");
        }
        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        URI uri = URI.create(config.baseUrl() + path + (query.isEmpty() ? "" : "?" + query));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("x-apisports-key", config.key())
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        for (int attempt = 1; ; attempt++) {
            throttle();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                if (attempt >= MAX_ATTEMPTS) throw new IOException("API-Football HTTP " + response.statusCode() + " dla " + uri);
                log.warn("API-Football HTTP {} dla {} (próba {}/{})", response.statusCode(), uri, attempt, MAX_ATTEMPTS);
                Thread.sleep(2000L * attempt * attempt);
                continue;
            }
            if (response.statusCode() != 200) {
                throw new IOException("API-Football HTTP " + response.statusCode() + " dla " + uri);
            }

            JsonNode body = mapper.readTree(response.body());
            JsonNode errors = body.path("errors");
            if (errors.size() > 0) {
                // "errors" bywa pustą tablicą albo obiektem {"plan": "...", "rateLimit": "..."}
                if (errors.has("rateLimit") && attempt < MAX_ATTEMPTS) {
                    Thread.sleep(5000L * attempt);
                    continue;
                }
                throw new IOException("API-Football: " + errors);
            }
            return body;
        }
    }

    private synchronized void throttle() throws InterruptedException {
        long wait = lastRequestAt + config.requestDelayMs() - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastRequestAt = System.currentTimeMillis();
    }
}
