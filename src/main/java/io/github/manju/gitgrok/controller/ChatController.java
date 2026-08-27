package io.github.manju.gitgrok.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam String message,
                             @RequestParam(defaultValue = "default-user") String sessionId) {

        // Clean query to avoid leaking "search_query:" prefix into prompt text
        String cleanedMessage = message.replaceFirst("(?i)^search_query:\\s*", "").trim();

        return chatClient.prompt()
                .user(cleanedMessage)
                .advisors(a -> a.param("conversation_id", sessionId))
                .stream()
                .content()
                // Prevent space swallowing in browser SSE consumption
                .map(chunk -> chunk.startsWith(" ") ? " " + chunk : chunk)
                // Make the stream hot/shared if multiple subscribers/advisors inspect it
                .share()
                // Handle reactive stream errors gracefully without throwing 500
                .onErrorResume(org.springframework.web.reactive.function.client.WebClientResponseException.class, ex -> {
                    log.error("Groq API Error Details: {}", ex.getResponseBodyAsString());
                    return Flux.just("The AI service is temporarily unavailable. Please try again.");
                })
                .onErrorResume(Exception.class, ex -> {
                    log.error("Pipeline Error: {}", ex.getMessage());
                    return Flux.just("An unexpected error occurred while streaming response.");
                });
    }
}