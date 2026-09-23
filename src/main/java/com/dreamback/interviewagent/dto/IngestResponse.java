package com.dreamback.interviewagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class IngestResponse {

    private String docId;
    private int chunkCount;
    private String message;
}
