package com.example.apifootball;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ujednolica nazwy etapów z API-Football, które różnią się między ligami i sezonami
 * ("1st Phase" / "Regular Season", "Championship Round" / "Championship Group" / "2nd Phase"...).
 * <p>
 * Reguła: runda z numerem ("Relegation Round - 3") to faza grupowa/ligowa, runda bez numeru
 * ("Relegation Round", "Semi-finals") to baraż albo play-off.
 */
final class StageNormalizer {

    static final String REGULAR = "Regular Season";
    static final String CHAMPIONSHIP_GROUP = "Championship Group";
    static final String RELEGATION_GROUP = "Relegation Group";
    static final String PROMOTION_GROUP = "Promotion Group";
    static final String CONFERENCE_GROUP = "Conference League Group";
    static final String PLAY_OFFS = "Play-offs";
    static final String RELEGATION_PLAY_OFF = "Relegation Play-off";
    static final String CONFERENCE_PLAY_OFFS = "Conference League Play-offs";

    private static final String SPLIT = "\u0000split";
    private static final Pattern SPLIT_SEASON = Pattern.compile("^(Apertura|Clausura)(?:\\s*-\\s*(.*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBERED = Pattern.compile("^(.*?)\\s*-\\s*\\d+$");
    private static final Set<String> REGULAR_NAMES = Set.of("regular season", "1st phase", "main", "league stage", "group stage", "");

    private StageNormalizer() {
    }

    /** Etap dla każdego meczu sezonu (klucz: externalId). Potrzebuje całego sezonu, żeby rozdzielić "2nd Phase". */
    static Map<String, String> normalize(Iterable<JsonNode> fixtures) {
        Map<String, String> stages = new HashMap<>();
        List<JsonNode> split = new ArrayList<>();

        for (JsonNode f : fixtures) {
            String stage = stage(f.path("league").path("round").asText(""));
            if (SPLIT.equals(stage)) split.add(f);
            else stages.put(ApiFootballNames.externalId(f), stage);
        }

        // zdarza się też, że obie grupy są opisane jako "Championship Round" (np. Szkocja 2021/22)
        if (split.isEmpty() && !stages.containsValue(RELEGATION_GROUP)) {
            List<JsonNode> championship = new ArrayList<>();
            for (JsonNode f : fixtures) {
                if (CHAMPIONSHIP_GROUP.equals(stages.get(ApiFootballNames.externalId(f)))) championship.add(f);
            }
            if (groupCount(championship) == 2) split = championship;
        }

        if (!split.isEmpty()) {
            stages.putAll(splitGroups(split, fixtures, stages));
        }
        return stages;
    }

    static String stage(String round) {
        String raw = round.trim();

        Matcher season = SPLIT_SEASON.matcher(raw);
        if (season.matches()) {
            String prefix = capitalize(season.group(1));
            String rest = season.group(2) == null ? "" : season.group(2).trim();
            return rest.isEmpty() || rest.matches("\\d+") ? prefix : prefix + " - " + PLAY_OFFS;
        }

        Matcher numbered = NUMBERED.matcher(raw);
        if (numbered.matches()) {
            String base = numbered.group(1).trim().toLowerCase(Locale.ROOT);
            if (REGULAR_NAMES.contains(base)) return REGULAR;
            if (base.equals("2nd phase")) return SPLIT;
            if (base.contains("championship")) return CHAMPIONSHIP_GROUP;
            if (base.contains("relegation")) return RELEGATION_GROUP;
            if (base.contains("promotion") || base.contains("second stage")) return PROMOTION_GROUP;
            if (base.contains("conference league")) return CONFERENCE_GROUP;
            return numbered.group(1).trim();
        }

        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("relegation")) return RELEGATION_PLAY_OFF;
        if (lower.contains("conference league")) return CONFERENCE_PLAY_OFFS;
        return PLAY_OFFS;
    }

    /**
     * "2nd Phase" to obie grupy (mistrzowska i spadkowa) pod jedną nazwą. Drużyny grają tylko w obrębie swojej grupy,
     * więc dzielimy je na spójne składowe; grupa z większą liczbą punktów z fazy zasadniczej to grupa mistrzowska.
     */
    private static Map<String, String> splitGroups(List<JsonNode> split, Iterable<JsonNode> all, Map<String, String> stages) {
        Map<String, String> parent = new HashMap<>();
        for (JsonNode f : split) union(parent, home(f), away(f));

        Map<String, Integer> points = new HashMap<>();
        for (JsonNode f : all) {
            if (!REGULAR.equals(stages.get(ApiFootballNames.externalId(f)))) continue;
            Integer gh = ApiFootballNames.intOrNull(f.path("goals").path("home"));
            Integer ga = ApiFootballNames.intOrNull(f.path("goals").path("away"));
            if (gh == null || ga == null) continue;
            points.merge(home(f), gh > ga ? 3 : gh.equals(ga) ? 1 : 0, Integer::sum);
            points.merge(away(f), ga > gh ? 3 : gh.equals(ga) ? 1 : 0, Integer::sum);
        }

        Map<String, Double> avgByGroup = new HashMap<>();
        Map<String, Integer> sizeByGroup = new HashMap<>();
        for (String team : parent.keySet()) {
            String root = find(parent, team);
            avgByGroup.merge(root, (double) points.getOrDefault(team, 0), Double::sum);
            sizeByGroup.merge(root, 1, Integer::sum);
        }
        avgByGroup.replaceAll((root, sum) -> sum / sizeByGroup.get(root));
        String top = avgByGroup.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        boolean separable = avgByGroup.size() > 1;

        Map<String, String> result = new HashMap<>();
        for (JsonNode f : split) {
            String stage = !separable ? "2nd Phase"
                    : find(parent, home(f)).equals(top) ? CHAMPIONSHIP_GROUP : RELEGATION_GROUP;
            result.put(ApiFootballNames.externalId(f), stage);
        }
        return result;
    }

    private static int groupCount(List<JsonNode> fixtures) {
        Map<String, String> parent = new HashMap<>();
        fixtures.forEach(f -> union(parent, home(f), away(f)));
        Set<String> roots = new HashSet<>();
        parent.keySet().forEach(team -> roots.add(find(parent, team)));
        return roots.size();
    }

    private static String home(JsonNode f) {
        return f.path("teams").path("home").path("name").asText();
    }

    private static String away(JsonNode f) {
        return f.path("teams").path("away").path("name").asText();
    }

    private static String find(Map<String, String> parent, String x) {
        parent.putIfAbsent(x, x);
        String p = parent.get(x);
        if (p.equals(x)) return x;
        String root = find(parent, p);
        parent.put(x, root);
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a), rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
