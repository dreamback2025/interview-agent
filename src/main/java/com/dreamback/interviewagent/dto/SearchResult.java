package com.dreamback.interviewagent.dto;

import lombok.Data;

@Data
public class SearchResult {

    /** 相似度（1 - 余弦距离，越大越相关） */
    private double score;

    private String content;

    private String docId;

    private String title;

    private String source;

    private String tags;
}
