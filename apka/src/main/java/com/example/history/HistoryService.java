package com.example.history;

import com.example.MatchEntity;
import com.example.MatchRepository;
import com.example.analytics.AnalyticsService.MatchView;
import com.example.apifootball.FinishedMatchesRecorder;
import com.example.source.DailySource;
import com.example.strategy.StrategyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Wyniki historyczne z bazy: ile w danym dniu było meczów z 1/2, 2/1, 1/X, 2/X (dzień po dniu i per liga).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    public static final List<String> EVENTS = List.of("1/2", "2/1", "1/X", "2/X");
    static final int MAX_DAYS = 92;
    private static final ZoneId LOCAL_ZONE = ZoneId.of("Europe/Warsaw");

    private final MatchRepository repository;
    private final StrategyProperties strategy;
    private final DailySource dailySource;
    private final FinishedMatchesRecorder recorder;

    public int pending() {
        return recorder.pending();
    }

    public HistoryView history(LocalDate from, LocalDate to, boolean onlyMine) {
        return history(from, to, onlyMine, null);
    }

    /** search - fragment nazwy kraju/ligi (bez wielkości liter i polskich/obcych znaków), też polskie nazwy lig z Lamaków. */
    public HistoryView history(LocalDate from, LocalDate to, boolean onlyMine, String search) {
        check(from, to);
        Set<String> mine = new HashSet<>();
        Map<String, String> labels = new HashMap<>();
        strategy.leagues().forEach(l -> {
            mine.add(key(l.country(), l.league()));
            labels.put(key(l.country(), l.league()), l.label());
        });
        String needle = search == null || search.isBlank() ? null : normalize(search);

        List<MatchEntity> matches = repository.findFinishedBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay()).stream()
                .filter(m -> m.getKickoff() != null)
                .filter(m -> !onlyMine || mine.contains(key(m.getCountry(), m.getLeague())))
                .filter(m -> matches(needle, m.getCountry(), m.getLeague(), labels))
                .sorted(Comparator.comparing(MatchEntity::getKickoff).thenComparing(MatchEntity::getId))
                .toList();

        Map<LocalDate, Counter> days = new TreeMap<>(Comparator.reverseOrder());
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) days.put(d, new Counter());
        Map<String, Counter> leagues = new HashMap<>();
        Counter total = new Counter();

        for (MatchEntity m : matches) {
            String htft = m.getHTFT().toUpperCase();
            Counter day = days.get(m.getKickoff().toLocalDate());
            if (day == null) continue;
            day.add(htft, m);
            total.add(htft, null);
            leagues.computeIfAbsent(m.getCountry() + ": " + m.getLeague(), k -> new Counter()).add(htft, m);
        }

        List<DayStats> dayStats = days.entrySet().stream()
                .map(e -> new DayStats(e.getKey(), e.getValue().matches, e.getValue().counts,
                        e.getValue().events.stream().map(HistoryService::view).toList()))
                .toList();
        List<LeagueStats> leagueStats = leagues.entrySet().stream()
                .map(e -> new LeagueStats(e.getKey(), e.getValue().matches, e.getValue().counts,
                        e.getValue().events.stream().map(HistoryService::view).toList()))
                .sorted(Comparator.comparingInt((LeagueStats l) -> -l.counts().values().stream().mapToInt(Integer::intValue).sum())
                        .thenComparing(LeagueStats::league))
                .toList();
        return new HistoryView(from, to, total.matches, total.counts, dayStats, leagueStats);
    }

    /**
     * Podział na sezony: ile 1/2, 2/1, 1/X, 2/X było w każdym sezonie ligi (od bieżącego roku minus seasonsBack).
     * Sezon jest "bieżący", jeśli to ostatni sezon ligi w bazie i ostatni mecz był w ciągu 45 dni.
     */
    public List<LeagueSeasons> seasons(String search, int seasonsBack) {
        String needle = search == null || search.isBlank() ? null : normalize(search);
        Map<String, String> labels = new HashMap<>();
        strategy.leagues().forEach(l -> labels.put(key(l.country(), l.league()), l.label()));
        LocalDate today = LocalDate.now(LOCAL_ZONE);

        // liga -> rok sezonu -> licznik
        Map<String, TreeMap<Integer, SeasonAcc>> byLeague = new TreeMap<>();
        Map<String, String[]> names = new HashMap<>();
        for (Object[] r : repository.countHtftBySeason(today.getYear() - seasonsBack)) {
            String country = (String) r[0], league = (String) r[1];
            if (!matches(needle, country, league, labels)) continue;
            String k = country + ": " + league;
            names.putIfAbsent(k, new String[]{country, league});
            SeasonAcc acc = byLeague.computeIfAbsent(k, x -> new TreeMap<>(Comparator.reverseOrder()))
                    .computeIfAbsent((Integer) r[2], y -> new SeasonAcc());
            if (acc.label == null || (r[3] != null && ((String) r[3]).length() > acc.label.length())) acc.label = (String) r[3];
            int n = ((Number) r[5]).intValue();
            acc.matches += n;
            String htft = ((String) r[4]).toUpperCase();
            if (acc.counts.containsKey(htft)) acc.counts.merge(htft, n, Integer::sum);
            java.time.LocalDateTime last = (java.time.LocalDateTime) r[6];
            if (last != null && (acc.last == null || last.isAfter(acc.last))) acc.last = last;
        }

        List<LeagueSeasons> result = new ArrayList<>();
        byLeague.forEach((k, seasons) -> {
            List<SeasonRow> rows = new ArrayList<>();
            boolean first = true;
            for (var e : seasons.entrySet()) {
                SeasonAcc a = e.getValue();
                boolean current = first && a.last != null && a.last.toLocalDate().isAfter(today.minusDays(45));
                rows.add(new SeasonRow(e.getKey(), a.label == null ? String.valueOf(e.getKey()) : a.label,
                        a.matches, a.counts, current, a.last));
                first = false;
            }
            String[] n = names.get(k);
            result.add(new LeagueSeasons(k, n[0], n[1], rows));
        });
        return result;
    }

    private static boolean matches(String needle, String country, String league, Map<String, String> labels) {
        return needle == null || normalize(country + ": " + league + " "
                + labels.getOrDefault(key(country, league), "") + " "
                + POLISH_COUNTRIES.getOrDefault(normalize(country), "")).contains(needle);
    }

    private static class SeasonAcc {
        String label;
        int matches;
        java.time.LocalDateTime last;
        final Map<String, Integer> counts = new LinkedHashMap<>();

        SeasonAcc() {
            EVENTS.forEach(e -> counts.put(e, 0));
        }
    }

    public record SeasonRow(int year, String label, int matches, Map<String, Integer> counts, boolean current,
                            java.time.LocalDateTime lastMatch) {
    }

    public record LeagueSeasons(String league, String country, String leagueName, List<SeasonRow> seasons) {
    }

    /**
     * Pobiera listę meczów każdego dnia z zakresu (1 zapytanie do API na dzień) - zakończone mecze lig
     * z konfiguracji trafiają wtedy do bazy w tle (FinishedMatchesRecorder).
     */
    public int refresh(LocalDate from, LocalDate to) {
        check(from, to);
        LocalDate today = LocalDate.now(LOCAL_ZONE);
        int days = 0;
        for (LocalDate d = from; !d.isAfter(to) && !d.isAfter(today); d = d.plusDays(1)) {
            try {
                dailySource.fetch(d);
                days++;
            } catch (Exception e) {
                log.warn("Historia: nie udało się pobrać meczów {}: {}", d, e.getMessage());
            }
        }
        return days;
    }

    private static void check(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new IllegalArgumentException("Data 'do' jest przed 'od'");
        if (from.plusDays(MAX_DAYS).isBefore(to)) throw new IllegalArgumentException("Maksymalnie " + MAX_DAYS + " dni");
    }

    private static EventMatch view(MatchEntity m) {
        return new EventMatch(m.getCountry(), MatchView.of(m));
    }

    static String normalize(String s) {
        return java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace('ł', 'l').replace('ı', 'i'),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('-', ' ');
    }

    /** Kraj po angielsku (jak w bazie, znormalizowany) -> polska nazwa, żeby dało się szukać "turcja", "niemcy". */
    private static final Map<String, String> POLISH_COUNTRIES = polishCountries();

    private static Map<String, String> polishCountries() {
        Map<String, String> m = new HashMap<>();
        Locale pl = Locale.forLanguageTag("pl");
        for (String code : Locale.getISOCountries()) {
            Locale c = Locale.of("", code);
            m.put(normalize(c.getDisplayCountry(Locale.ENGLISH)), c.getDisplayCountry(pl));
        }
        m.putAll(Map.of("england", "Anglia", "scotland", "Szkocja", "wales", "Walia",
                "northern ireland", "Irlandia Północna", "turkey", "Turcja", "usa", "USA Stany Zjednoczone",
                "czech republic", "Czechy", "south korea", "Korea Południowa", "ivory coast", "Wybrzeże Kości Słoniowej"));
        return m;
    }

    private static String key(String country, String league) {
        return country + "\u0000" + league;
    }

    private static class Counter {
        int matches;
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final List<MatchEntity> events = new ArrayList<>();

        Counter() {
            EVENTS.forEach(e -> counts.put(e, 0));
        }

        void add(String htft, MatchEntity m) {
            matches++;
            if (counts.containsKey(htft)) {
                counts.merge(htft, 1, Integer::sum);
                if (m != null) events.add(m);
            }
        }

        int hits() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public record EventMatch(String country, MatchView match) {
    }

    public record DayStats(LocalDate date, int matches, Map<String, Integer> counts, List<EventMatch> events) {
    }

    public record LeagueStats(String league, int matches, Map<String, Integer> counts, List<EventMatch> events) {
    }

    public record HistoryView(LocalDate from, LocalDate to, int matches, Map<String, Integer> counts,
                              List<DayStats> days, List<LeagueStats> leagues) {
    }
}
