package com.example.dto;

import com.example.MatchEntity;

import java.time.LocalDateTime;

/** Mecz z bazy w postaci dla frontu (historia pary, zakładka Historia). */
public record MatchView(long id, String season, int startYear, String league, String stage, String round,
                        Integer roundNumber, LocalDateTime kickoff, String home, String away,
                        String scoreHT, String scoreFT, String htft, String goalOrder) {

    public static MatchView of(MatchEntity m) {
        return new MatchView(m.getId(), m.getSeasonLabel(), m.getStartSeasonYear(), m.getLeague(), m.getStage(),
                m.getRoundName(), m.getCurrentRound(), m.getKickoff(), m.getHome(), m.getAway(),
                m.getScoreHT(), m.getScoreFT(), m.getHTFT(), m.getGoalOrder());
    }
}
