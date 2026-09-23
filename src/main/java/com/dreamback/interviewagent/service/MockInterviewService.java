package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.dto.MockAnswerRequest;
import com.dreamback.interviewagent.dto.MockAnswerResult;
import com.dreamback.interviewagent.dto.MockFinishResult;
import com.dreamback.interviewagent.dto.MockQuestionSet;
import com.dreamback.interviewagent.dto.MockSessionDetail;
import com.dreamback.interviewagent.dto.MockStartRequest;
import com.dreamback.interviewagent.dto.MockStartResponse;
import com.dreamback.interviewagent.entity.MockSession;
import com.dreamback.interviewagent.entity.MockTurn;
import com.dreamback.interviewagent.llm.LlmService;
import com.dreamback.interviewagent.repository.MockSessionRepository;
import com.dreamback.interviewagent.repository.MockTurnRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 模拟面试。出题 -> 逐题作答 -> LLM 打分并追问 -> 结束汇总，全程落库。
 */
@Service
@RequiredArgsConstructor
public class MockInterviewService {

    private static final String ROLE_INTERVIEWER = "INTERVIEWER";
    private static final String ROLE_CANDIDATE = "CANDIDATE";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_FINISHED = "FINISHED";

    private static final String QUESTION_SYSTEM_PROMPT = """
            你是一名目标岗位的面试官。根据给定的 JD 生成模拟面试题。
            要求：
            1. 题目必须贴合 JD 中明确提到的技术栈和职责，不要出泛泛的"自我介绍"类题目；
            2. 覆盖 JD 的核心要求，难度有梯度（简单/中等/困难）；
            3. intent 写清楚这道题想考察什么，hint 只给作答方向，不要给完整答案；
            4. 全部使用中文。
            """;

