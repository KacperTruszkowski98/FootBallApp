package com.example.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Match {

    private String home;
    private String away;
    private String date;
    private String score;
    private Integer currentRound;
    private String roundName;
    private String HTFT;
    private int startSeasonYear;
    private String country;

    private String scoreHT;
    private String scoreFT;

    private MatchResult winHT;
    private MatchResult winFT;

    private List<Goal> goals = new ArrayList<>();
}