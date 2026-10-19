package com.example.scraper;

import com.example.source.ResultsSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ScrapeJobServiceTest {

    private final FakeSource source = new FakeSource();
    private final ScrapeJobService jobs = new ScrapeJobService(source,
            new ScraperProperties(null, null, null, 1, null, null, null));

    @Test
    void selectedLeaguesOnly() throws Exception {
        jobs.start(null, List.of("180"), false);
        awaitFinished();

        assertThat(source.scraped).containsExactly("180:2023", "180:2024", "180:2025");
    }

    @Test
    void countryAndLeagueSelectionsAreCombined() throws Exception {
        jobs.start(List.of("india"), List.of("179"), true);
        awaitFinished();

        assertThat(source.scraped).containsExactlyInAnyOrder("179:2024", "179:2025", "323:2024", "323:2025");
    }

    @Test
    void nothingSelectedMeansEverything() throws Exception {
        jobs.start(null, null, true);
        awaitFinished();

        assertThat(source.scraped).hasSize(6);
    }

    private void awaitFinished() throws InterruptedException {
        for (int i = 0; i < 200 && (jobs.status().running() || jobs.status().finishedAt() == null); i++) {
            Thread.sleep(20);
        }
        assertThat(jobs.status().running()).isFalse();
    }

    /** Szkocja: ligi 179 i 180, Indie: liga 323; każda z sezonami 2023-2025. */
    private static class FakeSource implements ResultsSource {

        final List<String> scraped = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public List<ConfiguredCountry> countries() {
            return List.of(
                    new ConfiguredCountry("scotland", "Scotland", 2023, List.of(
                            new ConfiguredLeague("179", "Premiership", 1), new ConfiguredLeague("180", "Championship", 2))),
                    new ConfiguredCountry("india", "India", 2023, List.of(new ConfiguredLeague("323", "ISL", 1))));
        }

        @Override
        public CountryPlan plan(String slug) {
            List<String> refs = countries().stream().filter(c -> c.slug().equals(slug)).findFirst().orElseThrow()
                    .leagues().stream().map(ConfiguredLeague::ref).toList();
            List<LeagueSeason> seasons = refs.stream()
                    .flatMap(ref -> java.util.stream.IntStream.rangeClosed(2023, 2025)
                            .mapToObj(y -> new LeagueSeason(slug, slug, ref, ref, String.valueOf(y), y, ref + ":" + y)))
                    .toList();
            return new CountryPlan(slug, slug, refs, List.of(), seasons, null);
        }

        @Override
        public ScrapeResult scrape(LeagueSeason season, ExecutorService executor, BooleanSupplier cancelled) {
            scraped.add(season.path());
            return new ScrapeResult(0, 0, 0, List.of());
        }
    }
}
