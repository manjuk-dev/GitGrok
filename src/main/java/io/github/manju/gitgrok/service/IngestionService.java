package io.github.manju.gitgrok.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IngestionService {
    private final VectorStore vectorStore;
    private final GitHubService gitHubService;

    public IngestionService(VectorStore vectorStore, GitHubService gitHubService) {
        this.vectorStore = vectorStore;
        this.gitHubService = gitHubService;
    }


    public void ingestEntireRepo(String owner, String repo, String branch) {
        List<String> javaFiles = gitHubService.fetchJavaFilePaths(owner, repo, branch);

        System.out.println("DEBUG: GitHub found " + javaFiles.size() + " Java files.");
        // using parallelStream to ingest many files at once
        javaFiles.parallelStream().forEach(path -> {
            ingestSingleFile(owner, repo, branch, path);
        });
    }


    public void ingestSingleFile(String owner, String repo, String branch, String path) {
        String content = gitHubService.fetchFileContent(owner, repo, branch, path);

        // Create the document
        Document doc = new Document(content, Map.of("path", path, "repo", repo));

        // Use a TokenTextSplitter to break it into chunks
        var splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(List.of(doc));

        // Assign a random UUID to every chunk so Pinecone sees them as unique
        List<Document> uniqueChunks = chunks.stream()
                .map(chunk -> new Document(
                        UUID.randomUUID().toString(),
                        chunk.getText(),
                        chunk.getMetadata()))
                .toList();

        vectorStore.accept(uniqueChunks);
        System.out.println("Processed successfully " + path);
    }
}
