package com.dreamback.interviewagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 知识库文档目录（chunk 本体在 pgvector 里）。 */
@Getter
@Setter
@Entity
@Table(name = "knowledge_doc")
public class KnowledgeDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 与 pgvector metadata 中的 docId 对应 */
    @Column(name = "doc_id", length = 64, unique = true)
    private String docId;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "source", length = 200)
    private String source;

    @Column(name = "tags", length = 255)
    private String tags;

    @Column(name = "chunk_count")
    private int chunkCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
