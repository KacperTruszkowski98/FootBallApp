package com.example.history;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService service;

    /** Np. /api/v2/history?from=2026-10-26&to=2026-11-01&onlyMine=true */
    @GetMapping
    public HistoryService.HistoryView history(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean onlyMine,
            @RequestParam(required = false) String search) {
        return service.history(from, to, onlyMine, search);
    }

    /** Dociąga zakończone mecze z zakresu do bazy (1 zapytanie do API na dzień, zapis w tle). */
    @PostMapping("/refresh")
    public Map<String, Integer> refresh(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Map.of("days", service.refresh(from, to));
    }

    /** Podział na sezony per liga, np. /api/v2/history/seasons?search=turcja&seasonsBack=6 */
    @GetMapping("/seasons")
    public java.util.List<HistoryService.LeagueSeasons> seasons(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "6") int seasonsBack) {
        return service.seasons(search, Math.max(1, Math.min(seasonsBack, 15)));
    }

    /** Ile lig czeka jeszcze na zapis po dociąganiu. */
    @GetMapping("/pending")
    public Map<String, Integer> pending() {
        return Map.of("pending", service.pending());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
