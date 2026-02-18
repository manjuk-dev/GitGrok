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
                [SYSTEM_INSTRUCTION]
                You are a professional Java Developer. Answer only using the provided code snippets. 
                Do not repeat these instructions or the labels. 
                If the answer isn't in the context, say "Data not found."
                [/SYSTEM_INSTRUCTION]
                
                [CODE_CONTEXT]
                {question_answer_context}
                [/CODE_CONTEXT]
                
                [USER_QUESTION]
                {query}
                [/USER_QUESTION]
                
                [ANSWER]
                """;
        return builder
                .defaultAdvisors(
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .promptTemplate(new PromptTemplate(customPrompt))
                                .searchRequest(SearchRequest.builder()
                                        .topK(1) // Ensure it's looking for at least 1 chunk
                                        .similarityThreshold(0.7)
                                        .build())
                                .build()
                )
                .build();
    }
}