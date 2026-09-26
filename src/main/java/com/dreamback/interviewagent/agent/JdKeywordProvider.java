package com.dreamback.interviewagent.agent;

import java.util.List;

/**
 * JD 提炼用的关键词来源。
 *
 * <p>抽成接口是为了**可扩展**：默认实现合并「配置关键词 + 知识库标签」，
 * 将来要接数据库词表、外部词库服务，只需再加一个实现类替换，
 * 提炼逻辑（{@code InterviewTools#getJdRequirements}）一行都不用动。
 */
public interface JdKeywordProvider {

    /** 当前生效的关键词（已去重、保序） */
    List<String> keywords();
}
