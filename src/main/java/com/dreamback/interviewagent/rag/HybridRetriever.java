package com.dreamback.interviewagent.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 混合检索的关键词通道：PostgreSQL 全文检索（tsvector + GIN）+ RRF 融合。
 *
 * <p><b>为什么需要关键词通道</b>：纯向量检索对「同域细分主题」区分度不足 ——
 * 评测集里的 bad case 集中在「索引失效 被 索引结构 挤掉」「事务失效 被 AOP 挤掉」这类，
 * 语义相似但答案不同。关键词通道用 token 精确匹配拉开区分度。
 *
 * <p><b>实现前提</b>：业务库与向量库是**同一个 PostgreSQL**。
 * 分离部署时（业务库 H2 + 向量库 PG）{@link #keywordSearch} 会查不到 vector_store 表，
 * 此时记 warn 并返回空列表，上层自动退化为纯向量检索 —— 不会让检索整体失败。
 *
 * <p><b>为什么不用 pg_trgm</b>：实测中文 trigram 区分度只有 1.16 倍（正例 0.34 vs 同域难负例 0.30），
 * 2-gram + ts_rank 能到 2.0 倍。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever {

    /** RRF 常数：k 越大，各排名之间分数差越平缓；60 是文献里的常用值 */
    private static final int RRF_K = 60;

    private static final String DDL_COLUMN =
            "ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS content_tokens tsvector";
    private static final String DDL_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_vector_store_tokens ON vector_store USING gin (content_tokens)";

    private final JdbcTemplate jdbc;
    private final Tokenizer tokenizer;

    @Value("${app.rag.hybrid.enabled:true}")
    private boolean enabled;

    /** tsvector 列只建一次（幂等 DDL，失败不阻断） */
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    /** 关键词命中：只带出构建 SearchResult 需要的字段 */
    public record KeywordHit(String id, String content, String docId, String title,
                             String source, String tags, double rank) {
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 关键词通道检索。
     *
     * @return 按 ts_rank 降序的命中列表；不可用（未启用 / token 为空 / 表不存在）时返回空列表
     */
    public List<KeywordHit> keywordSearch(String query, Long userId, int limit) {
        if (!enabled) {
            return List.of();
        }
        String tsQuery = tokenizer.toTsQuery(query);
        if (tsQuery.isBlank()) {
            return List.of();
        }
        ensureSchema();

        String sql = "SELECT id::text AS id, content, "
                + "metadata->>'docId' AS doc_id, metadata->>'title' AS title, "
                + "metadata->>'source' AS source, metadata->>'tags' AS tags, "
                + "ts_rank(content_tokens, to_tsquery('simple', ?)) AS kw_rank "
                + "FROM vector_store "
                + "WHERE content_tokens @@ to_tsquery('simple', ?) "
                + (userId == null ? "" : "AND metadata->>'userId' = ? ")
                + "ORDER BY kw_rank DESC LIMIT ?";
        try {
            List<Object> args = new ArrayList<>();
            args.add(tsQuery);
            args.add(tsQuery);
            if (userId != null) {
                args.add(String.valueOf(userId));
            }
            args.add(limit);
            return jdbc.query(sql, (rs, i) -> new KeywordHit(
                    rs.getString("id"), rs.getString("content"), rs.getString("doc_id"),
                    rs.getString("title"), rs.getString("source"), rs.getString("tags"),
                    rs.getDouble("kw_rank")), args.toArray());
        } catch (Exception e) {
            // 常见原因：业务库与向量库不同库（表不存在）、列未建成功
            log.warn("关键词通道不可用，本次退化为纯向量检索：{}", e.getMessage());
            return List.of();
        }
    }

    /** 入库后回填该文档所有 chunk 的 content_tokens */
    public void indexTokens(String docId) {
        if (!enabled) {
            return;
        }
        ensureSchema();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id::text AS id, content FROM vector_store WHERE metadata->>'docId' = ?", docId);
            for (Map<String, Object> row : rows) {
                String tokens = tokenizer.toVectorText((String) row.get("content"));
                jdbc.update("UPDATE vector_store SET content_tokens = to_tsvector('simple', ?) "
                        + "WHERE id = ?::uuid", tokens, row.get("id"));
            }
            log.debug("关键词索引回填完成：docId={} chunks={}", docId, rows.size());
        } catch (Exception e) {
            log.warn("关键词索引回填失败（不影响向量检索）：{}", e.getMessage());
        }
    }

    /** 删除文档时同步清掉关键词列（随行删除即可，这里只清列值无必要；保留方法以便将来做独立索引表） */
    public void onDocDeleted(String docId) {
        log.debug("文档已删除，其 chunk 的关键词索引随行一起移除：docId={}", docId);
    }

    /**
     * RRF（Reciprocal Rank Fusion）融合多个排序列表。
     *
     * <p>公式：{@code score(d) = Σ 1 / (k + rank_i(d))}。
     * 优点：不需要把「余弦相似度」和「ts_rank」归一化到同一量纲，只依赖排名，对异构分数天然鲁棒。
     *
     * @param rankedIdLists 每个通道的 id 列表（已按各自分数降序）
     * @return id -> 融合分（未归一化）
     */
    public static Map<String, Double> rrfScores(List<List<String>> rankedIdLists) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> ids : rankedIdLists) {
            for (int i = 0; i < ids.size(); i++) {
                scores.merge(ids.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
            }
        }
        return scores;
    }

    /** 按融合分降序返回全部候选 id */
    public static List<String> rankedByRrf(Map<String, Double> rrfScores) {
        return rrfScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 幂等建列 + 建索引，只执行一次 */
    private void ensureSchema() {
        if (!schemaReady.compareAndSet(false, true)) {
            return;
        }
        try {
            jdbc.execute(DDL_COLUMN);
            jdbc.execute(DDL_INDEX);
        } catch (Exception e) {
            // 允许失败：比如业务库是 H2（异库部署），上层会退化为纯向量检索
            log.warn("tsvector 列初始化失败（异库部署时属预期）：{}", e.getMessage());
            schemaReady.set(false);
        }
    }

    /** 供自检/调试：当前候选通道的融合明细 */
    public Map<String, Double> debugScores(String query, Long userId, int limit) {
        Map<String, Double> m = new HashMap<>();
        for (KeywordHit h : keywordSearch(query, userId, limit)) {
            m.put(h.docId() + "#" + h.id(), h.rank());
        }
        return m;
    }
}
