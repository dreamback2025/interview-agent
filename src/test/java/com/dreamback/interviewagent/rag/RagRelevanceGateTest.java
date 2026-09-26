package com.dreamback.interviewagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 闸门规则单测。默认阈值：vecLow=0.40 vecMid=0.58 covLow=0.15 covMid=0.35。
 *
 * <p>规则：`vec < vecLow && cov < covLow` → 拒；`vec < vecMid && cov < covMid` → 拒；其余放行。
 */
class RagRelevanceGateTest {

    private final RagRelevanceGate gate = new RagRelevanceGate();

    @Test
    void 语义与覆盖率双低_拒绝() {
        // vec 0.35 < 0.40 且 cov 0.10 < 0.15
        RagRelevanceGate.Decision d = gate.decide(0.35, 0.10);
        assertThat(d.confident()).isFalse();
        assertThat(d.reason()).contains("没有找到相关内容");
    }

    @Test
    void 语义高但覆盖率低_放行() {
        // vec 0.75 ≥ vecMid → 两个分支都不成立。
        // 这是有意的：语义足够强时，即使关键词没覆盖（比如换了一种说法），也不该拒
        assertThat(gate.decide(0.75, 0.05).confident()).isTrue();
    }

    @Test
    void 覆盖率足够高_语义中等也放行() {
        // vec 0.50 < 0.58 但 cov 0.40 ≥ 0.35；且 0.50 不 < 0.40
        assertThat(gate.decide(0.50, 0.40).confident()).isTrue();
    }

    @Test
    void 语义中等且覆盖不足_拒绝() {
        // vec 0.50 < 0.58 且 cov 0.20 < 0.35
        RagRelevanceGate.Decision d = gate.decide(0.50, 0.20);
        assertThat(d.confident()).isFalse();
        assertThat(d.reason()).contains("覆盖不足");
    }

    @Test
    void 边界用严格小于_恰好等于阈值时放行() {
        // vec 0.40 不 < 0.40（规则A 不成立）；cov 0.35 不 < 0.35（规则B 不成立）→ 放行
        assertThat(gate.decide(0.40, 0.35).confident()).isTrue();
        // 只差一点点就会被拒：0.40 < 0.58 成立、0.34 < 0.35 成立 → 拒
        assertThat(gate.decide(0.40, 0.34).confident()).isFalse();
    }

    @Test
    void 两者都为零_拒绝() {
        assertThat(gate.decide(0.0, 0.0).confident()).isFalse();
    }

    @Test
    void 理由里带可读的百分比() {
        RagRelevanceGate.Decision d = gate.decide(0.35, 0.10);
        assertThat(d.reason()).contains("35%").contains("10%");
    }

    @Test
    void describe_包含全部阈值便于排查() {
        assertThat(gate.describe())
                .contains("vecLow=0.40").contains("vecMid=0.58")
                .contains("covLow=0.15").contains("covMid=0.35");
    }
}
