package com.dreamback.interviewagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.dreamback.interviewagent.config.JdProperties;
import com.dreamback.interviewagent.repository.InterviewQuestionRepository;
import com.dreamback.interviewagent.security.UserContext;
import com.dreamback.interviewagent.service.KnowledgeService;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterviewToolsTest {

    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final InterviewQuestionRepository questions = mock(InterviewQuestionRepository.class);
    private final UserContext userContext = mock(UserContext.class);

    /** 用指定关键词造一个 tools（关掉动态来源，让结果只由关键词决定） */
    private InterviewTools tools(List<String> keywords, int max) {
        JdProperties p = new JdProperties();
        p.setKeywords(keywords);
        p.setDynamicTags(false);
        p.setMaxRequirements(max);
        p.setKeywordsCacheMs(0);
        return new InterviewTools(knowledge, questions, userContext, () -> keywords, p);
    }

    private InterviewTools tools(List<String> keywords) {
        return tools(keywords, 12);
    }

    @Test
    void 正常命中并按关键词顺序返回() {
        List<String> r = tools(List.of("Java", "MySQL", "并发"))
                .getJdRequirements("要求熟悉 Java，掌握 MySQL 索引，有高并发经验");
        assertThat(r).containsExactly("Java", "MySQL", "并发");
    }

    @Test
    void ascii词要求边界_Java不该命中JavaScript() {
        // 这是重构前 contains 分支的真实误判：JD 写的是前端岗位，却提炼出 "Java"
        assertThat(tools(List.of("Java")).getJdRequirements("熟悉 JavaScript 与 TypeScript"))
                .doesNotContain("Java");
    }

    @Test
    void ascii词要求边界_GC不该命中GCC() {
        assertThat(tools(List.of("GC")).getJdRequirements("熟练使用 GCC 编译工具链"))
                .doesNotContain("GC");
    }

    @Test
    void 独立的词即使被别的词包着也能命中() {
        assertThat(tools(List.of("Java")).getJdRequirements("后端用 Java，前端用 JavaScript"))
                .containsExactly("Java");
    }

    @Test
    void 中文关键词用包含匹配() {
        assertThat(tools(List.of("高并发", "索引")).getJdRequirements("有过高并发系统调优经验，熟悉索引设计"))
                .containsExactly("高并发", "索引");
    }

    @Test
    void 大小写不敏感() {
        assertThat(tools(List.of("MySQL")).getJdRequirements("熟悉 mysql 与 redis"))
                .containsExactly("MySQL");
    }

    @Test
    void 空JD给出提示而不是抛错() {
        assertThat(tools(List.of("Java")).getJdRequirements(null)).containsExactly("（JD 为空）");
        assertThat(tools(List.of("Java")).getJdRequirements("   ")).containsExactly("（JD 为空）");
    }

    @Test
    void 关键词全不命中时退化为切分职责短语() {
        String jd = "负责交易系统后端开发工作。要求三年以上服务端经验。熟悉分布式架构设计。";
        List<String> r = tools(List.of("Go", "Rust")).getJdRequirements(jd);
        assertThat(r).isNotEmpty().allSatisfy(p -> assertThat(p.length()).isGreaterThanOrEqualTo(6));
    }

    @Test
    void maxRequirements限制输出条数() {
        String jd = "Java JVM GC 并发 线程池 锁 MySQL 索引 Redis 缓存 Spring Kafka 分布式";
        assertThat(tools(List.of("Java", "JVM", "GC", "并发", "线程池", "锁", "MySQL", "索引",
                "Redis", "缓存", "Spring", "Kafka", "分布式"), 5).getJdRequirements(jd))
                .hasSize(5);
    }
}
