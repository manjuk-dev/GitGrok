package io.github.manju.gitgrok.config;

import java.util.List;

public class CodeSearchRequest {
    private final String query;
    private final int topK;
    private final List<String> targetFiles;

    public CodeSearchRequest(String query, int topK, List<String> targetFiles) {
        this.query = query;
        this.topK = topK;
        this.targetFiles = targetFiles;
    }

    public String getQuery() { return query; }
    public int getTopK() { return topK; }
    public List<String> getTargetFiles() { return targetFiles; }
}