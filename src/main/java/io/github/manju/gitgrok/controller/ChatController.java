package io.github.manju.gitgrok.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam String message,  @RequestParam(defaultValue = "default-user")  String sessionId) {
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
    }
}