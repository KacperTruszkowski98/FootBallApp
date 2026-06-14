package com.example.dto;

public record UpcomingMatchesRequest(
        int year,
        int month,
        int day
) {
}