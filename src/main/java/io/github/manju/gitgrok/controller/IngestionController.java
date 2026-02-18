package io.github.manju.gitgrok.controller;

import io.github.manju.gitgrok.service.IngestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @GetMapping("/ingest")
    public String triggerIngestion(
            @RequestParam String owner,
            @RequestParam String repo,
            @RequestParam String branch,
            @RequestParam String path) {

        ingestionService.ingestFile(owner, repo, branch, path);
        return "Ingestion started for " + path;
    }
}
