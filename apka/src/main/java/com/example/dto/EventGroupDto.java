package com.example.dto;

import lombok.Data;

import java.util.List;

@Data
public class EventGroupDto {

    private String groupName; // np "1/2 + 2/1"
    private long count; // 👈 liczba wystąpień
    private List<EventOccurrenceDto> occurrences;
}