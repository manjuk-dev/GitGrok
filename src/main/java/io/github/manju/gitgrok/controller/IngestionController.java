package io.github.manju.gitgrok.controller;

import io.github.manju.gitgrok.model.IngestionRequest;
import io.github.manju.gitgrok.service.IngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/ingest")
public class IngestionController {
    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    // Endpoint for full repo
    @PostMapping("/repo")
    public ResponseEntity<String> ingestRepo(@RequestBody IngestionRequest request) {
        ingestionService.ingestEntireRepo(request.owner(), request.repo(), request.branch());
        return ResponseEntity.ok("Full repo ingestion complete.");
    }

    // Endpoint for just one file
    @PostMapping("/file")
    public ResponseEntity<String> ingestFile(@RequestBody IngestionRequest request) {
        ingestionService.ingestSingleFile(request.owner(), request.repo(), request.branch(), request.path());
        return ResponseEntity.ok("Single file update complete.");
    }
}
