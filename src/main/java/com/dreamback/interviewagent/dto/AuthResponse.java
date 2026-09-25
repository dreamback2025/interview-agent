package com.dreamback.interviewagent.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthResponse {

    private String token;
    private String tokenType = "Bearer";
    private long expiresIn;
    private Long userId;
    private String username;
    private String displayName;
    private String role;
}
