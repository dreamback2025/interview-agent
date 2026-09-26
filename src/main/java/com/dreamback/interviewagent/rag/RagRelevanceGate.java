package com.dreamback.interviewagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 相关性闸门：判断「检索到的内容是否确实覆盖了这个问题」。
 *
 * <p><b>在产品里的用途</b>：分析面试时，对每道答错的题判断「用户自己的笔记里有没有这个知识点」——
 * 有 = 不是知识缺口，该重新消化笔记；没有 = 真正的知识缺口，该补一篇。
 *
 * <p>早期一版把它用作「问答拒答」（系统判断能不能回答用户），但本项目并没有
 * 「系统替用户作答」的场景（系统只做两件事：分析、以及把匹配的笔记列给用户看），
 * 因此已纠正为当前的用途 —— 判定结果服务于**分析报告**，不服务于「答不答」。
 *
 * <p><b>为什么不能用单一相似度阈值</b>：分级负样本实测给出反例 ——
 * 正样本 Top1 最低分 38.3%，而难负样本（L2~L4）最高分 80.1%，**重叠 41.8 个百分点**。
 * 任何单一阈值都会二选一地失败。
 *
 * <p><b>为什么第二个信号是「覆盖率」而不是「关键词命中数」</b>：
 * 一开始用关键词通道的命中文档数，实测发现它**完全无效** —— 关键词通道是 OR 查询
 * （{@code tok1 | tok2 | ...}），任意 token 匹配即命中，而「消息」「处理」「怎么」这类
 * 通用词在语料里到处都有。跨域的「Kafka 消息积压该怎么处理」照样命中 6 个文档，
 * 正负样本的命中数没有区分度（正样本 10~15、负样本 6~15）。
 *
 * <p>换成**覆盖率**后才有区分力：查询分词后，有多少比例的 token 出现在 **top1 文档**里。
 * 正样本的词集中在一个知识点里，跨域问题的词散落在不同文档 → 没有任何文档能覆盖它。
 *
 * <p><b>判定规则</b>（阈值可用配置覆盖，见 application.yml 的 app.rag.gate.*）：
 * <pre>
 *   拒绝 A：vecTop1 &lt; vecLow 且 coverage &lt; covLow  —— 语义弱且词也对不上
 *   拒绝 B：vecTop1 &lt; vecMid 且 coverage &lt; covMid  —— 语义偏弱且覆盖不足
 *   其余：放行
 * </pre>
 *
 * <p>这是**保守策略**：宁可漏答（用户可换个问法），也不要硬答一个语料里没有的内容。
 */
@Slf4j
@Component
public class RagRelevanceGate {

    /** 关掉则永远 confident=true（等于退回 /search 的行为） */
    @Value("${app.rag.gate.enabled:true}")
    private boolean enabled = true;

    /** 低分区：低于此相似度且覆盖率也低 → 直接拒绝 */
    @Value("${app.rag.gate.vec-low:0.40}")
    private double vecLow = 0.40;

    /** 中分区：低于此相似度需要覆盖率兜底 */
    @Value("${app.rag.gate.vec-mid:0.58}")
    private double vecMid = 0.58;

    /** 低分区要求的覆盖率下限 */
    @Value("${app.rag.gate.cov-low:0.15}")
    private double covLow = 0.15;

    /** 中分区要求的覆盖率下限 */
    @Value("${app.rag.gate.cov-mid:0.35}")
    private double covMid = 0.35;

    public record Decision(boolean confident, String reason) {
        public static Decision accept() {
            return new Decision(true, null);
        }
    }

    /**
     * @param vecTop1   向量通道 top1 相似度（0~1）
     * @param coverage  关键词覆盖率（0~1）
     */
    public Decision decide(double vecTop1, double coverage) {
        if (!enabled) {
            return Decision.accept();
        }
        if (vecTop1 < vecLow && coverage < covLow) {
            return new Decision(false,
                    "没有找到相关内容（语义相似度 %.0f%%、关键词覆盖 %.0f%% 都偏低）"
                            .formatted(vecTop1 * 100, coverage * 100));
        }
        if (vecTop1 < vecMid && coverage < covMid) {
            return new Decision(false,
                    "找到的内容覆盖不足（语义相似度 %.0f%%、关键词覆盖 %.0f%%）"
                            .formatted(vecTop1 * 100, coverage * 100));
        }
        return Decision.accept();
    }

    /** 供评测/调试：当前阈值 */
    public String describe() {
        return "enabled=%s vecLow=%.2f vecMid=%.2f covLow=%.2f covMid=%.2f"
                .formatted(enabled, vecLow, vecMid, covLow, covMid);
    }
}
