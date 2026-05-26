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
    public Flux<String> chat(@RequestParam String message, @RequestParam(defaultValue = "default-user") String sessionId) {
        try {
            String enhancedMessage = "search_query: " + message;
            return chatClient.prompt()
                    .user(enhancedMessage)
                    .advisors((a -> a.param("conversation_id", sessionId))
                    )
                    .stream()
                    .content()
                    .map(content -> {
                        // If the AI starts a chunk with a space, add an extra one
                        // to prevent the browser from swallowing it.
                        return content.startsWith(" ") ? " " + content : content;
                    });
        } catch (Exception e) {
            log.warn("Ollama unavailable, using mock response{}", String.valueOf(e));
            return Flux.just("Mock Response: " + message);

        }

    }
}