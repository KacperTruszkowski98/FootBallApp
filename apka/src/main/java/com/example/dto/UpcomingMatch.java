package com.example.dto;

import lombok.Data;

@Data
public class UpcomingMatch {

    private String home;
    private String away;

    private String league;

    private String date;
    private String time;

    private String country;
}