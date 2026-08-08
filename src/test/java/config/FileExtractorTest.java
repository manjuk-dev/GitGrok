package config;

import io.github.manju.gitgrok.config.FileExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for FileExtractor.
 * Note: normalizeFileName is private, so most tests go through the public
 * extractFileNames entry point. A couple of reflection-based tests near the
 * bottom cover normalizeFileName paths directly.
 */
class FileExtractorTest {

    // ---------- null / empty / no-match input ----------

    @Test
    void nullQuery_returnsNull() {
        assertNull(FileExtractor.extractFileNames(null));
    }

    @Test
    void emptyQuery_returnsNull() {
        assertNull(FileExtractor.extractFileNames(""));
    }

    @Test
    void blankQuery_returnsNull() {
        assertNull(FileExtractor.extractFileNames("   "));
    }

    @Test
    void noFileReference_returnsNull() {
        assertNull(FileExtractor.extractFileNames("show me how retrieval works"));
    }

    // ---------- case 1: explicit 'file X.java' / 'from X.java' ----------

    @Test
    void fileKeyword_matches() {
        List<String> result = FileExtractor.extractFileNames("explain file WelcomeController.java");
        assertEquals(List.of("WelcomeController.java"), result);
    }

    @Test
    void fromKeyword_matches() {
        List<String> result = FileExtractor.extractFileNames("show the retrieval logic from HybridPineconeVectorStore.java");
        assertEquals(List.of("HybridPineconeVectorStore.java"), result);
    }

    // ---------- case 2: 'in file X.java' / 'in X.java' ----------

    @Test
    void inFileKeyword_matches() {
        List<String> result = FileExtractor.extractFileNames("what happens in file IngestionService.java");
        assertEquals(List.of("IngestionService.java"), result);
    }

    @Test
    void inWithoutFileKeyword_matches() {
        List<String> result = FileExtractor.extractFileNames("what's the NPE fix in ChatController.java");
        assertEquals(List.of("ChatController.java"), result);
    }

    // ---------- case 3: path pattern ----------

    @Test
    void pathWithEquals_normalizesToBaseName() {
        List<String> result = FileExtractor.extractFileNames("path=/src/main/java/com/gitgrok/WelcomeController.java");
        assertEquals(List.of("WelcomeController.java"), result);
    }

    @Test
    void pathWithColon_normalizesToBaseName() {
        List<String> result = FileExtractor.extractFileNames("path: com/gitgrok/service/GitHubService.java");
        assertEquals(List.of("GitHubService.java"), result);
    }

    @Test
    void fileKeywordActingAsPathPrefix_alsoMatchesPathPattern() {
        // "file <path>" satisfies both EXPLICIT_FILE_PATTERN and PATH_PATTERN,
        // but both capture the identical raw string so LinkedHashSet dedups it.
        List<String> result = FileExtractor.extractFileNames("file com/gitgrok/IngestionController.java");
        assertEquals(List.of("IngestionController.java"), result);
    }

    // ---------- case 4: standalone 'X.java' anywhere ----------

    @Test
    void standaloneMention_matches() {
        List<String> result = FileExtractor.extractFileNames("any tests written for IngestionService.java yet?");
        assertEquals(List.of("IngestionService.java"), result);
    }

    @Test
    void lowercaseStart_doesNotMatch() {
        // STANDALONE_CLASS_PATTERN requires the name to start with an uppercase letter
        assertNull(FileExtractor.extractFileNames("check config.java for the ollama settings"));
    }

    // ---------- case 5: class name without .java extension ----------

    @Test
    void classKeyword_matches() {
        List<String> result = FileExtractor.extractFileNames("walk me through the ChatController class");
        assertEquals(List.of("ChatController.java"), result);
    }

    @Test
    void endpointsKeyword_matches() {
        List<String> result = FileExtractor.extractFileNames("list all WelcomeController endpoints");
        assertEquals(List.of("WelcomeController.java"), result);
    }

    @Test
    void methodsKeyword_matches() {
        List<String> result = FileExtractor.extractFileNames("what are the IngestionService methods");
        assertEquals(List.of("IngestionService.java"), result);
    }

    @Test
    void codeKeyword_matches() {
        List<String> result = FileExtractor.extractFileNames("show me the HybridPineconeVectorStore code");
        assertEquals(List.of("HybridPineconeVectorStore.java"), result);
    }

    @Test
    void fileKeyword_alsoMatchesClassNamePattern() {
        List<String> result = FileExtractor.extractFileNames("GitHubService file");
        assertEquals(List.of("GitHubService.java"), result);
    }

    // ---------- multiple files and ordering ----------

    @Test
    void multipleExplicitMentions_preserveInsertionOrder() {
        List<String> result = FileExtractor.extractFileNames(
                "compare file WelcomeController.java with file ChatController.java");
        assertEquals(List.of("WelcomeController.java", "ChatController.java"), result);
    }

    @Test
    void sameRawStringMatchedTwice_dedupedBySet() {
        // "IngestionService.java" appears twice verbatim -> LinkedHashSet keeps one
        List<String> result = FileExtractor.extractFileNames(
                "IngestionService.java has a bug, please check IngestionService.java again");
        assertEquals(List.of("IngestionService.java"), result);
    }

    @Test
    @DisplayName("same file referenced in different raw forms is deduped after normalization")
    void sameFileDifferentRawForm_isDedupedInResult() {
        // "com/gitgrok/WelcomeController.java" (via PATH_PATTERN) and "WelcomeController.java"
        // (via STANDALONE_CLASS_PATTERN) are different raw strings, but both normalize to
        // "WelcomeController.java". The final dedup in extractFileNames collapses them
        // into a single entry.
        List<String> result = FileExtractor.extractFileNames(
                "path=com/gitgrok/WelcomeController.java also mentioned as WelcomeController.java");

        assertEquals(List.of("WelcomeController.java"), result);
    }

    // ---------- normalizeFileName via reflection ----------

    private String normalize(String input) throws Exception {
        Method m = FileExtractor.class.getDeclaredMethod("normalizeFileName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, input);
    }

    @Test
    void normalize_nullInput_returnsNull() throws Exception {
        assertNull(normalize(null));
    }

    @Test
    void normalize_bareClassName_addsExtension() throws Exception {
        assertEquals("WelcomeController.java", normalize("WelcomeController"));
    }

    @Test
    void normalize_alreadyHasExtension_unchanged() throws Exception {
        assertEquals("WelcomeController.java", normalize("WelcomeController.java"));
    }

    @Test
    void normalize_forwardSlashPath_stripsToBaseName() throws Exception {
        assertEquals("WelcomeController.java", normalize("com/gitgrok/WelcomeController.java"));
    }

    @Test
    @DisplayName("backslash handling exists but is unreachable from extractFileNames")
    void normalize_backslashPath_stripsToBaseName() throws Exception {
        // None of the five regexes allow '\\' in the captured group ([\w./-] only),
        // so this branch in normalizeFileName can never actually be hit through the
        // public extractFileNames API on a Windows-style path. Verified directly here.
        assertEquals("WelcomeController.java", normalize("com\\gitgrok\\WelcomeController.java"));
    }
}