    private static final String GRADE_SYSTEM_PROMPT = """
            你是一名严格的面试官，正在给候选人的回答打分并决定要不要追问。
            要求：
            1. score 是 0-100 的整数：答到关键点且能展开给 75-100，答了但浮于表面给 50-74，答错或答不上来给 0-49；
            2. feedback 要具体到"缺了哪个点"，不要写"回答得不错"这种空话；
            3. followUp 只在回答浮于表面时给一道追问（顺着他没讲清的点继续问），
               如果回答已经足够深入，followUp 必须是空字符串，表示这道题问完了；
            4. 同一道题最多追问两次，已经是第二次回答时必须结束本题（followUp 留空）；
            5. 全部使用中文。
            """;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一名面试官，刚结束一场模拟面试。请根据完整对话给出总结。
            要求：总分 0-100；comment 一句话总体评价；strengths 和 improvements 各列 2-3 条，必须具体。全部使用中文。
            """;

    private final LlmService llmService;
    private final MockSessionRepository sessionRepository;
    private final MockTurnRepository turnRepository;

    /** 出题并建会话 */
    @Transactional
    public MockStartResponse start(MockStartRequest req) {
        String userPrompt = "目标 JD：\n" + req.getJd()
                + "\n\n需要生成 " + req.getCount() + " 道题。"
                + (req.getFocus() == null || req.getFocus().isBlank() ? "" : "\n重点考察方向：" + req.getFocus());
        MockQuestionSet set = llmService.structured(
                QUESTION_SYSTEM_PROMPT, userPrompt, MockQuestionSet.class, () -> heuristic(req));

        MockSession session = new MockSession();
        session.setTargetJd(req.getJd());
        session.setFocus(req.getFocus());
        session.setStatus(STATUS_ACTIVE);
        MockSession saved = sessionRepository.save(session);

        for (int i = 0; i < set.getQuestions().size(); i++) {
            MockQuestionSet.MockQuestion q = set.getQuestions().get(i);
            MockTurn t = new MockTurn();
            t.setSession(saved);
            t.setTurnIndex(i);
            t.setRole(ROLE_INTERVIEWER);
            t.setContent(q.getQuestion());
            turnRepository.save(t);
        }

        MockStartResponse resp = new MockStartResponse();
        resp.setSessionId(saved.getId());
        resp.getQuestions().addAll(set.getQuestions());
        resp.setMessage("共 " + set.getQuestions().size() + " 题，逐题作答即可");
        return resp;
    }

    /** 作答 -> 打分 -> 追问 */
    @Transactional
    public MockAnswerResult answer(MockAnswerRequest req) {
        MockSession session = sessionRepository.findById(req.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模拟面试不存在: " + req.getSessionId()));

        List<MockTurn> turns = turnRepository.findBySessionIdOrderByTurnIndexAscIdAsc(session.getId());
        String question = currentQuestion(turns, req.getQuestionIndex());
        long answeredTimes = turns.stream()
                .filter(t -> t.getTurnIndex() == req.getQuestionIndex() && ROLE_CANDIDATE.equals(t.getRole()))
                .count();

        String userPrompt = "目标 JD：\n" + session.getTargetJd()
                + "\n\n题目：" + question
                + "\n\n候选人的回答（第 " + (answeredTimes + 1) + " 次回答本题）：\n" + req.getAnswer()
                + (answeredTimes >= 2 ? "\n\n注意：这已经是本题第三次回答，必须结束本题，followUp 留空。" : "");

        MockAnswerResult result = llmService.structured(
                GRADE_SYSTEM_PROMPT, userPrompt, MockAnswerResult.class,
                () -> heuristicGrade(req.getAnswer(), answeredTimes));

        // 落库：候选人回答 + 得分/反馈
        MockTurn answerTurn = new MockTurn();
        answerTurn.setSession(session);
        answerTurn.setTurnIndex(req.getQuestionIndex());
        answerTurn.setRole(ROLE_CANDIDATE);
        answerTurn.setContent(req.getAnswer());
        answerTurn.setScore(result.getScore());
        answerTurn.setFeedback(result.getFeedback());
        turnRepository.save(answerTurn);

        String followUp = result.getFollowUp() == null ? "" : result.getFollowUp().trim();
        result.setFollowUp(followUp);
        result.setFinished(followUp.isEmpty());
        if (!followUp.isEmpty()) {
            MockTurn fu = new MockTurn();
            fu.setSession(session);
            fu.setTurnIndex(req.getQuestionIndex());
            fu.setRole(ROLE_INTERVIEWER);
            fu.setContent(followUp);
            turnRepository.save(fu);
        }
        return result;
    }

    /** 结束并汇总 */
    @Transactional
    public MockFinishResult finish(Long sessionId) {
        MockSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模拟面试不存在: " + sessionId));
        List<MockTurn> turns = turnRepository.findBySessionIdOrderByTurnIndexAscIdAsc(sessionId);

        StringBuilder dialog = new StringBuilder();
        for (MockTurn t : turns) {
            dialog.append(ROLE_INTERVIEWER.equals(t.getRole()) ? "面试官：" : "候选人：")
                    .append(t.getContent()).append('\n');
            if (t.getScore() != null) {
                dialog.append("（本轮得分 ").append(t.getScore()).append("）\n");
            }
        }

        MockFinishResult result = llmService.structured(
                SUMMARY_SYSTEM_PROMPT,
                "目标 JD：\n" + session.getTargetJd() + "\n\n完整对话：\n" + dialog,
                MockFinishResult.class,
                () -> heuristicSummary(turns));

        session.setStatus(STATUS_FINISHED);
        session.setTotalScore(result.getTotalScore());
        session.setSummary(result.getComment());
        sessionRepository.save(session);
        return result;
    }

    @Transactional(readOnly = true)
    public MockSessionDetail detail(Long sessionId) {
        MockSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模拟面试不存在: " + sessionId));
        MockSessionDetail dto = new MockSessionDetail();
        dto.setSessionId(session.getId());
        dto.setTargetJd(session.getTargetJd());
        dto.setFocus(session.getFocus());
        dto.setStatus(session.getStatus());
        dto.setTotalScore(session.getTotalScore());
        dto.setSummary(session.getSummary());
        dto.setCreatedAt(session.getCreatedAt());
        for (MockTurn t : turnRepository.findBySessionIdOrderByTurnIndexAscIdAsc(sessionId)) {
            MockSessionDetail.TurnView v = new MockSessionDetail.TurnView();
            v.setId(t.getId());
            v.setTurnIndex(t.getTurnIndex());
            v.setRole(t.getRole());
            v.setContent(t.getContent());
            v.setScore(t.getScore());
            v.setFeedback(t.getFeedback());
            dto.getTurns().add(v);
        }
        return dto;
    }

    /** 某题当前要回答的问题（取该题最后一条面试官发言，可能是追问） */
    private String currentQuestion(List<MockTurn> turns, int index) {
        String last = null;
        for (MockTurn t : turns) {
            if (t.getTurnIndex() == index && ROLE_INTERVIEWER.equals(t.getRole())) {
                last = t.getContent();
            }
        }
        if (last == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + (index + 1) + " 题不存在");
        }
        return last;
    }

    /* ---------------- 离线兜底 ---------------- */

    private MockQuestionSet heuristic(MockStartRequest req) {
        String jd = req.getJd().toLowerCase();
        List<String[]> pool = new ArrayList<>();
        pool.add(new String[]{"讲一下 JVM 内存模型和垃圾回收机制，你们线上用的什么收集器？", "考察 JVM 底层掌握度", "中等", "按 内存区域 -> 回收算法 -> 收集器 -> 调优参数 四层展开"});
        pool.add(new String[]{"synchronized 和 ReentrantLock 的区别，什么场景选哪个？", "考察并发基础", "中等", "从实现、可中断、公平性、条件队列四个维度对比"});
        pool.add(new String[]{"MySQL 索引失效有哪些典型场景？怎么定位慢查询？", "考察数据库调优", "中等", "结合 explain 和最左前缀原则回答"});
        pool.add(new String[]{"Redis 缓存穿透、击穿、雪崩分别怎么解决？", "考察缓存设计", "中等", "三类问题要分别给方案，不要混着说"});
        pool.add(new String[]{"Spring 循环依赖是怎么解决的？三级缓存各存什么？", "考察框架原理", "困难", "按 Bean 创建流程和三级缓存角色说明"});
        pool.add(new String[]{"设计一个秒杀系统，你会怎么分层？瓶颈在哪？", "考察系统设计", "困难", "从流量分层、库存扣减一致性、兜底降级三块讲"});
        pool.add(new String[]{"线程池核心参数怎么配？队列选什么？拒绝策略怎么选？", "考察并发实战", "简单", "结合 CPU 密集 / IO 密集分别说明"});
        pool.add(new String[]{"讲一次你线上排查问题的完整过程。", "考察实战与复盘能力", "简单", "按 现象 -> 定位 -> 根因 -> 修复 -> 防复发 讲"});

        List<String[]> picked = new ArrayList<>();
        String[] keywords = {"jvm", "并发", "concurren", "thread", "mysql", "索引", "redis", "缓存", "spring", "秒杀", "高并发", "分布式", "微服务", "kafka", "mq"};
        for (String[] item : pool) {
            String text = (item[0] + item[1]).toLowerCase();
            for (String k : keywords) {
                if (text.contains(k) && jd.contains(k)) {
                    picked.add(item);
                    break;
                }
            }
        }
        for (String[] item : pool) {
            if (picked.size() >= req.getCount()) {
                break;
            }
            if (!picked.contains(item)) {
                picked.add(item);
            }
        }

        MockQuestionSet set = new MockQuestionSet();
        for (int i = 0; i < Math.min(req.getCount(), picked.size()); i++) {
            String[] item = picked.get(i);
            MockQuestionSet.MockQuestion q = new MockQuestionSet.MockQuestion();
            q.setQuestion(item[0]);
            q.setIntent(item[1]);
            q.setDifficulty(item[2]);
            q.setHint(item[3] + "（stub 模式，配置 DEEPSEEK_API_KEY 后由模型按 JD 定制出题）");
            set.getQuestions().add(q);
        }
        return set;
    }

    private MockAnswerResult heuristicGrade(String answer, long answeredTimes) {
        MockAnswerResult r = new MockAnswerResult();
        int len = answer == null ? 0 : answer.length();
        r.setScore(Math.max(0, Math.min(90, 40 + len / 8)));
        r.setFeedback("[stub 模式] 回答长度 " + len + " 字。真实评分需要配置 DEEPSEEK_API_KEY，"
                + "模型会指出具体缺失的知识点。");
        boolean needFollowUp = answeredTimes < 1 && len < 200;
        r.setFollowUp(needFollowUp ? "（stub 追问）能再展开讲讲其中的关键流程吗？" : "");
        r.setFinished(!needFollowUp);
        return r;
    }

    private MockFinishResult heuristicSummary(List<MockTurn> turns) {
        List<Integer> scores = turns.stream()
                .filter(t -> ROLE_CANDIDATE.equals(t.getRole()) && t.getScore() != null)
                .map(MockTurn::getScore)
                .toList();
        int avg = scores.isEmpty() ? 0 : (int) scores.stream().mapToInt(Integer::intValue).average().orElse(0);
        MockFinishResult r = new MockFinishResult();
        r.setTotalScore(avg);
        r.setComment("[stub 模式] 共作答 " + scores.size() + " 轮，平均分 " + avg
                + "。配置 DEEPSEEK_API_KEY 后由模型给出真实总评。");
        r.getStrengths().add("（stub）需要真实模型才能分析");
        r.getImprovements().add("（stub）需要真实模型才能分析");
        return r;
    }
}
