package io.github.manju.gitgrok.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AIConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, VectorStore vectorStore) {
        String customPrompt = """
                ### ROLE
                You are 'GitGrok', a Senior Full-Stack Architect. Your goal is to explain the provided codebase 
                with technical precision, brevity, and absolute honesty.
                
                ### CONTEXT FROM REPOSITORY
                The following snippets are retrieved from the codebase for the current query. 
                ---
                {question_answer_context}
                ---
                
                ### INSTRUCTIONS
                1. **Source Grounding**: Answer using ONLY the snippets provided. If the context is insufficient, state: "Information not found in repository."
                2. **Thought Process**: Before your final response, briefly list which files/methods you are looking at to formulate the answer.
                3. **The "Crisp" Rule**: Limit your explanation to 3-5 high-impact sentences. Use bullet points for multi-step logic.
                4. **File References**: Always use **bold** for file paths (e.g., **ChatController.java**) and `inline code` for variable names or annotations.
                5. **Architect's Critique**: If the snippet contains a security risk (like hardcoded tokens) or a performance bottleneck, add a "⚠️ Architect's Note" at the end.
                
                ### USER QUESTION
                {query}
                
                ### FINAL RESPONSE
                (Start with 'Analysis of [filenames]...')
                """;
        return builder
                .defaultAdvisors(
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .promptTemplate(new PromptTemplate(customPrompt))
                                .searchRequest(SearchRequest.builder()
                                        .topK(10) // Ensure it's looking for at least 10
                                        .similarityThreshold(0.5)
                                        .build())
                                .build()
                )
                .build();
    }
}