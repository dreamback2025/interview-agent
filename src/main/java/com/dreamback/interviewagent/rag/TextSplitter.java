package com.dreamback.interviewagent.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 笔记切分器：**标题优先**，其次段落，代码块内不切，超长再滑窗。
 *
 * 顺序很关键：先按 Markdown 标题切成小节（保证一个 chunk 只讲一个主题），
 * 小节内再按段落合并到 MAX_CHARS，仍超长才滑窗。否则 1400 字的笔记只会被切出 2-3 段，
 * 多个主题混在一起，检索效果会明显变差（这是实测踩过的坑）。
 */
@Component
public class TextSplitter {

    private static final int MAX_CHARS = 600;
    private static final int OVERLAP = 100;

    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        for (String section : sections(text)) {
            String s = section.strip();
            if (s.isEmpty()) {
                continue;
            }
            if (s.length() <= MAX_CHARS) {
                chunks.add(s);
            } else {
                chunks.addAll(splitLongSection(s));
            }
        }
        return chunks;
    }

    /** 按 Markdown 标题切小节；代码块内的 # 不当标题 */
    private List<String> sections(String text) {
        List<String> sections = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean inCode = false;

        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inCode = !inCode;
            } else if (!inCode && isHeading(trimmed) && buf.length() > 0) {
                sections.add(buf.toString());
                buf.setLength(0);
            }
            buf.append(line).append('\n');
        }
        if (buf.length() > 0) {
            sections.add(buf.toString());
        }
        return sections;
    }

    /** ATX 标题（# / ## / ### …）视为小节边界 */
    private boolean isHeading(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '#') {
            i++;
        }
        return i > 0 && i <= 6 && i < line.length() && line.charAt(i) == ' ';
    }

    /** 超长小节：标题保留在首块，正文按段落合并，仍超长则滑窗 */
    private List<String> splitLongSection(String section) {
        String heading = "";
        String body = section;
        int nl = section.indexOf('\n');
        if (nl > 0 && isHeading(section.substring(0, nl).strip())) {
            heading = section.substring(0, nl).strip();
            body = section.substring(nl + 1);
        }
        List<String> parts = splitPlain(body);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            out.add(i == 0 && !heading.isEmpty() ? heading + "\n" + parts.get(i) : parts.get(i));
        }
        return out;
    }

    private List<String> splitPlain(String section) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String raw : section.split("\\n{2,}")) {
            String part = raw.strip();
            if (part.isEmpty()) {
                continue;
            }
            if (buf.length() + part.length() + 2 <= MAX_CHARS) {
                if (buf.length() > 0) {
                    buf.append("\n\n");
                }
                buf.append(part);
            } else {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                if (part.length() <= MAX_CHARS) {
                    buf.append(part);
                } else {
                    out.addAll(slide(part));
                }
            }
        }
        if (buf.length() > 0) {
            out.add(buf.toString());
        }
        return out;
    }

    private List<String> slide(String text) {
        List<String> out = new ArrayList<>();
        int step = Math.max(1, MAX_CHARS - OVERLAP);
        for (int i = 0; i < text.length(); i += step) {
            out.add(text.substring(i, Math.min(i + MAX_CHARS, text.length())));
        }
        return out;
    }
}
