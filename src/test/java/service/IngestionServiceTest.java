package service;

import com.github.javaparser.ParseProblemException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.manju.gitgrok.service.IngestionService;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import io.github.manju.gitgrok.service.GitHubService;
import io.github.manju.gitgrok.model.CodeChunk;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private GitHubService gitHubService;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private Pinecone pineconeClient;

    @Mock
    private Index pineconeIndex;

    private IngestionService ingestionService;
    private final String indexName = "test-index";

    @BeforeEach
    void setUp() {
        when(vectorStore.getNativeClient()).thenReturn(Optional.of(pineconeClient));
        when(pineconeClient.getIndexConnection(indexName)).thenReturn(pineconeIndex);

        ingestionService = new IngestionService(embeddingModel, gitHubService, vectorStore, indexName);
    }

    @Test
    @DisplayName("Constructor should throw IllegalStateException when native client is missing")
    void constructorThrowsExceptionWhenNativeClientMissing() {
        VectorStore mockEmptyVectorStore = mock(VectorStore.class);
        when(mockEmptyVectorStore.getNativeClient()).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () ->
                new IngestionService(embeddingModel, gitHubService, mockEmptyVectorStore, indexName)
        );
    }

    @Test
    @DisplayName("ingestEntireRepo handles an empty repository gracefully")
    void ingestEntireRepoWithNoJavaFiles() {
        when(gitHubService.fetchJavaFilePaths("owner", "repo", "main"))
                .thenReturn(Collections.emptyList());

        ingestionService.ingestEntireRepo("owner", "repo", "main");

        verify(gitHubService, never()).fetchFileContent(anyString(), anyString(), anyString(), anyString());
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    @DisplayName("ingestEntireRepo processes multiple files successfully")
    void ingestEntireRepoProcessesAllFiles() {
        List<String> filePaths = List.of("src/Main.java", "src/Utils.java");
        when(gitHubService.fetchJavaFilePaths("owner", "repo", "main")).thenReturn(filePaths);

        String sampleCode = "public class Dummy { public void execute() {} }";
        when(gitHubService.fetchFileContent(eq("owner"), eq("repo"), eq("main"), anyString()))
                .thenReturn(sampleCode);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        ingestionService.ingestEntireRepo("owner", "repo", "main");

        // Verify fetchContent was hit for both paths despite the parallel stream execution
        verify(gitHubService, times(1)).fetchFileContent("owner", "repo", "main", "src/Main.java");
        verify(gitHubService, times(1)).fetchFileContent("owner", "repo", "main", "src/Utils.java");
        verify(pineconeIndex, times(2)).upsert(anyString(), anyList(), anyList(), anyList(), any(), anyString());
    }

    @Test
    @DisplayName("ingestEntireRepo should continue processing other files when one file fails (resilience check)")
    void ingestEntireRepoContinuesWhenOneFileFails() {
        List<String> filePaths = List.of("src/Good1.java", "src/Bad.java", "src/Good2.java");
        when(gitHubService.fetchJavaFilePaths("owner", "repo", "main")).thenReturn(filePaths);

        String goodCode = "public class Dummy { public void execute() {} }";

        when(gitHubService.fetchFileContent("owner", "repo", "main", "src/Good1.java"))
                .thenReturn(goodCode);
        when(gitHubService.fetchFileContent("owner", "repo", "main", "src/Bad.java"))
                .thenThrow(new RuntimeException("Simulated GitHub API failure"));
        when(gitHubService.fetchFileContent("owner", "repo", "main", "src/Good2.java"))
                .thenReturn(goodCode);

        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        ingestionService.ingestEntireRepo("owner", "repo", "main");
        verify(pineconeIndex, times(2)).upsert(anyString(), anyList(), anyList(), anyList(), any(), anyString());
    }

    @Test
    @DisplayName("ingestSingleFile handles a class with no methods gracefully")
    void ingestSingleFileHandlesEmptyMethodList() {
        String javaCodeNoMethods = """
            public class MarkerInterface {
                // no methods, just a class declaration
            }
            """;

        when(gitHubService.fetchFileContent("owner", "repo", "main", "src/MarkerInterface.java"))
                .thenReturn(javaCodeNoMethods);

        // Should not throw NPE even though chunkByMethods() returns an empty list
        assertDoesNotThrow(() ->
                ingestionService.ingestSingleFile("owner", "repo", "main", "src/MarkerInterface.java")
        );

        // No methods means no chunks means no embedding calls or upserts
        verify(embeddingModel, never()).embed(anyString());
        verify(pineconeIndex, never()).upsert(anyString(), anyList(), anyList(), anyList(), any(), anyString());
    }

    @Test
    @DisplayName("ingestSingleFile handles null file content gracefully")
    void ingestSingleFileHandlesNullContent() {
        when(gitHubService.fetchFileContent("owner", "repo", "main", "src/Missing.java"))
                .thenReturn(null);

        assertDoesNotThrow(() ->
                ingestionService.ingestSingleFile("owner", "repo", "main", "src/Missing.java")
        );

        verify(embeddingModel, never()).embed(anyString());
        verify(pineconeIndex, never()).upsert(anyString(), anyList(), anyList(), anyList(), any(), anyString());
    }

    @Test
    @DisplayName("chunkByMethods extracts methods accurately from valid Java code")
    void chunkByMethodsExtractsValidMethods() {
        String javaCode = """
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                    private void log() { System.out.println("done"); }
                }
                """;

        List<CodeChunk> chunks = ingestionService.chunkByMethods(javaCode, "src/Calculator.java");

        assertEquals(2, chunks.size());
        assertEquals("add", chunks.get(0).methodName());
        assertEquals("Calculator", chunks.get(0).className());
        assertEquals("method", chunks.get(0).symbolType());
        assertEquals("log", chunks.get(1).methodName());
    }

    @Test
    @DisplayName("chunkByMethods throws ParseProblemException on malformed Java code")
    void chunkByMethodsThrowsOnMalformedCode() {
        String brokenJavaCode = "public class Broken Class { missing open bracket";

        assertThrows(ParseProblemException.class, () ->
                ingestionService.chunkByMethods(brokenJavaCode, "src/Broken.java")
        );
    }

    @Test
    @DisplayName("ingestSingleFile maps calculations, embedding, and term weights properly to Pinecone payload")
    void ingestSingleFileUpsertsCorrectPayload() {
        String javaCode = """
                public class UserService {
                    public void createUser() {
                        // processing logic here
                    }
                }
                """;

        when(gitHubService.fetchFileContent("owner", "repo", "main", "src/UserService.java"))
                .thenReturn(javaCode);

        float[] mockVector = new float[]{0.5f, -0.5f};
        when(embeddingModel.embed(anyString())).thenReturn(mockVector);

        ingestionService.ingestSingleFile("owner", "repo", "main", "src/UserService.java");

        ArgumentCaptor<List<Float>> denseVectorCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Long>> sparseKeysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Float>> sparseValuesCaptor = ArgumentCaptor.forClass(List.class);

        verify(pineconeIndex).upsert(
                anyString(),
                denseVectorCaptor.capture(),
                sparseKeysCaptor.capture(),
                sparseValuesCaptor.capture(),
                any(),
                eq("")
        );

        // Assert dense vector conversions
        List<Float> capturedDense = denseVectorCaptor.getValue();
        assertEquals(2, capturedDense.size());
        assertEquals(0.5f, capturedDense.get(0));

        // Assert that term frequency weights were populated
        List<Float> capturedSparseValues = sparseValuesCaptor.getValue();
        assertFalse(capturedSparseValues.isEmpty());

        // Assert that custom weights (e.g., method names vs keywords) calculated values
        // "createuser" should match isMethodName rules (startsLower, endsWithGetter 'create' prefix isn't explicitly there but starts lower + length conditions apply)
        // Ensure weights are strictly positive numbers
        assertTrue(capturedSparseValues.stream().allMatch(weight -> weight > 0.0f));
    }

    @Test
    @DisplayName("ingestSingleFile attaches correct metadata fields to Pinecone payload")
    void ingestSingleFileAttachesCorrectMetadata() {
        String javaCode = """
            public class OrderProcessor {
                public void processOrder() {
                    // business logic here
                }
            }
            """;

        when(gitHubService.fetchFileContent("owner", "repo", "main", "src/OrderProcessor.java"))
                .thenReturn(javaCode);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.5f, -0.5f});

        ingestionService.ingestSingleFile("owner", "repo", "main", "src/OrderProcessor.java");

        ArgumentCaptor<Struct> metadataCaptor = ArgumentCaptor.forClass(Struct.class);

        verify(pineconeIndex).upsert(
                anyString(),
                anyList(),
                anyList(),
                anyList(),
                metadataCaptor.capture(),
                eq("")
        );

        Struct capturedMetadata = metadataCaptor.getValue();
        Map<String, Value> fields = capturedMetadata.getFieldsMap();

        assertEquals("src/OrderProcessor.java", fields.get("path").getStringValue());
        assertEquals("repo", fields.get("repo").getStringValue());
        assertEquals("OrderProcessor.java", fields.get("filename").getStringValue());
        assertEquals("OrderProcessor", fields.get("className").getStringValue());
        assertEquals("processOrder", fields.get("methodName").getStringValue());
        assertEquals("method", fields.get("symbolType").getStringValue());
        assertEquals(0, (int) fields.get("chunkIndex").getNumberValue());
        assertEquals(1, (int) fields.get("totalChunks").getNumberValue());
        assertTrue(fields.get("isFirstChunk").getBoolValue());

        assertTrue(fields.get("document_content").getStringValue().contains("processOrder"));
    }

    @Test
    @DisplayName("ingestSingleFile correctly batches chunkIndex/totalChunks/isFirstChunk across multiple methods")
    void ingestSingleFileBatchesMultipleMethodsCorrectly() {
        String javaCode = """
            public class OrderProcessor {
                public void createOrder() { }
                public void updateOrder() { }
                public void cancelOrder() { }
            }
            """;

        when(gitHubService.fetchFileContent("owner", "repo", "main", "src/OrderProcessor.java"))
                .thenReturn(javaCode);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});

        ingestionService.ingestSingleFile("owner", "repo", "main", "src/OrderProcessor.java");

        ArgumentCaptor<Struct> metadataCaptor = ArgumentCaptor.forClass(Struct.class);
        verify(pineconeIndex, times(3))
                .upsert(anyString(), anyList(), anyList(), anyList(), metadataCaptor.capture(), eq(""));

        List<Struct> chunks = metadataCaptor.getAllValues();

        assertChunkMetadata(chunks.get(0), "createOrder", 0, 3, true);
        assertChunkMetadata(chunks.get(1), "updateOrder", 1, 3, false);
        assertChunkMetadata(chunks.get(2), "cancelOrder", 2, 3, false);
    }

    private void assertChunkMetadata(Struct struct, String expectedMethodName,
                                     int expectedChunkIndex, int expectedTotalChunks,
                                     boolean expectedIsFirstChunk) {
        Map<String, Value> fields = struct.getFieldsMap();

        assertEquals(expectedMethodName, fields.get("methodName").getStringValue());
        assertEquals(expectedChunkIndex, (int) fields.get("chunkIndex").getNumberValue());
        assertEquals(expectedTotalChunks, (int) fields.get("totalChunks").getNumberValue());
        assertEquals(expectedIsFirstChunk, fields.get("isFirstChunk").getBoolValue());
    }
}