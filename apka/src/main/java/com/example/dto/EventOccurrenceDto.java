package com.example.dto;

import lombok.Data;

@Data
public class EventOccurrenceDto {

    private String home;
    private String away;
    private String event;      // np "1/2"
    private int round;
}