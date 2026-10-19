package com.example.strategy;

import com.example.MatchEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyServiceTest {

    private static final Set<String> LAMAKI = Set.of("1/2", "2/1");

    private long id;
    private LocalDateTime time = LocalDateTime.of(2024, 1, 1, 18, 0);

    @Test
    void matchQualifiesOnlyWithEnoughEarlierCommonEvents() {
        List<MatchEntity> matches = new ArrayList<>(List.of(
                match("A", "B", "2/1"),
                match("B", "A", "1/2"),   // przed nim para miała 1 lamaka - jeszcze się nie kwalifikuje
                match("A", "B", "1/1"),   // przed nim 2 lamaki - kwalifikuje się, pudło
                match("C", "D", "1/2"),   // inna para - nie kwalifikuje się
                match("B", "A", "2/1"),   // kwalifikuje się, trafienie
                match("A", "B", "X/X")    // kwalifikuje się, pudło
        ));

        StrategyService.LeagueScan scan = StrategyService.scan(matches, LAMAKI, 2, LocalDate.of(2000, 1, 1));

        assertThat(scan.qualifying()).isEqualTo(3);
        assertThat(scan.qualifyingHits()).isEqualTo(1);
        assertThat(scan.sinceLastHit()).isEqualTo(1);
        assertThat(scan.lastHit()).isSameAs(matches.get(4));
        assertThat(scan.eventHits()).isEqualTo(4);
        assertThat(scan.pairHits().get(StrategyService.pairKey("A", "B"))).hasSize(3);
        assertThat(scan.meetings().get(StrategyService.pairKey("B", "A"))).isEqualTo(5);
        assertThat(scan.recent()).extracting(StrategyService.RecentPick::commonBefore).containsExactly(2, 2, 3);
    }

    @Test
    void recentPicksStartAtGivenDay() {
        List<MatchEntity> matches = List.of(match("A", "B", "1/2"), match("A", "B", "2/1"),
                match("A", "B", "1/1"), match("A", "B", "1/2"));

        StrategyService.LeagueScan scan = StrategyService.scan(matches, LAMAKI, 2, matches.get(3).getKickoff().toLocalDate());

        assertThat(scan.recent()).singleElement().satisfies(p -> {
            assertThat(p.match()).isSameAs(matches.get(3));
            assertThat(p.hit()).isTrue();
        });
    }

    private MatchEntity match(String home, String away, String htft) {
        MatchEntity m = new MatchEntity();
        m.setId(++id);
        m.setHome(home);
        m.setAway(away);
        m.setHTFT(htft);
        m.setKickoff(time = time.plusDays(1));
        return m;
    }
}
