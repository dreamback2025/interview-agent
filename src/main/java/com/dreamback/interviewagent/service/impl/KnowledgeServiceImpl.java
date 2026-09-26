package com.dreamback.interviewagent.service.impl;

import com.dreamback.interviewagent.cache.CacheService;
import com.dreamback.interviewagent.dto.AskResponse;
import com.dreamback.interviewagent.dto.IngestRequest;
import com.dreamback.interviewagent.dto.IngestResponse;
import com.dreamback.interviewagent.dto.SearchResult;
import com.dreamback.interviewagent.entity.KnowledgeDoc;
import com.dreamback.interviewagent.rag.HybridRetriever;
import com.dreamback.interviewagent.rag.RagRelevanceGate;
import com.dreamback.interviewagent.rag.TextSplitter;
import com.dreamback.interviewagent.rag.Tokenizer;
import com.dreamback.interviewagent.repository.KnowledgeDocRepository;
import com.dreamback.interviewagent.security.UserContext;
import com.dreamback.interviewagent.service.KnowledgeService;
import com.dreamback.interviewagent.util.Digest;
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
    private final RagRelevanceGate gate;
    private final Tokenizer tokenizer;

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
        return retrieve(query, topK).getResults();
    }

    @Override
    public AskResponse ask(String query, int topK) {
        return retrieve(query, topK);
    }

    /**
     * 召回 + 融合 + 相关性判定。search 与 ask 共用同一套逻辑与缓存 ——
     * search 只取结果，ask 额外要 confident 判定。
     */
    private AskResponse retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q 不能为空");
        }
        Long uid = userContext.currentUserId().orElse(null);

        // 缓存 key 必须带 userId：否则别的用户的检索结果会被缓存命中后返回给当前用户，造成越权。
        // 同时带上「检索模式 + 闸门阈值」—— 任一变化结果就不同，不能混用同一份缓存。
        CacheService cache = cacheProvider.getIfAvailable();
        String mode = (hybridRetriever.isEnabled() ? "h" : "v") + ":g" + Digest.sha256Short(gate.describe());
        String cacheKey = SEARCH_CACHE_PREFIX + mode + ":" + uid + ":" + topK + ":" + Digest.sha256Short(query);
        if (cache != null) {
            AskResponse cached = cache.get(cacheKey, AskResponse.class);
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

        double vecTop1 = vecDocs.isEmpty() ? 0.0 : scoreOf(vecDocs.get(0));
        double rrfTop1 = 0.0;
        List<SearchResult> results = new ArrayList<>();

        if (kwHits.isEmpty()) {
            vecDocs.forEach(d -> results.add(toResult(d)));
        } else {
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
            rrfTop1 = rrf.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            results.addAll(takeTop(rrf, vecById, kwById, topK));
        }

        // 相关性判定：单看相似度区分不了难负样本（实测重叠 41.8pp）。
        // 第二个信号用「覆盖率」而不是「关键词命中文档数」—— 后者被通用词污染，实测无区分度。
        double coverage = keywordCoverage(query, results);
        RagRelevanceGate.Decision decision = gate.decide(vecTop1, coverage);

        AskResponse resp = new AskResponse();
        resp.setResults(results);
        resp.setConfident(decision.confident());
        resp.setReason(decision.reason());
        resp.getSignals().setVecTop1(vecTop1);
        resp.getSignals().setKwHits(kwHits.size());
        resp.getSignals().setKwCoverage(coverage);
        resp.getSignals().setRrfTop1(rrfTop1);

        if (cache != null) {
            cache.put(cacheKey, resp);
        }
        return resp;
    }

    /**
     * 按 RRF 融合分截取 topK。
     *
     * <p>score 字段的处理：向量通道来的保留**余弦相似度**（语义可比）；
     * 仅被关键词通道召回的，用 ts_rank 映射一个可读分（ts_rank 量级约 0~0.1，乘 10 封顶 1.0）。
     * 排序完全由 RRF 决定 —— 这也是「关键词通道能把同域干扰项挤下去」的机制。
     */
    private List<SearchResult> takeTop(Map<String, Double> rrf,
                                       Map<String, Document> vecById,
                                       Map<String, HybridRetriever.KeywordHit> kwById,
                                       int topK) {
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

    /**
     * 关键词覆盖率：查询分词后，有多少比例的 token 出现在 top1 文档里（0~1）。
     *
     * <p>为什么不用关键词通道的命中文档数：那是 OR 查询（{@code tok1 | tok2 | ...}），
     * 任意 token 命中就计数 —— 「消息」「处理」「怎么」这类通用词会让跨域问题
     * （如「Kafka 消息积压怎么处理」）也拿到很高的命中数，信号失去区分度（实测正负样本都 6~15）。
     *
     * <p>覆盖率要求 token 落在**同一个文档**里，才代表「这个文档真的在讲这个问题」。
     */
    private double keywordCoverage(String query, List<SearchResult> results) {
        if (results.isEmpty()) {
            return 0.0;
        }
        String content = results.get(0).getContent();
        if (content == null || content.isBlank()) {
            return 0.0;
        }
        List<String> tokens = tokenizer.tokenize(query);
        if (tokens.isEmpty()) {
            return 0.0;
        }
        String haystack = content.toLowerCase();
        long hit = tokens.stream().filter(haystack::contains).count();
        return (double) hit / tokens.size();
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
