package io.github.manju.gitgrok.config;

import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AIConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 VectorStore vectorStore,
                                 EmbeddingModel embeddingModel,
                                 @Value("${spring.ai.vectorstore.pinecone.index-name}") String indexName) {

        Pinecone pineconeClient = (Pinecone) vectorStore.getNativeClient()
                .orElseThrow(() -> new IllegalStateException("Pinecone native client not available"));

        Index pineconeIndex = pineconeClient.getIndexConnection(indexName);

        VectorStore hybridStore = new HybridPineconeVectorStore(
                pineconeIndex, embeddingModel, 0.6, 6
        );

        // Core instructions for the AI's persona
        String systemInstructions = """
                You are GitGrok, a high-precision Java Source Code Auditor.
                
                CORE RULES:
                1. Source Truth: Answer ONLY from provided snippets. No external knowledge.
                2. Zero Inference: No guessing or assumptions. If not visible, don't mention it.
                3. Strict Visibility: Mark incomplete information as "Snippet ends prematurely."
                4. Observable Facts: Report only what's explicitly shown in code.
                
                RESPONSE FORMAT (match the code type):
                
                **REST Controllers** (@GetMapping/@PostMapping):
                → [HTTP METHOD] [PATH] → [MethodName]: [Brief purpose]
                
                **Data Access** (@Repository, CrudRepository):
                → [MethodSignature]: [What data operation]
                
                **Entity/Model Classes** (@Entity, POJO):
                → [MethodName]: [What property/operation]
                
                **Configuration** (@Configuration, @Bean):
                → [BeanName]: [What component it configures]
                
                **Utility Classes**:
                → [MethodName]: [What helper task]
                
                QUALITY:
                - Be concise. No unnecessary code blocks.
                - Show relationships: "uses", "calls", "depends on"
                - Acknowledge missing context
                - Ask for clarification if ambiguous
                
                CRITICAL FOR MULTI-FILE QUERIES:
                When multiple files are provided:
                - Focus on visible method calls ONLY
                - Do NOT invent paths, methods, or parameters
                - If connection between files is not explicit, state: "Connection not visible in snippets"
                - Do NOT fill gaps with reasonable assumptions
                
                NEVER: Fabricate endpoints, invent parameters, assume patterns without evidence.
                """;

        SearchRequest searchRequest = SearchRequest.builder()
                .similarityThreshold(0.2)
                .topK(5)
                .build();

        // Defining how retrieved snippets are presented to the LLM
        PromptTemplate adviceTemplate = new PromptTemplate("""
                Context Code Snippets:
                {question_answer_context}
                
                User Question: {query}
                
                Answer concisely using ONLY the snippets above.
                Do NOT repeat full code. Focus on what was asked.
                If not found, say "File not found in context
                """);

        // Build the ChatClient with integrated Advisors
        return builder
                .defaultSystem(systemInstructions)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        QuestionAnswerAdvisor.builder(hybridStore)
                                .promptTemplate(adviceTemplate)
                                .searchRequest(searchRequest)
                                .build()
                )
                .build();
    }

}