package io.github.manju.gitgrok.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

// RAG  engine
@Service
public class GitHubService {

    private final RestClient restClient;

    public GitHubService(RestClient.Builder builder){
        this.restClient = builder.baseUrl("https://raw.githubusercontent.com").build();
    }

    public String fetchFileContent(String repoOwner, String repoName, String branch, String filePath) {
        try {
            return restClient.get()
                    .uri("/{owner}/{repo}/{branch}/{file}", repoOwner, repoName, branch, filePath)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch file from GitHub: " + e.getMessage());
        }
    }
}
