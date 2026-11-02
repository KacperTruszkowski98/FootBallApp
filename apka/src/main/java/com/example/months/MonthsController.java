package com.example.months;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/months")
@RequiredArgsConstructor
public class MonthsController {

    private final MonthsService service;

    /** Np. /api/v2/months?month=11&events=1/2&events=2/1&fromYear=2021&minMatches=3&sort=YEARS&limit=10 */
    @GetMapping
    public MonthsService.MonthsView ranking(
            @RequestParam int month,
            @RequestParam(required = false) List<String> events,
            @RequestParam(defaultValue = "2021") int fromYear,
            @RequestParam(defaultValue = "3") int minMatches,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "YEARS") MonthsService.Sort sort,
            @RequestParam(defaultValue = "10") int limit) {
        if (month < 1 || month > 12) throw new IllegalArgumentException("Miesiąc 1-12");
        return service.ranking(month, events == null ? List.of() : events, fromYear, Math.max(1, minMatches),
                search, sort, Math.max(1, Math.min(limit, 200)));
    }
}
