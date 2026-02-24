package io.github.manju.gitgrok.model;

import jakarta.validation.constraints.NotBlank;

public record IngestionRequest(
        @NotBlank(message = "Owner is required")
        String owner,

        @NotBlank(message = "Repository name is required")
        String repo,

        String branch,

        // Only used for single-file ingestion
        String path) {
}
