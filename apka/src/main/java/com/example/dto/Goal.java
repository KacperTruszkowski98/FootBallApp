package com.example.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Goal {

    private String minute;
    private String scorer;
    private String team; // "home" lub "away"
}
