package com.dreamback.interviewagent.service;

import com.dreamback.interviewagent.cache.CacheService;
import com.dreamback.interviewagent.dto.IngestRequest;
import com.dreamback.interviewagent.dto.IngestResponse;
import com.dreamback.interviewagent.dto.SearchResult;
import com.dreamback.interviewagent.entity.KnowledgeDoc;
import com.dreamback.interviewagent.rag.TextSplitter;
import com.dreamback.interviewagent.repository.KnowledgeDocRepository;
import com.dreamback.interviewagent.util.Digest;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 知识库：切分 -> embedding -> pgvector 入库 -> 相似度检索。 */
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final TextSplitter splitter;
    private final KnowledgeDocRepository docRepository;
    /** RAG 关闭或向量库不可用时拿不到 bean，用 ObjectProvider 延迟取，避免拖垮应用启动 */
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    /** 缓存同样可关闭，用 ObjectProvider 取 */
    private final ObjectProvider<CacheService> cacheProvider;

    private static final String SEARCH_CACHE_PREFIX = "kbsearch:";

    public IngestResponse ingest(IngestRequest req) {
        List<String> chunks = splitter.split(req.getContent());
        if (chunks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容为空，无可入库片段");
        }

        String docId = UUID.randomUUID().toString();
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("docId", docId);
            meta.put("title", nvl(req.getTitle()));
            meta.put("source", nvl(req.getSource()));
            meta.put("tags", nvl(req.getTags()));
            meta.put("chunkIndex", i);
            docs.add(new Document(chunks.get(i), meta));
        }
        store().add(docs);

        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setDocId(docId);
        doc.setTitle(req.getTitle());
        doc.setSource(req.getSource());
        doc.setTags(req.getTags());
        doc.setChunkCount(chunks.size());
        docRepository.save(doc);

        evictSearchCache();
        return new IngestResponse(docId, chunks.size(), "已入库，共切出 " + chunks.size() + " 个片段");
    }

    public List<SearchResult> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q 不能为空");
        }

        // 热点查询缓存：省掉一次 embedding + 向量检索（实测这两步占检索耗时的绝大部分）
        CacheService cache = cacheProvider.getIfAvailable();
        String cacheKey = SEARCH_CACHE_PREFIX + topK + ":" + Digest.sha256Short(query);
        if (cache != null) {
            List<SearchResult> cached = cache.getTyped(cacheKey, new TypeReference<List<SearchResult>>() { });
            if (cached != null) {
                return cached;
            }
        }

        var hits = store().similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build());

        List<SearchResult> out = new ArrayList<>();
        for (Document d : hits) {
            SearchResult r = new SearchResult();
            r.setContent(d.getText());
            r.setScore(scoreOf(d));
            r.setDocId(str(d.getMetadata().get("docId")));
            r.setTitle(str(d.getMetadata().get("title")));
            r.setSource(str(d.getMetadata().get("source")));
            r.setTags(str(d.getMetadata().get("tags")));
            out.add(r);
        }
        if (cache != null) {
            cache.put(cacheKey, out);
        }
        return out;
    }

    /** 库内容变化后，检索结果全部失效 */
    private void evictSearchCache() {
        CacheService cache = cacheProvider.getIfAvailable();
        if (cache != null) {
            cache.evictByPrefix(SEARCH_CACHE_PREFIX);
        }
    }

    public List<KnowledgeDoc> docs() {
        return docRepository.findByOrderByCreatedAtDesc();
    }

    /** 删除一篇文档：先删 pgvector 里该 docId 的所有 chunk，再删目录记录 */
    @Transactional
    public void deleteDoc(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "docId 不能为空");
        }
        Filter.Expression expr = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("docId"),
                new Filter.Value(docId));
        store().delete(expr);
        docRepository.findByDocId(docId).ifPresent(docRepository::delete);
        evictSearchCache();
    }

    private VectorStore store() {
        VectorStore vs;
        try {
            vs = vectorStoreProvider.getIfAvailable();
        } catch (Exception e) {
            // 向量库 bean 是懒加载的，创建失败（Postgres/Ollama 没起）会在这里才暴露
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RAG 不可用：请确认 PostgreSQL(pgvector) 与 Ollama 已启动，库 interview_vector 已建且已 CREATE EXTENSION vector", e);
        }
        if (vs == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RAG 未启用：设置 app.rag.enabled=true 后重启");
        }
        return vs;
    }

    /** Spring AI 返回的是余弦距离，转成更直观的相似度 */
    private static double scoreOf(Document d) {
        Object v = d.getMetadata().get("distance");
        if (v instanceof Number n) {
            return Math.max(0.0, 1.0 - n.doubleValue());
        }
        return 0.0;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
