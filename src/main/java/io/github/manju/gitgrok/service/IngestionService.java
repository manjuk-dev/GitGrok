package io.github.manju.gitgrok.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class IngestionService {

    private VectorStore vectorStore;
    private GitHubService gitHubService;

    public IngestionService(VectorStore vectorStore, GitHubService gitHubService) {
        this.vectorStore = vectorStore;
        this.gitHubService = gitHubService;
    }

    public void ingestFile(String owner, String repo, String branch, String path) {
        // 1. Fetch content from GitHub
        String rawContent = gitHubService.fetchFileContent(owner, repo, branch, path);

        // 2. Create a Document object (Spring AI's core data unit)
        // We add metadata so the AI knows which repo/file it is looking at later.
        Document document = new Document(rawContent, Map.of(
                "repo", repo,
                "path", path,
                "owner", owner
        ));

        // 3. Split the document into "Chunks"
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(List.of(document));

        // Sending to Pinecone
        // Spring AI automatically calls Gemini's Embedding model to turn text into numbers (vectors)
        vectorStore.add(chunks);
        System.out.println("Successfully ingested: " + path);
    }
}
