package com.example.dto;

import lombok.Data;

import java.util.List;

@Data
public class EventStats {

    private String event;           // np "1/2"
    private List<Integer> roundDiffs;
    private double avgDiff;
}