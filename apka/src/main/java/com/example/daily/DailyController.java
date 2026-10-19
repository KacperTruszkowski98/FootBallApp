package com.example.daily;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/v2/daily")
@RequiredArgsConstructor
public class DailyController {

    private final DailyService service;

    /** Np. /api/v2/daily?date=2026-10-16&events=1/2&events=2/1 (bez daty = dziś). */
    @GetMapping
    public List<DailyService.DailyMatchView> daily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) List<String> events,
            @RequestParam(defaultValue = "true") boolean onlyTracked) throws Exception {
        LocalDate day = date != null ? date : LocalDate.now(ZoneId.of("Europe/Warsaw"));
        return service.daily(day, events == null ? List.of() : events, onlyTracked);
    }

    /** Kursy 1X2 meczów dnia: externalId -> [1, X, 2]. Pierwsze wywołanie dla dnia może trwać do minuty, kolejne są szybkie. */
    @GetMapping("/odds")
    public java.util.Map<String, double[]> odds(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws Exception {
        return service.odds(date != null ? date : LocalDate.now(ZoneId.of("Europe/Warsaw")));
    }
}
