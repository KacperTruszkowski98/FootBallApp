package com.example.dto;

import lombok.Data;

import java.util.List;

@Data
public class ResultDto {

    private int seasonYear;
    private List<EventGroupDto> groups;
}