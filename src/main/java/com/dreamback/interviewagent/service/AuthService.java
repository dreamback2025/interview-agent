package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.dto.AuthResponse;
import com.dreamback.interviewagent.dto.LoginRequest;
import com.dreamback.interviewagent.dto.RegisterRequest;

/**
 * 鉴权服务：注册 / 登录。
 *
 * <p>实现见 {@link com.dreamback.interviewagent.service.impl.AuthServiceImpl}。
 * 密码用 BCrypt 哈希；登录失败对「用户名不存在」与「密码错误」返回同一提示，避免账号枚举。
 */
public interface AuthService {

    /** 注册：成功即返回 token */
    AuthResponse register(RegisterRequest req);

    /** 登录换取 token */
    AuthResponse login(LoginRequest req);
}
