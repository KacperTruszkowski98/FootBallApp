package com.example;

import com.example.dto.MatchResult;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class MatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
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
}
