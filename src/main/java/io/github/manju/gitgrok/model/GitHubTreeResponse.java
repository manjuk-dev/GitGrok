package io.github.manju.gitgrok.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTreeResponse(List<GitHubTreeNode> tree, boolean truncated) {
}
