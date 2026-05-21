package io.github.manju.gitgrok.model;

public record CodeChunk(String text, String path, String className,
                        String methodName, String symbolType) {}