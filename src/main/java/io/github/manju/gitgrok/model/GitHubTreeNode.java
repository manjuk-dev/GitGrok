package io.github.manju.gitgrok.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTreeNode(String path, String type, String sha) {}
