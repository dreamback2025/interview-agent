package com.dreamback.interviewagent.service.impl;

import com.dreamback.interviewagent.dto.AuthResponse;
import com.dreamback.interviewagent.dto.LoginRequest;
import com.dreamback.interviewagent.dto.RegisterRequest;
import com.dreamback.interviewagent.entity.AppUser;
import com.dreamback.interviewagent.repository.AppUserRepository;
import com.dreamback.interviewagent.security.JwtService;
import com.dreamback.interviewagent.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 鉴权服务实现（注册 / 登录）。详见 {@link AuthService}。 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在: " + req.getUsername());
        }
        AppUser u = new AppUser();
        u.setUsername(req.getUsername());
        u.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        u.setDisplayName(req.getDisplayName() == null || req.getDisplayName().isBlank()
                ? req.getUsername() : req.getDisplayName());
        u.setRole("USER");
        AppUser saved = userRepository.save(u);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        AppUser u = userRepository.findByUsername(req.getUsername())
                // 用户名不存在与密码错误返回同一个提示，避免账号枚举
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (!passwordEncoder.matches(req.getPassword(), u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return toResponse(u);
    }

    private AuthResponse toResponse(AppUser u) {
        AuthResponse r = new AuthResponse();
        r.setToken(jwtService.issue(u.getId(), u.getUsername(), u.getRole()));
        r.setExpiresIn(jwtService.ttlSeconds());
        r.setUserId(u.getId());
        r.setUsername(u.getUsername());
        r.setDisplayName(u.getDisplayName());
        r.setRole(u.getRole());
        return r;
    }
}
