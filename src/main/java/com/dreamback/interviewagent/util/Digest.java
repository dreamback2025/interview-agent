package com.dreamback.interviewagent.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 内容指纹：用于缓存 key 与幂等键，避免把整段文本塞进 key。 */
public final class Digest {

    private Digest() {
    }

    /** SHA-256 前 8 字节转十六进制（16 字符），长度与碰撞概率足够缓存场景使用 */
    public static String sha256Short(String s) {
        if (s == null) {
            return "null";
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在任何 JDK 上都存在，走到这里说明环境异常，退化即可
            return Integer.toHexString(s.hashCode());
        }
    }
}
