package io.github.manju.gitgrok.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.google.protobuf.Struct;
import com.google.protobuf.util.Values;
import io.github.manju.gitgrok.model.CodeChunk;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final EmbeddingModel embeddingModel;
    private final GitHubService gitHubService;
    private final Index pineconeIndex;

    public IngestionService(EmbeddingModel embeddingModel,
                            GitHubService gitHubService,
                            VectorStore vectorStore,
                            @Value("${spring.ai.vectorstore.pinecone.index-name}") String indexName) {
        this.embeddingModel = embeddingModel;
        this.gitHubService = gitHubService;
        Pinecone pineconeClient = (Pinecone) vectorStore.getNativeClient()
                .orElseThrow(() -> new IllegalStateException("Pinecone native client not available"));
        this.pineconeIndex = pineconeClient.getIndexConnection(indexName);
    }

    public void ingestEntireRepo(String owner, String repo, String branch) {
        List<String> javaFiles = gitHubService.fetchJavaFilePaths(owner, repo, branch);
        log.debug("DEBUG: GitHub found {} Java files.", javaFiles.size());
        javaFiles.parallelStream().forEach(path -> {
            try {
                ingestSingleFile(owner, repo, branch, path);
            } catch (Exception e) {
                log.error("Failed to ingest file: {}", path, e);
            }
        });
    }

    public void ingestSingleFile(String owner, String repo, String branch, String path) {
        String content = gitHubService.fetchFileContent(owner, repo, branch, path);

        if (content == null || content.isBlank()) {
            log.info("Skipping file with empty/null content: {}", path);
            return;
        }

        String filename = path.substring(path.lastIndexOf("/") + 1);
        String className = filename.replace(".java", "");

        List<CodeChunk> chunks = chunkByMethods(content, path);

        for (int i = 0; i < chunks.size(); i++) {

            // Enrich text with context header
            String enrichedText = String.format("// File: %s | Class: %s | Chunk: %d/%d\n%s",
                    filename, className, (i + 1), chunks.size(), chunks.get(i));

            String embeddingInput = "search_document: " + enrichedText;

            // Dense vector embed() returns float[], convert with simple loop
            float[] raw = embeddingModel.embed(embeddingInput);
            List<Float> denseVector = new ArrayList<>();
            for (float v : raw) denseVector.add(v);

            Map<Long, Float> freq = generateCodeAwareTermFrequency(enrichedText);

            // Metadata (Struct is required by Pinecone Java client)
            Struct metadata = Struct.newBuilder()
                    .putFields("document_content", Values.of(enrichedText))
                    .putFields("path", Values.of(path))
                    .putFields("repo", Values.of(repo))
                    .putFields("filename", Values.of(filename))
                    .putFields("className", Values.of(className))
                    .putFields("methodName", Values.of(chunks.get(i).methodName()))
                    .putFields("symbolType", Values.of(chunks.get(i).symbolType()))
                    .putFields("chunkIndex", Values.of(i))
                    .putFields("totalChunks", Values.of(chunks.size()))
                    .putFields("isFirstChunk", Values.of(i == 0))
                    .build();

            // Upsert dense + sparse to Pinecone
            pineconeIndex.upsert(
                    UUID.randomUUID().toString(),
                    denseVector,
                    new ArrayList<>(freq.keySet()),
                    new ArrayList<>(freq.values()),
                    metadata,
                    ""
            );
        }

        log.info("Ingested: {} → {} chunks", path, chunks.size());
    }

    public List<CodeChunk> chunkByMethods(String content, String filePath) {
        List<CodeChunk> chunks = new ArrayList<>();
        CompilationUnit cu = StaticJavaParser.parse(content);

        cu.getTypes().forEach(type -> {
            type.getMethods().forEach(method -> {
                chunks.add(new CodeChunk(
                        method.toString(),
                        filePath,
                        type.getNameAsString(),
                        method.getNameAsString(),
                        "method"
                ));
            });
        });

        return chunks;
    }

    private Map<Long, Float> generateCodeAwareTermFrequency(String enrichedText) {
        Map<Long, Float> freq = new HashMap<>();

        // Split and clean tokens
        String[] tokens = enrichedText.toLowerCase().split("\\W+");

        for (String token : tokens) {
            if (token.length() <= 2) continue;  // Skip single chars

            float weight = 1.0f;

            if (isMethodName(token)) {
                weight = 3.0f;  // Highest priority
            } else if (isClassName(token)) {
                weight = 2.5f;
            } else if (isPropertyName(token)) {
                weight = 2.0f;
            } else if (isKeyword(token)) {
                weight = 0.3f;  // Penalize keywords
            } else if (isStopWord(token)) {
                weight = 0.1f;  // Skip common words
            }

            // Hash token and merge weight
            long idx = Math.abs((long) token.hashCode());
            freq.merge(idx, weight, Float::sum);
        }

        return freq;
    }

    private boolean isMethodName(String token) {
        if (token.isEmpty()) return false;

        boolean startsLower = Character.isLowerCase(token.charAt(0));
        boolean hasCamelCase = token.matches(".*[a-z][A-Z].*");
        boolean endsWithGetter = token.matches("^(get|set|is|has|add|remove|update|delete|find).*");

        return startsLower && (hasCamelCase || endsWithGetter);
    }

    private boolean isClassName(String token) {
        if (token.isEmpty()) return false;

        boolean startsUpper = Character.isUpperCase(token.charAt(0));
        boolean hasCamelCase = token.matches(".*[a-z][A-Z].*");

        return startsUpper && (hasCamelCase || token.length() > 3);
    }

    private boolean isPropertyName(String token) {

        if (token.isEmpty() || token.length() > 20) return false;

        boolean startsLower = Character.isLowerCase(token.charAt(0));
        boolean noUnderscores = !token.contains("_");
        boolean noNumbers = !token.matches(".*\\d+.*");

        return startsLower && noUnderscores && noNumbers;
    }

    private boolean isKeyword(String token) {
        Set<String> javaKeywords = Set.of(
                "public", "private", "protected", "static", "final", "abstract",
                "class", "interface", "extends", "implements", "new",
                "return", "if", "else", "for", "while", "do", "switch", "case",
                "try", "catch", "finally", "throw", "throws",
                "void", "int", "string", "boolean", "long", "double", "float",
                "true", "false", "null",
                "import", "package", "super", "this",
                "override", "deprecated", "entity", "table", "column"
        );

        return javaKeywords.contains(token);
    }

    private boolean isStopWord(String token) {
        Set<String> stopWords = Set.of(
                "a", "an", "and", "or", "the", "in", "of", "to", "from",
                "is", "are", "be", "been", "being", "have", "has", "had",
                "do", "does", "did", "will", "would", "should", "could", "may",
                "with", "by", "as", "at", "it", "that", "this", "which"
        );

        return stopWords.contains(token);
    }

}