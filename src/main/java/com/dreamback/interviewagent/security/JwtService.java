package com.dreamback.interviewagent.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与校验（HS256，无状态）。
 *
 * <p>密钥必须外部注入：{@code JWT_SECRET} 环境变量。默认值仅供本地开发，
 * 生产环境务必替换（长度需 ≥ 32 字节，否则 HS256 会拒绝）。
 */
@Slf4j
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    /** HS256 要求的最小密钥长度（字节） */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 开发与演示用的兜底密钥。**必须与 application.yml / docker-compose.yml 里的默认占位保持一致**。
     * 只在 JWT_SECRET 未配置（或配置为空字符串）时使用，并打 WARN 提醒。
     */
    private static final String DEV_FALLBACK_SECRET =
            "dev-only-secret-change-me-before-deploy-0123456789abcdef";

    public JwtService(@Value("${app.security.jwt.secret:}") String secret,
                      @Value("${app.security.jwt.ttl-seconds:86400}") long ttlSeconds) {
        // 空值兜底：环境变量被设成空字符串时 Spring 占位符不会回退默认值
        //（${VAR:默认} 只在「未设置」时回退），这里在代码层再兜一次，保证开箱能跑
        boolean blank = secret == null || secret.isBlank();
        String used = blank ? DEV_FALLBACK_SECRET : secret;
        if (blank) {
            log.warn("JWT_SECRET 未配置（或为空），正在使用开发用默认密钥 —— "
                    + "生产环境必须通过 JWT_SECRET 注入随机串：openssl rand -base64 48");
        }

        byte[] bytes = used.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            // 配置了但太短 = 明确的弱密钥，快速失败，不静默降级
            throw new IllegalStateException(
                    "JWT 密钥太短（" + bytes.length + " 字节），HS256 要求 ≥ 32 字节。"
                            + "请通过环境变量 JWT_SECRET 注入更长的随机串。");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(Long userId, String username, String role) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttlSeconds * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    /** 解析并验签；无效或过期会抛 JwtException，由调用方决定如何处理 */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }
}
