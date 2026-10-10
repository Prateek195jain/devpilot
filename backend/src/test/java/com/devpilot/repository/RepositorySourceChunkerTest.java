package com.devpilot.repository;

import com.devpilot.common.Crypto;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositorySourceChunkerTest {
    private static final UUID REPOSITORY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String COMMIT = "a".repeat(40);

    private RepositorySources.File file(String path, String text) {
        return new RepositorySources.File(path, text, text.getBytes(StandardCharsets.UTF_8).length, Crypto.hash(text));
    }

    private RepositorySources snapshot(RepositorySources.File... files) {
        return new RepositorySources(REPOSITORY, COMMIT, List.of(files));
    }

    private List<SourceChunk> chunk(int size, int overlap, String text) {
        return new RepositorySourceChunker(size, overlap).chunk(snapshot(file("src/A.java", text)));
    }

    @Test void sortsFilesResetsIndexesAndRepeatsExactly() {
        var chunker = new RepositorySourceChunker(4, 0);
        var a = file("a.java", "aa\nbb\ncc");
        var z = file("z.java", "zz");
        var input = snapshot(z, a);
        var result = chunker.chunk(input);
        assertEquals(result, chunker.chunk(input));
        assertEquals(result, chunker.chunk(snapshot(a, z)));
        assertEquals(List.of("a.java", "a.java", "a.java", "z.java"), result.stream().map(SourceChunk::sourcePath).toList());
        assertEquals(List.of(0, 1, 2, 0), result.stream().map(SourceChunk::chunkIndex).toList());
        assertEquals(List.of("aa\n", "bb\n", "cc", "zz"), result.stream().map(SourceChunk::content).toList());
        assertTrue(result.stream().allMatch(c -> c.repositoryId().equals(REPOSITORY) && c.commitSha().equals(COMMIT)));
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }

    @Test void smallAndExactLimitFilesRemainWhole() {
        assertEquals(List.of("abc"), chunk(4, 0, "abc").stream().map(SourceChunk::content).toList());
        var exact = chunk(4, 0, "a\nb\n");
        assertEquals(1, exact.size());
        assertEquals("a\nb\n", exact.getFirst().content());
        assertEquals(1, exact.getFirst().startLine());
        assertEquals(2, exact.getFirst().endLine());
        assertEquals(1, chunk(4, 0, "abcd").size());
    }

    @Test void overlapsOnlyWholeLinesWithCorrectLineRanges() {
        var chunks = chunk(6, 2, "a\nb\nc\nd\ne\n");
        assertEquals(List.of("a\nb\nc\n", "c\nd\ne\n"), chunks.stream().map(SourceChunk::content).toList());
        assertEquals(List.of(1, 3), chunks.stream().map(SourceChunk::startLine).toList());
        assertEquals(List.of(3, 5), chunks.stream().map(SourceChunk::endLine).toList());
        assertEquals(List.of("aa\nbb\n", "cc\n"), chunk(6, 2, "aa\nbb\ncc\n").stream().map(SourceChunk::content).toList());
    }

    @Test void overlapShrinksToFitNextLineAndAlwaysMakesProgress() {
        var chunks = chunk(6, 5, "a\nb\nc\ndddd\ne\n");
        assertEquals(List.of("a\nb\nc\n", "dddd\n", "e\n"), chunks.stream().map(SourceChunk::content).toList());
        var nearMaximum = chunk(4, 3, "a\nb\nc\nd\n");
        assertEquals(List.of("a\nb\n", "b\nc\n", "c\nd\n"), nearMaximum.stream().map(SourceChunk::content).toList());
    }

    @Test void longIndividualLinesAreIntactAndStandalone() {
        var chunks = chunk(4, 2, "a\n0123456789\nz\n");
        assertEquals(List.of("a\n", "0123456789\n", "z\n"), chunks.stream().map(SourceChunk::content).toList());
        assertEquals(List.of(1, 2, 3), chunks.stream().map(SourceChunk::startLine).toList());
        assertEquals(List.of(1, 2, 3), chunks.stream().map(SourceChunk::endLine).toList());
        assertEquals("😀", chunk(1, 0, "😀").getFirst().content());
    }

    @Test void normalizesLineEndingsAndHashesExactChunkText() {
        var unix = chunk(4, 0, "a\nb\nc");
        assertEquals(unix, chunk(4, 0, "a\r\nb\r\nc"));
        assertEquals(unix, chunk(4, 0, "a\rb\rc"));
        assertEquals(unix, chunk(4, 0, "a\r\nb\rc"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                chunk(4, 0, "abc").getFirst().contentHash());
        assertNotEquals(chunk(4, 0, "abc").getFirst().contentHash(), chunk(4, 0, "abc\n").getFirst().contentHash());
    }

    @Test void omitsBlankFilesAndChunksWithoutLosingLineNumbersOrIndexContinuity() {
        assertTrue(chunk(4, 0, "").isEmpty());
        assertTrue(chunk(4, 0, " \t\r\n\n").isEmpty());
        var chunks = chunk(2, 0, "\n\na\n\n\nb");
        assertEquals(List.of("a\n", "b"), chunks.stream().map(SourceChunk::content).toList());
        assertEquals(List.of(0, 1), chunks.stream().map(SourceChunk::chunkIndex).toList());
        assertEquals(List.of(3, 6), chunks.stream().map(SourceChunk::startLine).toList());
        assertEquals("  a\n\n b", chunk(20, 0, "  a\n\n b").getFirst().content());
        assertTrue(new RepositorySourceChunker(4, 0).chunk(snapshot()).isEmpty());
    }

    @Test void largeFileHasCompleteCoverageAndBoundedChunks() {
        String text = "line\n".repeat(10_000);
        var chunks = chunk(4000, 400, text);
        assertTrue(chunks.size() > 1);
        int lastEnd = 0;
        for (var c : chunks) {
            assertTrue(c.content().length() <= 4000);
            assertTrue(c.startLine() <= lastEnd + 1);
            assertTrue(c.endLine() > lastEnd);
            assertEquals("line\n".repeat(c.endLine() - c.startLine() + 1), c.content());
            lastEnd = c.endLine();
        }
        assertEquals(10_000, lastEnd);
        assertEquals(text, String.join("", chunk(4000, 0, text).stream().map(SourceChunk::content).toList()));
    }

    @Test void rejectsInvalidConfigurationAndAmbiguousInput() {
        assertThrows(IllegalArgumentException.class, () -> new RepositorySourceChunker(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RepositorySourceChunker(4, -1));
        assertThrows(IllegalArgumentException.class, () -> new RepositorySourceChunker(4, 4));
        var chunker = new RepositorySourceChunker(4, 0);
        assertThrows(IllegalArgumentException.class, () -> chunker.chunk(snapshot(file("a", "a"), file("a", "b"))));
        assertThrows(IllegalArgumentException.class, () -> chunker.chunk(new RepositorySources(REPOSITORY, "main", List.of())));
        assertThrows(IllegalArgumentException.class, () -> chunker.chunk(snapshot(file(" ", "a"))));
    }
}
