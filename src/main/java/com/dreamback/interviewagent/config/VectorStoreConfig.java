package com.dreamback.interviewagent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 向量库独立数据源：业务数据仍在 H2，向量数据走 Postgres + pgvector。
 *
 * 用 @Lazy 是为了「Ollama / Postgres 没启动」时应用依然能正常启动，
 * 只在真正调用 RAG 接口时才去连向量库。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VectorStoreConfig implements DisposableBean {

    /**
     * 注意：这个数据源**不能注册成 Spring bean**，否则 JPA 会把它当成主数据源
     * （曾经导致启动就去连 5432，Postgres 没起时整个应用起不来）。
     * 因此这里在方法内部构造，仅供向量库使用。
     */
    private volatile HikariDataSource internalDataSource;

    @Bean
    @Lazy
    public VectorStore vectorStore(EmbeddingModel embeddingModel,
                                   @Value("${app.rag.vectordb.url}") String url,
                                   @Value("${app.rag.vectordb.username}") String username,
                                   @Value("${app.rag.vectordb.password}") String password,
                                   @Value("${app.rag.dimensions:1024}") int dimensions) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setPoolName("vectorPool");
        ds.setMaximumPoolSize(5);
        this.internalDataSource = ds;

        return PgVectorStore.builder(new JdbcTemplate(ds), embeddingModel)
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(true)
                .build();
    }

    @Override
    public void destroy() {
        if (internalDataSource != null) {
            internalDataSource.close();
        }
    }
}
