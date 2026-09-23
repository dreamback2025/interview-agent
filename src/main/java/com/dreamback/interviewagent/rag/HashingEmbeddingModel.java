package com.dreamback.interviewagent.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 兜底 embedding：不依赖任何外部服务。
 *
 * 用「词袋 + 双哈希」生成定长向量，对关键词型查询（例如「G1 回收流程」）能给出可用的相似度，
 * 因此它的作用是：在 Ollama/bge-m3 还没就绪时，把「切分 → 向量化 → pgvector 入库 → 相似度检索」
 * 这条链路完整验证掉。语义检索质量远不如 bge-m3，正式使用请切回 ollama。
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    private final int dimensions;

    public HashingEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        int index = 0;
        for (String text : request.getInstructions()) {
            embeddings.add(new Embedding(embedText(text), index++));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embedText(getEmbeddingContent(document));
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private float[] embedText(String text) {
        float[] vector = new float[dimensions];
        if (text == null || text.isBlank()) {
            return vector;
        }
        String lower = text.toLowerCase();
        for (String token : lower.split("[^\\p{IsHan}\\p{Alnum}]+")) {
            if (token.isEmpty()) {
                continue;
            }
            addToken(vector, token, 1.0f);
            // 中文再补 bigram，提高区分度
            for (int i = 0; i + 2 <= token.length(); i++) {
                addToken(vector, token.substring(i, i + 2), 0.5f);
            }
        }
        double norm = 0;
        for (float f : vector) {
            norm += (double) f * f;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= (float) norm;
            }
        }
        return vector;
    }

    private void addToken(float[] vector, String token, float weight) {
        int h = token.hashCode();
        vector[Math.floorMod(h, dimensions)] += weight;
        vector[Math.floorMod(h * 31 + 7, dimensions)] += weight * 0.5f;
    }
}
