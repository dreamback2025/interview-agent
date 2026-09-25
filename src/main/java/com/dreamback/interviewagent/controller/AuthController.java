package com.dreamback.interviewagent.controller;

import com.dreamback.interviewagent.dto.AuthResponse;
import com.dreamback.interviewagent.dto.LoginRequest;
import com.dreamback.interviewagent.dto.RegisterRequest;
import com.dreamback.interviewagent.security.UserContext;
import com.dreamback.interviewagent.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 注册 / 登录 / 查看当前身份。这三个接口免鉴权。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserContext userContext;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    /** 校验 token 是否有效（前端刷新页面时用来判断要不要重新登录）。需认证。 */
    @GetMapping("/me")
    public MeView me() {
        return userContext.currentUserId()
                .map(id -> new MeView(id, userContext.currentUsername().orElse(""), "USER"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token 无效或已过期"));
    }

    public record MeView(Long userId, String username, String role) {
    }
}
