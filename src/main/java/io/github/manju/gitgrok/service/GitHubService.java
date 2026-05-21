package io.github.manju.gitgrok.service;

import io.github.manju.gitgrok.model.GitHubTreeNode;
import io.github.manju.gitgrok.model.GitHubTreeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.util.List;


@Service
public class GitHubService {
    private final RestClient restClient;

    public GitHubService(RestClient.Builder builder, @Value("${github.token}") String token) {
        System.out.println("DEBUG: Token starts with: " + (token != null && token.length() > 4 ? token.substring(0, 4) : "NULL"));
        this.restClient = builder
                .baseUrl("https://api.github.com")
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    // Fetches every .java file path in the repo using the recursive tree API
    public List<String> fetchJavaFilePaths(String owner, String repo, String branch) {
        // We use the recursive=1 flag to get EVERY file in every subfolder
        String url = String.format("/repos/%s/%s/git/trees/%s?recursive=1", owner, repo, branch);

        GitHubTreeResponse response = restClient.get()
                .uri(url)
                .retrieve()
                .body(GitHubTreeResponse.class);

        if (response == null || response.tree() == null) return List.of();

        List<String> result =  response.tree().stream()
                .filter(node -> "blob".equals(node.type()))     // Only files, not folders
                .filter(node -> node.path().endsWith(".java"))
                .filter(node -> !isTestFile(node.path()))// Only src Java files
                .map(GitHubTreeNode::path)                      // Get the full path string
                .toList();

        return result;
    }

    private boolean isTestFile(String path) {
        String lowerPath = path.toLowerCase();
        return lowerPath.contains("/src/test/") ||
                lowerPath.endsWith("test.java") ||
                lowerPath.contains("/test/") ||
                lowerPath.endsWith("tests.java");
    }

    public String fetchFileContent(String owner, String repo, String branch, String path) {
        String rawUrl = String.format("https://raw.githubusercontent.com/%s/%s/%s/%s", owner, repo, branch, path);
        return new RestTemplate().getForObject(rawUrl, String.class);
    }
}