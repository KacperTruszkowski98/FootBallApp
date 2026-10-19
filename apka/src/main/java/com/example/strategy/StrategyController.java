package com.example.strategy;

import com.example.scraper.ScrapeJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyService service;
    private final ScrapeJobService jobs;

    /** Np. /api/v2/strategy?minCommon=2&historyFrom=2022-01-01&daysAhead=7 (brak parametru = wartość z application.yaml). */
    @GetMapping
    public StrategyService.StrategyView view(
            @RequestParam(required = false) List<String> events,
            @RequestParam(required = false) Integer minCommon,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate historyFrom,
            @RequestParam(required = false) Integer daysAhead,
            @RequestParam(required = false) Integer daysBack) {
        StrategyService.Settings d = service.defaults();
        return service.view(new StrategyService.Settings(
                events == null || events.isEmpty() ? d.events() : events,
                minCommon == null ? d.minCommon() : Math.max(1, minCommon),
                historyFrom == null ? d.historyFrom() : historyFrom,
                daysAhead == null ? d.daysAhead() : Math.clamp(daysAhead, 1, 14),
                daysBack == null ? d.daysBack() : Math.clamp(daysBack, 0, 60)));
    }

    /** Uzupełnia kolejność goli w lamakach lig strategii (do "podwójnych lamaków"); po 20 meczów na zapytanie do API. */
    @PostMapping("/goals")
    public ResponseEntity<?> goals(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate historyFrom) {
        StrategyService.Settings d = service.defaults();
        try {
            return ResponseEntity.ok(service.fillGoalOrders(historyFrom == null ? d
                    : new StrategyService.Settings(d.events(), d.minCommon(), historyFrom, d.daysAhead(), d.daysBack())));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "API-Football: " + e.getMessage()));
        }
    }

    /** Dociąga nowe wyniki (bieżące sezony) krajów z ligami strategii - w tle, postęp pod /api/v2/scrape/status. */
    @PostMapping("/update")
    public ResponseEntity<?> update() {
        try {
            return ResponseEntity.ok(jobs.start(service.countrySlugs(), null, true));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }
}
