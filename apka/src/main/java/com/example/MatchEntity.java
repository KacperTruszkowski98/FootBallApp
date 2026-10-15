package com.example;

import com.example.dto.MatchResult;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
@Table(indexes = @Index(name = "idx_match_competition", columnList = "country, league, stage"))
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

    // Pola uzupełniane przez automatyczny scraper (stare, ręczne rekordy mają tu null)
    @Column(unique = true)
    private String externalId;
    private String league;
    private String stage;
    private String seasonLabel;
    private LocalDateTime kickoff;
}
