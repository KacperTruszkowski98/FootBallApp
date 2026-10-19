package com.example.scraper;

import com.example.source.ResultsSource;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/scrape")
@RequiredArgsConstructor
public class ScrapeController {

    private final ResultsSource source;
    private final ScrapeJobService jobs;

    /** Aktywne źródło i kraje z application.yaml. */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("source", source.name(), "countries", source.countries());
    }

    /** Podgląd, co zostanie pobrane (ligi + sezony) - bez zapisywania czegokolwiek. */
    @GetMapping("/plan")
    public List<CountryPlan> plan(@RequestParam(required = false) String country) {
        if (country == null || country.isBlank()) {
            return source.countries().stream().map(c -> source.plan(c.slug())).toList();
        }
        return List.of(source.plan(country));
    }

    /** Startuje scraping. Bez krajów i lig - wszystko z konfiguracji. */
    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody(required = false) StartRequest request) {
        try {
            return ResponseEntity.ok(request == null ? jobs.start(null, null, false)
                    : jobs.start(request.countries(), request.leagues(), request.latestOnly()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/cancel")
    public ScrapeJobService.JobStatus cancel() {
        jobs.cancel();
        return jobs.status();
    }

    @GetMapping("/status")
    public ScrapeJobService.JobStatus status() {
        return jobs.status();
    }

    /**
     * @param countries  kraje (slugi) - wszystkie ich ligi
     * @param leagues    pojedyncze ligi (ref z /config), np. ["179", "180"]
     * @param latestOnly tylko dwa ostatnie sezony każdej ligi (szybkie dociągnięcie świeżych wyników)
     */
    public record StartRequest(List<String> countries, List<String> leagues, boolean latestOnly) {
    }
}
