package com.dreamback.interviewagent.service.impl;

import com.dreamback.interviewagent.cache.CacheService;
import com.dreamback.interviewagent.dto.IngestRequest;
import com.dreamback.interviewagent.dto.IngestResponse;
import com.dreamback.interviewagent.dto.SearchResult;
import com.dreamback.interviewagent.entity.KnowledgeDoc;
import com.dreamback.interviewagent.rag.HybridRetriever;
import com.dreamback.interviewagent.rag.TextSplitter;
import com.dreamback.interviewagent.repository.KnowledgeDocRepository;
import com.dreamback.interviewagent.security.UserContext;
import com.dreamback.interviewagent.service.KnowledgeService;
import com.dreamback.interviewagent.util.Digest;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 知识库（切分 / 入库 / 检索 / 删除）实现。详见 {@link KnowledgeService}。 */
@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {

    private final TextSplitter splitter;
    private final KnowledgeDocRepository docRepository;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<CacheService> cacheProvider;
    private final UserContext userContext;
    private final HybridRetriever hybridRetriever;

    private static final String SEARCH_CACHE_PREFIX = "kbsearch:";

    @Override
    public IngestResponse ingest(IngestRequest req) {
        List<String> chunks = splitter.split(req.getContent());
        if (chunks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容为空，无可入库片段");
        }
        Long uid = userContext.currentUserId().orElse(null);

        String docId = UUID.randomUUID().toString();
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("docId", docId);
            meta.put("title", nvl(req.getTitle()));
            meta.put("source", nvl(req.getSource()));
            meta.put("tags", nvl(req.getTags()));
            meta.put("chunkIndex", i);
            if (uid != null) {
                meta.put("userId", uid);
            }
            docs.add(new Document(chunks.get(i), meta));
        }
        store().add(docs);

        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setDocId(docId);
        doc.setTitle(req.getTitle());
        doc.setSource(req.getSource());
        doc.setTags(req.getTags());
        doc.setChunkCount(chunks.size());
        doc.setUserId(uid);
        docRepository.save(doc);

        // 回填关键词索引（tsvector），供混合检索的关键词通道使用；失败不影响向量检索
        hybridRetriever.indexTokens(docId);

        evictSearchCache();
        return new IngestResponse(docId, chunks.size(), "已入库，共切出 " + chunks.size() + " 个片段");
    }

    @Override
    public List<SearchResult> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q 不能为空");
        }
        Long uid = userContext.currentUserId().orElse(null);

        // 缓存 key 必须带 userId：否则别的用户的检索结果会被缓存命中后返回给当前用户，造成越权。
        // 带上检索模式（h=混合 / v=纯向量）—— 模式切换后结果不同，不能混用同一份缓存。
        CacheService cache = cacheProvider.getIfAvailable();
        String mode = hybridRetriever.isEnabled() ? "h" : "v";
        String cacheKey = SEARCH_CACHE_PREFIX + mode + ":" + uid + ":" + topK + ":" + Digest.sha256Short(query);
        if (cache != null) {
            List<SearchResult> cached = cache.getTyped(cacheKey, new TypeReference<List<SearchResult>>() { });
            if (cached != null) {
                return cached;
            }
        }

        // 候选池放大：给融合留空间（两路各取 topK*3，融合后截取 topK）
        int candidateK = Math.max(topK * 3, topK + 5);

        // 通道一：向量语义检索
        SearchRequest.Builder b = SearchRequest.builder().query(query).topK(candidateK);
        if (uid != null) {
            b.filterExpression(new FilterExpressionBuilder().eq("userId", uid).build());
        }
        List<Document> vecDocs = store().similaritySearch(b.build());

        // 通道二：关键词检索（tsvector + GIN）。不可用时返回空列表，自动退化为纯向量
        List<HybridRetriever.KeywordHit> kwHits = hybridRetriever.keywordSearch(query, uid, candidateK);

        List<SearchResult> out = kwHits.isEmpty()
                ? vecDocs.stream().map(this::toResult).toList()
                : fuse(vecDocs, kwHits, topK);

        if (cache != null) {
            cache.put(cacheKey, out);
        }
        return out;
    }

    /**
     * RRF 融合两路召回并按融合分截取 topK。
     *
     * <p>score 字段的处理：向量通道来的保留**余弦相似度**（语义可比）；
     * 仅被关键词通道召回的，用 ts_rank 映射一个可读分（ts_rank 量级约 0~0.1，乘 10 封顶 1.0）。
     * 排序完全由 RRF 决定 —— 这也是「关键词通道能把同域干扰项挤下去」的机制。
     */
    private List<SearchResult> fuse(List<Document> vecDocs, List<HybridRetriever.KeywordHit> kwHits, int topK) {
        Map<String, Document> vecById = new LinkedHashMap<>();
        for (Document d : vecDocs) {
            vecById.put(normId(d.getId()), d);
        }
        Map<String, HybridRetriever.KeywordHit> kwById = new LinkedHashMap<>();
        for (HybridRetriever.KeywordHit h : kwHits) {
            kwById.put(normId(h.id()), h);
        }

        Map<String, Double> rrf = HybridRetriever.rrfScores(
                List.of(new ArrayList<>(vecById.keySet()), new ArrayList<>(kwById.keySet())));

        List<SearchResult> out = new ArrayList<>();
        for (String id : HybridRetriever.rankedByRrf(rrf)) {
            if (out.size() >= topK) {
                break;
            }
            Document d = vecById.get(id);
            if (d != null) {
                out.add(toResult(d));
            } else {
                HybridRetriever.KeywordHit h = kwById.get(id);
                SearchResult r = new SearchResult();
                r.setContent(h.content());
                r.setScore(Math.min(1.0, h.rank() * 10));
                r.setDocId(h.docId());
                r.setTitle(h.title());
                r.setSource(h.source());
                r.setTags(h.tags());
                out.add(r);
            }
        }
        return out;
    }

    private SearchResult toResult(Document d) {
        SearchResult r = new SearchResult();
        r.setContent(d.getText());
        r.setScore(scoreOf(d));
        r.setDocId(str(d.getMetadata().get("docId")));
        r.setTitle(str(d.getMetadata().get("title")));
        r.setSource(str(d.getMetadata().get("source")));
        r.setTags(str(d.getMetadata().get("tags")));
        return r;
    }

    /** 向量库返回的 id 与 SQL 查出的 id::text 可能一个带连字符一个不带，统一归一化后对齐 */
    private static String normId(String id) {
        return id == null ? "" : id.replace("-", "").toLowerCase();
    }

    @Override
    public List<KnowledgeDoc> docs() {
        Long uid = userContext.currentUserId().orElse(null);
        return uid == null ? docRepository.findAll() : docRepository.findByUserIdOrderByCreatedAtDesc(uid);
    }

    @Override
    @Transactional
    public void deleteDoc(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "docId 不能为空");
        }
        Long uid = userContext.currentUserId().orElse(null);
        Optional<KnowledgeDoc> doc = uid == null
                ? docRepository.findAll().stream().filter(d -> docId.equals(d.getDocId())).findFirst()
                : docRepository.findByDocIdAndUserId(docId, uid);
        if (doc.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + docId);
        }

        Filter.Expression expr = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("docId"),
                new Filter.Value(docId));
        store().delete(expr);
        docRepository.delete(doc.get());
        evictSearchCache();
    }

    private void evictSearchCache() {
        CacheService cache = cacheProvider.getIfAvailable();
        if (cache != null) {
            cache.evictByPrefix(SEARCH_CACHE_PREFIX);
        }
    }

    private VectorStore store() {
        VectorStore vs;
        try {
            vs = vectorStoreProvider.getIfAvailable();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RAG 不可用：请确认 PostgreSQL(pgvector) 与 Ollama 已启动，库 interview_vector 已建且已 CREATE EXTENSION vector", e);
        }
        if (vs == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RAG 未启用：设置 app.rag.enabled=true 后重启");
        }
        return vs;
    }

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
