package com.example.apifootball;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StageNormalizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Regular Season - 12                  | Regular Season",
            "1st Phase - 33                       | Regular Season",
            "Group Stage - 4                      | Regular Season",
            "Apertura - 5                         | Apertura",
            "Clausura - 17                        | Clausura",
            "Apertura - Semi-finals               | Apertura - Play-offs",
            "Clausura - Reclasificacion           | Clausura - Play-offs",
            "Apertura - Play-In Final             | Apertura - Play-offs",
            "Championship Round - 3               | Championship Group",
            "Championship Group - 1               | Championship Group",
            "Relegation Round - 5                 | Relegation Group",
            "Relegation Group - 2                 | Relegation Group",
            "Promotion Round - 2                  | Promotion Group",
            "Second Stage - 6                     | Promotion Group",
            "Conference League Play-off Group - 4 | Conference League Group",
            "Relegation Round                     | Relegation Play-off",
            "Relegation/Promotion                 | Relegation Play-off",
            "Relegation - Final                   | Relegation Play-off",
            "Conference League Play-offs - Final  | Conference League Play-offs",
            "Championship - Semi-finals           | Play-offs",
            "Quarter-finals                       | Play-offs",
            "Grand Final                          | Play-offs",
            "R73389                               | Play-offs",
    })
    void normalizesRoundNames(String round, String expected) {
        assertThat(StageNormalizer.stage(round)).isEqualTo(expected);
    }

    @Test
    void splitsSecondPhaseIntoChampionshipAndRelegationGroupByRegularSeasonPoints() {
        ArrayNode fixtures = MAPPER.createArrayNode();
        // faza zasadnicza: A i B wygrywają z C i D
        fixtures.add(fixture(1, "1st Phase - 1", "A", "C", 2, 0));
        fixtures.add(fixture(2, "1st Phase - 1", "B", "D", 1, 0));
        fixtures.add(fixture(3, "1st Phase - 2", "C", "B", 0, 3));
        fixtures.add(fixture(4, "1st Phase - 2", "D", "A", 1, 1));
        // 2nd Phase: dwie rozłączne grupy {A, B} i {C, D}
        fixtures.add(fixture(5, "2nd Phase - 1", "A", "B", 1, 0));
        fixtures.add(fixture(6, "2nd Phase - 1", "C", "D", 0, 2));

        Map<String, String> stages = StageNormalizer.normalize(fixtures);

        assertThat(stages).containsEntry("af:1", "Regular Season")
                .containsEntry("af:5", "Championship Group")
                .containsEntry("af:6", "Relegation Group");
    }

    @Test
    void splitsChampionshipRoundWhenBothGroupsShareOneName() {
        ArrayNode fixtures = MAPPER.createArrayNode();
        fixtures.add(fixture(1, "1st Phase - 1", "A", "C", 2, 0));
        fixtures.add(fixture(2, "1st Phase - 1", "B", "D", 1, 0));
        fixtures.add(fixture(3, "Championship Round - 1", "A", "B", 1, 0));
        fixtures.add(fixture(4, "Championship Round - 1", "C", "D", 0, 2));

        Map<String, String> stages = StageNormalizer.normalize(fixtures);

        assertThat(stages).containsEntry("af:3", "Championship Group")
                .containsEntry("af:4", "Relegation Group");
    }

    @Test
    void keepsSingleChampionshipGroupUntouched() {
        ArrayNode fixtures = MAPPER.createArrayNode();
        fixtures.add(fixture(1, "Regular Season - 1", "A", "C", 2, 0));
        fixtures.add(fixture(2, "Championship Round - 1", "A", "B", 1, 0));
        fixtures.add(fixture(3, "Championship Round - 2", "B", "C", 0, 2));

        assertThat(StageNormalizer.normalize(fixtures)).containsEntry("af:2", "Championship Group")
                .containsEntry("af:3", "Championship Group");
    }

    private static JsonNode fixture(int id, String round, String home, String away, int gh, int ga) {
        ObjectNode f = MAPPER.createObjectNode();
        f.putObject("fixture").put("id", id);
        f.putObject("league").put("round", round);
        ObjectNode teams = f.putObject("teams");
        teams.putObject("home").put("name", home);
        teams.putObject("away").put("name", away);
        f.putObject("goals").put("home", gh).put("away", ga);
        return f;
    }
}
