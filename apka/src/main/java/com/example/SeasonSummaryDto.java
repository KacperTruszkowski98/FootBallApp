package com.example;

import com.example.dto.EventGroupDto;
import lombok.Data;

import java.util.List;

@Data
public class SeasonSummaryDto {

    private String country;
    private Integer season;
    private List<EventGroupDto> groups;
}