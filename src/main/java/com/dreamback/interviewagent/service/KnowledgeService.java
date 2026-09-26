package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.dto.IngestRequest;
import com.dreamback.interviewagent.dto.IngestResponse;
import com.dreamback.interviewagent.dto.RetrievalResult;
import com.dreamback.interviewagent.dto.SearchResult;
import com.dreamback.interviewagent.entity.KnowledgeDoc;
import java.util.List;

/**
 * 知识库服务：切分 → embedding → pgvector 入库 → 相似度检索。
 *
 * <p>实现见 {@link com.dreamback.interviewagent.service.impl.KnowledgeServiceImpl}。
 * 向量 metadata 写入 userId，检索在向量库层就按用户过滤；检索缓存 key 也带 userId。
 */
public interface KnowledgeService {

    /** 笔记入库（切分 + embedding + pgvector） */
    IngestResponse ingest(IngestRequest req);

    /** 相似度检索；带用户过滤，结果可缓存（key 含 uid）。这是给「查看自己的笔记」用的对外入口 */
    List<SearchResult> search(String query, int topK);

    /**
     * 检索 + 判定「笔记是否覆盖了这个问题」，供**分析流程**使用。
     *
     * <p>与 {@link #search} 用同一套召回逻辑，但额外给出 {@code confident}：
     * false 表示「用户笔记里没有这个知识点」—— 分析一道错题时据此区分
     * 「笔记有、只是没答出来」（重新消化笔记）与「笔记没有」（补一篇）。
     *
     * <p>不对外暴露为独立端点：本产品没有「系统替用户回答问题」的场景。
     */
    RetrievalResult retrieveWithSignals(String query, int topK);

    /** 已导入文档目录（按用户隔离） */
    List<KnowledgeDoc> docs();

    /** 删除文档及其全部 chunk（先校验归属） */
    void deleteDoc(String docId);
}
