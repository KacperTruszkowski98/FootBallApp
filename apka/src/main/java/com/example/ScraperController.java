package com.example;

import com.example.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class ScraperController {


    private final BetExplorerService service;
    private final UpcomingMatchScraperService upcomingMatchScraperService;

    @GetMapping("/scrape")
    public List<Match> scrape(@RequestBody ScrapRequest request) throws InterruptedException {
        return service.scrapeSeason(request);
    }

    @GetMapping("/recalculate/results")
    public ResultDto recalculate(@RequestBody ResultRequest request) {
        return service.recalculate(request);
    }

    @GetMapping("/compare-seasons")
    public List<SeasonSummaryDto> compareSeasons(@RequestParam String country) {
        return service.compareAllSeasons(country);
    }

    @GetMapping("/countries")
    public List<String> getCountries() {
        return service.getCountries();
    }

    @GetMapping("/upcoming")
    public List<UpcomingMatch> scrapeUpcomingMatches(@RequestBody UpcomingMatchesRequest request) {
        return upcomingMatchScraperService.scrapeUpcomingMatches(request);
    }
}