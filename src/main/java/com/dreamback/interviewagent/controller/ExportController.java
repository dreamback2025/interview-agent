package com.dreamback.interviewagent.controller;

import com.dreamback.interviewagent.dto.ExportDto;
import com.dreamback.interviewagent.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 全量导出：GET /api/export 直接下载 JSON；不带 download 参数则返回 JSON 体。 */
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    @GetMapping
    public ResponseEntity<ExportDto> export() {
        ExportDto body = exportService.exportAll();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"interview-export.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
