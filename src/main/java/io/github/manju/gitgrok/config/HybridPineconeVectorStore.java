package io.github.manju.gitgrok.config;
//VectorStore wrapper, reads the ThreadLocal when Pinecone is called

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.pinecone.clients.Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.*;
import java.util.regex.Pattern;

public class HybridPineconeVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(HybridPineconeVectorStore.class);
    private static final Set<String> COMMAND_STOPWORDS = Set.of(
            "List", "Show", "Give", "What", "How", "Where", "Which", "Explain", "Find"
    );
    private static final Pattern METHOD_PATTERN =
            Pattern.compile(".*(method|function|endpoint|api).*");
    private static final Pattern CLASS_PATTERN =
            Pattern.compile(".*(class|interface|extends|implements).*");
    private static final Pattern DEFINITION_PATTERN =
            Pattern.compile(".*(define|definition|what is|how to|implementation).*");
    private static final Pattern EXPLANATION_PATTERN =
            Pattern.compile(".*(explain|meaning|why|how does|what does).*");

    private final Index pineconeIndex;
    private final EmbeddingModel embeddingModel;
    private final double alpha;
    private final int topK;

    public HybridPineconeVectorStore(Index pineconeIndex, EmbeddingModel embeddingModel,
                                     double alpha, int topK) {
        this.pineconeIndex = pineconeIndex;
        this.embeddingModel = embeddingModel;
        this.alpha = alpha;
        this.topK = topK;
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        List<String> targetFiles = FileExtractor.extractFileNames(request.getQuery());

        // after extracting candidate matches, filter
        targetFiles.removeIf(name -> COMMAND_STOPWORDS.contains(name.replace(".java", "")));
        CodeSearchRequest codeRequest = new CodeSearchRequest(
                request.getQuery(),
                request.getTopK(),
                targetFiles
        );
        return performSearch(codeRequest);
    }

    public List<Document> performSearch(CodeSearchRequest request) {
        try {
            // 1. Dense vector scaled by alpha
            float[] raw = embeddingModel.embed(request.getQuery());
            List<Float> denseVector = new ArrayList<>();
            for (float v : raw) denseVector.add(v * (float) alpha);

            //  Sparse vector scaled by (1 - alpha)
            float sparseScale = (float) (1.0 - alpha);
            Map<Long, Float> sparseMap = new HashMap<>();

            for (String token : request.getQuery().toLowerCase().split("\\W+")) {
                if (token.length() > 2) {
                    // Convert to Long explicitly
                    long id = Math.abs((long) token.hashCode());
                    sparseMap.put(id, sparseMap.getOrDefault(id, 0f) + sparseScale);
                }
            }

            List<Long> sparseIndices = new ArrayList<>(sparseMap.keySet());
            List<Float> sparseValues = new ArrayList<>(sparseMap.values());

            // Query Pinecone
            var response = pineconeIndex.query(
                    request.getTopK(),    // int
                    denseVector,          // List<Float>
                    sparseIndices,        // List<Long>
                    sparseValues,         // List<Float>
                    null,                 // vectorId (String)
                    "",                   // namespace
                    buildSmartFilter(request),                 // filter
                    false,                // includeValues
                    true                  // includeMetadata
            );

            if (response == null || response.getMatchesList().isEmpty()) {
                log.info("No matching vectors found in Pinecone.");
                // Return a sentinel document instead of an empty list
                return List.of(new Document(
                        "[SYSTEM_NOTE: No code snippets matching the query were found in the codebase index.]"
                ));
            }

            //  Map results to Spring AI Documents
            List<Document> results = new ArrayList<>();
            for (var match : response.getMatchesList()) {
                Map<String, Value> fields = match.getMetadata().getFieldsMap();
                String content = fields.getOrDefault("document_content",
                        Value.newBuilder().setStringValue("").build()).getStringValue();

                Map<String, Object> metadata = new HashMap<>();
                fields.forEach((k, v) -> {
                    if (!k.equals("document_content")) metadata.put(k, v.getStringValue());
                });
                results.add(new Document(content, metadata));
            }
            return results;
        } catch (Exception e) {
            log.error("EXCEPTION: Search failed for query: {}{}", request.getQuery(), e);
            return Collections.emptyList();  // or throw a custom exception
        }
    }

    private Struct buildSmartFilter(CodeSearchRequest request) {
        Map<String, Object> filterMap = buildFilterMap(request);

        if (filterMap == null || filterMap.isEmpty()) {
            return null;
        }

        return mapToStruct(filterMap);
    }

    private Struct mapToStruct(Map<String, Object> map) {
        Struct.Builder builder = Struct.newBuilder();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            builder.putFields(entry.getKey(), objectToValue(entry.getValue()));
        }

        return builder.build();
    }

    private Value objectToValue(Object obj) {
        if (obj == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }

        if (obj instanceof String) {
            return Value.newBuilder().setStringValue((String) obj).build();
        }

        if (obj instanceof Number) {
            return Value.newBuilder().setNumberValue(((Number) obj).doubleValue()).build();
        }

        if (obj instanceof Boolean) {
            return Value.newBuilder().setBoolValue((Boolean) obj).build();
        }

        if (obj instanceof Map) {
            return Value.newBuilder().setStructValue(mapToStruct((Map<String, Object>) obj)).build();
        }

        if (obj instanceof List) {
            ListValue.Builder listBuilder = ListValue.newBuilder();
            for (Object item : (List<?>) obj) {
                listBuilder.addValues(objectToValue(item));
            }
            return Value.newBuilder().setListValue(listBuilder.build()).build();
        }

        return Value.newBuilder().setStringValue(obj.toString()).build();
    }


    public Map<String, Object> buildFilterMap(CodeSearchRequest request) {
        if (request == null) {
            return null;
        }
        List<Map<String, Object>> andConditions = new ArrayList<>();

        // If filename was extracted, add exact match filter
        if (request.getTargetFiles() != null && !request.getTargetFiles().isEmpty()) {
            if (request.getTargetFiles().size() == 1) {
                // for single file: use $eq
                andConditions.add(Map.of(
                        "filename", Map.of("$eq", request.getTargetFiles().get(0))
                ));
            } else {
                // for multiple files: use $in
                andConditions.add(Map.of(
                        "filename", Map.of("$in", request.getTargetFiles())
                ));
            }
        }

        // Query type based filtering
        String queryType = detectQueryType(request.getQuery());
        if ("method_class".equals(queryType)) {
            andConditions.add(Map.of(
                    "symbolType", Map.of("$in", List.of("method", "class"))
            ));
        }

        if (andConditions.isEmpty()) return null;
        if (andConditions.size() == 1) return andConditions.get(0);
        return Map.of("$and", andConditions);
    }

    public String detectQueryType(String query) {
        if (query == null || query.isBlank()) {
            return "general";
        }
        String lower = query.toLowerCase();

        //pre-compiled patterns
        if (METHOD_PATTERN.matcher(lower).matches()) {
            return "method_class";
        } else if (CLASS_PATTERN.matcher(lower).matches()) {
            return "method_class";
        } else if (DEFINITION_PATTERN.matcher(lower).matches()) {
            return "definition";
        } else if (EXPLANATION_PATTERN.matcher(lower).matches()) {
            return "explanation";
        }

        return "general";
    }


    // Ingestion is handled by IngestionService directly, not needed here
    @Override
    public void add(List<Document> documents) {
    }

    @Override
    public void delete(List<String> ids) {
        pineconeIndex.deleteByIds(ids, "");
    }

    @Override
    public void delete(Filter.Expression filter) {
    }
}