package config;

import io.github.manju.gitgrok.config.CodeSearchRequest;
import io.github.manju.gitgrok.config.HybridPineconeVectorStore;
import io.pinecone.clients.Index;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class HybridPineconeVectorStoreTest {

    @Mock
    private Index pineconeIndex;

    @Mock
    private EmbeddingModel embeddingModel;

    private HybridPineconeVectorStore vectorStore;

    @BeforeEach
    void setUp() {
        vectorStore = new HybridPineconeVectorStore(pineconeIndex, embeddingModel, 0.6, 5);
    }

    // ==========================================
    // DETECT QUERY TYPE TESTS
    // ==========================================

    @Test
    void detectQueryType_ShouldReturnMethodClass_ForMethodKeywords() {
        assertEquals("method_class", vectorStore.detectQueryType("find the method calculateTotal"));
        assertEquals("method_class", vectorStore.detectQueryType("call the function execute"));
    }

    @Test
    void detectQueryType_ShouldReturnMethodClass_ForClassKeywords() {
        assertEquals("method_class", vectorStore.detectQueryType("where is class UserService"));
    }

    @Test
    void detectQueryType_ShouldReturnDefinition_ForDefinitionKeywords() {
        assertEquals("definition", vectorStore.detectQueryType("define polymorphism"));
    }

    @Test
    void detectQueryType_ShouldReturnExplanation_ForExplanationKeywords() {
        assertEquals("explanation", vectorStore.detectQueryType("explain the build process"));
    }

    @Test
    void detectQueryType_ShouldReturnGeneral_ForUnmatchedQueries() {
        assertEquals("general", vectorStore.detectQueryType("hello world"));
    }

    @Test
    void detectQueryType_ShouldReturnGeneral_ForNullQuery() {
        assertEquals("general", vectorStore.detectQueryType(null));
    }

    @Test
    void detectQueryType_ShouldReturnGeneral_ForEmptyQuery() {
        assertEquals("general", vectorStore.detectQueryType(""));
    }

    @Test
    void detectQueryType_ShouldReturnGeneral_ForBlankQuery() {
        assertEquals("general", vectorStore.detectQueryType("   "));
    }

    @Test
    void detectQueryType_ShouldBeCaseInsensitive_ForMethodKeywords() {
        assertEquals("method_class", vectorStore.detectQueryType("FIND THE METHOD calculateTotal"));
    }

    @Test
    void detectQueryType_ShouldPrioritizeMethodClass_WhenQueryHasMixedSignals() {
        assertEquals("method_class", vectorStore.detectQueryType("explain the method calculateTotal"));
    }

    // ==========================================
    // BUILD FILTER MAP TESTS
    // ==========================================

    @Test
    void buildFilterMap_ShouldReturnNull_WhenNoFilesAndGeneralQuery() {
        CodeSearchRequest request = new CodeSearchRequest("just a general query", 5, Collections.emptyList());
        assertNull(vectorStore.buildFilterMap(request));
    }

    @Test
    void buildFilterMap_ShouldReturnEqFilter_ForSingleTargetFile() {
        CodeSearchRequest request = new CodeSearchRequest("general query", 5, List.of("UserService.java"));
        Map<String, Object> filterMap = vectorStore.buildFilterMap(request);

        assertNotNull(filterMap);
        assertTrue(filterMap.containsKey("filename"));
        Map<String, Object> eqMap = (Map<String, Object>) filterMap.get("filename");
        assertEquals("UserService.java", eqMap.get("$eq"));
    }

    @Test
    void buildFilterMap_ShouldReturnSymbolTypeFilter_WhenQueryMatchesMethodClassWithoutFiles() {
        CodeSearchRequest request = new CodeSearchRequest("find method execute", 5, Collections.emptyList());
        Map<String, Object> filterMap = vectorStore.buildFilterMap(request);

        assertNotNull(filterMap);
        assertTrue(filterMap.containsKey("symbolType"));
    }

    @Test
    void buildFilterMap_ShouldReturnAndCompoundFilter_WhenBothFileAndMethodTypeArePresent() {
        CodeSearchRequest request = new CodeSearchRequest("find class User", 5, List.of("User.java"));
        Map<String, Object> filterMap = vectorStore.buildFilterMap(request);

        assertNotNull(filterMap);
        assertTrue(filterMap.containsKey("$and"));
    }

    @Test
    void buildFilterMap_ShouldReturnNull_WhenRequestIsNull() {
        assertNull(vectorStore.buildFilterMap(null));
    }

    @Test
    void buildFilterMap_ShouldReturnNull_WhenQueryIsBlankAndNoFiles() {
        CodeSearchRequest request = new CodeSearchRequest("   ", 5, Collections.emptyList());
        assertNull(vectorStore.buildFilterMap(request));
    }

    @Test
    void buildFilterMap_ShouldReturnNull_WhenTargetFileListIsNull() {
        CodeSearchRequest request = new CodeSearchRequest("just a general query", 5, null);
        assertNull(vectorStore.buildFilterMap(request));
    }


    @Test
    @SuppressWarnings("unchecked")
    void buildFilterMap_AndFilter_ShouldContainBothFilenameAndSymbolTypeConditions() {
        CodeSearchRequest request = new CodeSearchRequest("find class User", 5, List.of("User.java"));
        Map<String, Object> filterMap = vectorStore.buildFilterMap(request);

        assertNotNull(filterMap);
        assertTrue(filterMap.containsKey("$and"));

        List<Map<String, Object>> andConditions = (List<Map<String, Object>>) filterMap.get("$and");
        assertEquals(2, andConditions.size(),
                "Expected exactly two conditions in $and: filename and symbolType");

        // Verify a filename condition exists with the correct $eq value
        boolean hasFilenameCondition = andConditions.stream().anyMatch(condition -> {
            Map<String, Object> eqMap = (Map<String, Object>) condition.get("filename");
            return eqMap != null && "User.java".equals(eqMap.get("$eq"));
        });
        assertTrue(hasFilenameCondition, "Expected $and to contain a filename $eq condition for User.java");

        // Verify a symbolType condition exists
        boolean hasSymbolTypeCondition = andConditions.stream()
                .anyMatch(condition -> condition.containsKey("symbolType"));
        assertTrue(hasSymbolTypeCondition, "Expected $and to contain a symbolType condition");
    }

}
