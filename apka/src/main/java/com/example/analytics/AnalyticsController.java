package com.example.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService service;

    @GetMapping("/competitions")
    public List<AnalyticsService.CountryCompetitions> competitions() {
        return service.competitions();
    }

    @GetMapping("/matches")
    public List<AnalyticsService.MatchView> matches(@RequestParam String country,
                                                    @RequestParam(required = false) String league,
                                                    @RequestParam(required = false) List<String> stages,
                                                    @RequestParam(defaultValue = "0") int fromSeason) {
        return service.matches(new AnalyticsService.Filter(country, league, stages, null, fromSeason));
    }

    @GetMapping("/summary")
    public List<AnalyticsService.SeasonSummary> summary(@RequestParam String country,
                                                        @RequestParam(required = false) String league,
                                                        @RequestParam(required = false) List<String> stages,
                                                        @RequestParam(required = false) List<String> events,
                                                        @RequestParam(defaultValue = "0") int fromSeason,
                                                        @RequestParam(defaultValue = "true") boolean splitStages) {
        return service.summary(new AnalyticsService.Filter(country, league, stages, events, fromSeason), splitStages);
    }

    /** Np. /api/v2/analytics/pairs?country=Mexico&league=Liga MX&events=1/2&events=2/1&minCount=2 */
    @GetMapping("/pairs")
    public List<AnalyticsService.PairStats> pairs(@RequestParam String country,
                                                  @RequestParam(required = false) String league,
                                                  @RequestParam(required = false) List<String> stages,
                                                  @RequestParam List<String> events,
                                                  @RequestParam(defaultValue = "0") int fromSeason,
                                                  @RequestParam(defaultValue = "2") int minCount) {
        return service.pairs(new AnalyticsService.Filter(country, league, stages, events, fromSeason), minCount);
    }

    @GetMapping("/teams")
    public List<AnalyticsService.TeamStats> teams(@RequestParam String country,
                                                  @RequestParam(required = false) String league,
                                                  @RequestParam(required = false) List<String> stages,
                                                  @RequestParam List<String> events,
                                                  @RequestParam(defaultValue = "0") int fromSeason) {
        return service.teams(new AnalyticsService.Filter(country, league, stages, events, fromSeason));
    }
}
