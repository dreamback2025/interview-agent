package com.dreamback.interviewagent.llm;

/** 去掉模型返回里常见的 ```json 代码块包裹，避免解析失败。 */
public final class JsonUtil {

    private JsonUtil() {
    }

    public static String cleanJson(String raw) {
        if (raw == null) {
            throw new IllegalStateException("模型返回为空");
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstLineEnd = s.indexOf('\n');
            s = firstLineEnd > 0 ? s.substring(firstLineEnd + 1) : s.substring(3);
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }
}
