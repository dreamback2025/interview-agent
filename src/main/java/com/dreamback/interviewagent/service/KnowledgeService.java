package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.dto.IngestRequest;
import com.dreamback.interviewagent.dto.IngestResponse;
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

    /** 相似度检索；带用户过滤，结果可缓存（key 含 uid） */
    List<SearchResult> search(String query, int topK);

    /** 已导入文档目录（按用户隔离） */
    List<KnowledgeDoc> docs();

    /** 删除文档及其全部 chunk（先校验归属） */
    void deleteDoc(String docId);
}
