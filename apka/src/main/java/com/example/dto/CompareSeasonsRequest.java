package com.example.dto;

import java.util.List;

public record CompareSeasonsRequest(
        String country,
        List<Integer> seasons
) {}