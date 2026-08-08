package io.github.manju.gitgrok.config;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FileExtractor that handles MULTIPLE file extractions
 * 
 * Works with queries like: 
 * - "How do OwnerRepository.java and OwnerController.java relate?"
 * - "Compare Pet.java, Owner.java, and Visit.java"
 * - "Show relationship between AuthService and UserService"
 */
public class FileExtractor {

    // Pre-compiled patterns
    private static final Pattern EXPLICIT_FILE_PATTERN =
            Pattern.compile("(?:file|from)\\s+([\\w./-]+\\.java)", Pattern.CASE_INSENSITIVE);

    private static final Pattern IN_FILE_PATTERN =
            Pattern.compile("in\\s+(?:file\\s+)?([\\w./-]+\\.java)", Pattern.CASE_INSENSITIVE);
    //FIX :Fixed an issue where non-capitalized words were incorrectly parsed as class names.
    // (e.g. "explain file WelcomeController.java" -> spurious "explain.java").
    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("([A-Z][\\w]+)(?:\\.java)?\\s+(?i:class|file|endpoints|methods|code)");

    private static final Pattern PATH_PATTERN =
            Pattern.compile("(?:path|file)\\s*[=:]?\\s*([\\w./-]+\\.java)", Pattern.CASE_INSENSITIVE);

    // FIX: Fixed duplicate file entries caused by missing extensions.
    //Moved .java inside the capture group so the pattern captures the full filename
    // (e.g., WelcomeController.java instead of WelcomeController).
    private static final Pattern STANDALONE_CLASS_PATTERN =
            Pattern.compile("\\b([A-Z][\\w]+\\.java)\\b");

    /**
     * Extract ALL filenames from query (multiple files allowed)
     * Returns List of normalized filenames, or null if none found
     *
     * @param query the user's search query
     * @return List of filenames with .java extension, or null if not found
     */
    public static List<String> extractFileNames(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }

        Set<String> files = new LinkedHashSet<>();  // Maintains insertion order, prevents duplicates

        // Strategy 1: "file WelcomeController.java" or "from WelcomeController.java"
        List<String> results = matchPatternMultiple(query, EXPLICIT_FILE_PATTERN);
        if (results != null) files.addAll(results);

        // Strategy 2: "in file WelcomeController.java" or "in WelcomeController.java"
        results = matchPatternMultiple(query, IN_FILE_PATTERN);
        if (results != null) files.addAll(results);

        // Strategy 3: "path = /src/main/java/WelcomeController.java"
        results = matchPatternMultiple(query, PATH_PATTERN);
        if (results != null) files.addAll(results);

        // Strategy 4: Standalone "WelcomeController.java" anywhere in query
        results = matchPatternMultiple(query, STANDALONE_CLASS_PATTERN);
        if (results != null) files.addAll(results);

        // Strategy 5: "WelcomeController class endpoints" (no .java extension)
        results = matchPatternMultiple(query, CLASS_NAME_PATTERN);
        if (results != null) files.addAll(results);

        // Normalize all collected filenames
        if (files.isEmpty()) {
            return null;
        }

        //FIX: Added deduplication on final normalized filenames to prevent duplicates.
        List<String> normalizedFiles = new ArrayList<>();
        Set<String> seenNormalized = new LinkedHashSet<>();
        for (String file : files) {
            String normalized = normalizeFileName(file);
            if (normalized != null && seenNormalized.add(normalized)) {
                normalizedFiles.add(normalized);
            }
        }

        return normalizedFiles.isEmpty() ? null : normalizedFiles;
    }

    /**
     * Find ALL matches for a pattern (not just first one)
     *
     * @param text    the text to search
     * @param pattern the regex pattern
     * @return List of all matches, or null if none found
     */
    private static List<String> matchPatternMultiple(String text, Pattern pattern) {
        List<String> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            matches.add(matcher.group(1));
        }

        return matches.isEmpty() ? null : matches;
    }

    /**
     * Normalize filename to always have .java extension and just the filename.
     * Handles:
     * - "WelcomeController" → "WelcomeController.java"
     * - "WelcomeController.java" → "WelcomeController.java"
     * - "/src/main/java/WelcomeController.java" → "WelcomeController.java"
     * - "com/example/WelcomeController.java" → "WelcomeController.java"
     */
    private static String normalizeFileName(String filename) {
        if (filename == null) return null;

        // Extract just the filename from path if it contains slashes
        if (filename.contains("/")) {
            filename = filename.substring(filename.lastIndexOf("/") + 1);
        }
        if (filename.contains("\\")) {
            filename = filename.substring(filename.lastIndexOf("\\") + 1);
        }

        // Add .java extension if missing
        if (!filename.endsWith(".java")) {
            filename = filename + ".java";
        }

        return filename;
    }

}