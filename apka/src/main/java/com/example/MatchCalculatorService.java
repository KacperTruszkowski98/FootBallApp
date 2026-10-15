package com.example;

import com.example.dto.Goal;
import com.example.dto.Match;
import com.example.dto.MatchResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MatchCalculatorService {

    public void calculateMatch(Match match) {
        int homeHT = 0;
        int awayHT = 0;

        int homeFT = 0;
        int awayFT = 0;

        List<Goal> goals = match.getGoals();

        for (Goal g : goals) {
            int minute = parseMinute(g.getMinute());
            boolean isHome = "home".equalsIgnoreCase(g.getTeam());

            // FULL TIME
            if (isHome) homeFT++;
            else awayFT++;

            // HALF TIME
            if (isFirstHalf(g.getMinute(), minute)) {
                if (isHome) homeHT++;
                else awayHT++;
            }
        }

        applyScores(match, homeHT, awayHT, homeFT, awayFT);
    }

    public void applyScores(Match match, int homeHT, int awayHT, int homeFT, int awayFT) {
        // wyniki
        match.setScoreHT(homeHT + "-" + awayHT);
        match.setScoreFT(homeFT + "-" + awayFT);

        // zwycięzcy
        MatchResult winHT = resolveWinner(homeHT, awayHT);
        MatchResult winFT = resolveWinner(homeFT, awayFT);

        match.setWinHT(winHT);
        match.setWinFT(winFT);

        // 🔥 HTFT (tego brakowało)
        match.setHTFT(calculateHTFT(winHT, winFT));
    }

    private String calculateHTFT(MatchResult ht, MatchResult ft) {
        return mapResult(ht) + "/" + mapResult(ft);
    }

    private String mapResult(MatchResult result) {
        switch (result) {
            case HOME:
                return "1";
            case DRAW:
                return "X";
            case AWAY:
                return "2";
            default:
                return "?";
        }
    }

    private MatchResult resolveWinner(int homeGoals, int awayGoals) {
        if (homeGoals > awayGoals) {
            return MatchResult.HOME;
        }
        if (awayGoals > homeGoals) {
            return MatchResult.AWAY;
        }
        return MatchResult.DRAW;
    }

    private int parseMinute(String minuteStr) {
        if (minuteStr == null || minuteStr.isEmpty()) return 0;

        minuteStr = minuteStr.replace(".", "").trim();

        if (minuteStr.contains("+")) {
            String[] parts = minuteStr.split("\\+");
            return Integer.parseInt(parts[0]) + Integer.parseInt(parts[1]);
        }

        return Integer.parseInt(minuteStr);
    }

    private boolean isFirstHalf(String originalMinute, int parsedMinute) {
        if (originalMinute == null) return false;

        // np 45+2 → pierwsza połowa
        if (originalMinute.startsWith("45")) {
            return true;
        }

        return parsedMinute <= 45;
    }
}