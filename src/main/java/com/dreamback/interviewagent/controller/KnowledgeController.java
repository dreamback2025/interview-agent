package com.dreamback.interviewagent.controller;

import com.dreamback.interviewagent.dto.IngestRequest;
import com.dreamback.interviewagent.dto.IngestResponse;
import com.dreamback.interviewagent.dto.SearchResult;
import com.dreamback.interviewagent.entity.KnowledgeDoc;
import com.dreamback.interviewagent.service.KnowledgeService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    /** 笔记入库：切分 -> embedding -> pgvector */
    @PostMapping("/upload")
    public IngestResponse upload(@Valid @RequestBody IngestRequest req) {
        return knowledgeService.ingest(req);
    }

    /** 相似度检索 */
    @GetMapping("/search")
    public List<SearchResult> search(@RequestParam String q,
                                     @RequestParam(defaultValue = "5") int topK) {
        return knowledgeService.search(q, Math.min(Math.max(topK, 1), 20));
    }

    /** 已导入文档目录 */
    @GetMapping("/docs")
    public List<KnowledgeDoc> docs() {
        return knowledgeService.docs();
    }

    /** 删除一篇文档及其所有 chunk */
    @DeleteMapping("/docs/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDoc(@PathVariable String docId) {
        knowledgeService.deleteDoc(docId);
    }
}
