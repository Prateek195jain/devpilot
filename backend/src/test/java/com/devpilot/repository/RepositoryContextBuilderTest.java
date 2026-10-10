package com.devpilot.repository;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepositoryContextBuilderTest {
    private static final UUID REPOSITORY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String COMMIT = "a".repeat(40);
    private final RepositoryRetrievalService retrieval = mock(RepositoryRetrievalService.class);

    private RepositoryChunkMatch match(String path, int index, String content) {
        return new RepositoryChunkMatch(REPOSITORY, COMMIT, path, index, content, 0.1);
    }

    private RepositoryContext build(int budget, RepositoryChunkMatch... matches) {
        when(retrieval.retrieve(REPOSITORY, COMMIT, "question", 10)).thenReturn(List.of(matches));
        return new RepositoryContextBuilder(retrieval, budget).build(REPOSITORY, COMMIT, "question");
    }

    @Test void deterministicRankingMetadataAndExactTextAcrossFiles() {
        var first = match("z.java", 2, "  first();\r\n\tsecond();\n");
        var second = match("a.java", 0, "last();");
        var result = build(24000, first, second);
        assertEquals("[Source: z.java | chunk=2]\n  first();\r\n\tsecond();\n\n\n"
                + "[Source: a.java | chunk=0]\nlast();", result.text());
        assertEquals(result, build(24000, first, second));
        assertEquals(REPOSITORY, result.repositoryId());
        assertEquals(COMMIT, result.commitSha());
        assertEquals(2, result.matchesConsidered());
        assertEquals(2, result.chunksIncluded());
        assertEquals(result.text().length(), result.characterCount());
        assertEquals(List.of(new RepositorySourceReference("z.java",2,COMMIT),
                new RepositorySourceReference("a.java",0,COMMIT)),result.sources());
    }

    @Test void exactBudgetIncludesHeadersAndSeparators() {
        var first = match("a", 0, "one");
        var second = match("b", 0, "two");
        int length = build(24000, first, second).characterCount();
        assertEquals(2, build(length, first, second).chunksIncluded());
        var smaller = build(length - 1, first, second);
        assertEquals(1, smaller.chunksIncluded());
        assertEquals("[Source: a | chunk=0]\none", smaller.text());
    }

    @Test void skipsOversizedChunkAndContinuesInRankOrder() {
        var result = build(40, match("a", 0, "x".repeat(100)), match("b", 0, "fits"));
        assertEquals("[Source: b | chunk=0]\nfits", result.text());
        assertEquals(List.of(new RepositorySourceReference("b",0,COMMIT)),result.sources());
        assertEquals(2, result.matchesConsidered());
        assertTrue(result.characterCount() <= 40);
    }

    @Test void oversizedSingleChunkAndTinyBudgetReturnEmpty() {
        assertEquals("", build(40, match("a", 0, "x".repeat(100))).text());
        assertEquals(0, build(1, match("a", 0, "x")).chunksIncluded());
        assertTrue(build(1, match("a",0,"x")).sources().isEmpty());
    }

    @Test void emptyRetrievalDoesNotFallback() {
        var result = build(24000);
        assertEquals(new RepositoryContext(REPOSITORY, COMMIT, 0, 0, 0, ""), result);
        verify(retrieval).retrieve(REPOSITORY, COMMIT, "question", 10);
        verifyNoMoreInteractions(retrieval);
    }

    @Test void removesWholeLineOverlapFromLowerRankedAdjacentChunk() {
        var result = build(24000, match("a", 0, "start\nshared1\nshared2\n"),
                match("a", 1, "shared1\nshared2\nend\n"));
        assertEquals("[Source: a | chunk=0]\nstart\nshared1\nshared2\n\n\n"
                + "[Source: a | chunk=1]\nend\n", result.text());
    }

    @Test void reverseSourceOrderStillPreservesRetrievalRanking() {
        var result = build(24000, match("a", 1, "shared1\nshared2\nend\n"),
                match("a", 0, "start\nshared1\nshared2\n"));
        assertEquals("[Source: a | chunk=1]\nshared1\nshared2\nend\n\n\n"
                + "[Source: a | chunk=0]\nstart\n", result.text());
        assertEquals(List.of(new RepositorySourceReference("a",1,COMMIT),
                new RepositorySourceReference("a",0,COMMIT)),result.sources());
    }

    @Test void fullyOverlappingAndBlankChunksDoNotBecomeSources() {
        var result = build(24000, match("a",0,"start\nshared1\nshared2\n"),
                match("a",1,"shared1\nshared2\n"), match("blank",0,"  "));
        assertEquals(List.of(new RepositorySourceReference("a",0,COMMIT)),result.sources());
        assertEquals(1,result.chunksIncluded());
    }

    @Test void canRemoveBothEndsWhenBothNeighborsAreAlreadyIncluded() {
        var result = build(24000, match("a", 0, "start\nleft1\nleft2\n"),
                match("a", 2, "right1\nright2\nend\n"),
                match("a", 1, "left1\nleft2\nmiddle\nright1\nright2\n"));
        assertTrue(result.text().endsWith("[Source: a | chunk=1]\nmiddle\n"));
    }

    @Test void preservesUncertainOverlapAndDifferentFiles() {
        for (var second : List.of(match("a", 1, "same\nend\n"),
                match("a", 2, "one\nsame\nend\n"), match("b", 1, "one\nsame\nend\n"))) {
            var result = build(24000, match("a", 0, "start\none\nsame\n"), second);
            assertTrue(result.text().endsWith(second.content()));
        }
    }

    @Test void deduplicatesIdentityButNotIdenticalTextInOtherFiles() {
        var first = match("a", 0, "same");
        var result = build(24000, first, first, match("b", 0, "same"));
        assertEquals(3, result.matchesConsidered());
        assertEquals(2, result.chunksIncluded());
        assertEquals(List.of(new RepositorySourceReference("a",0,COMMIT),
                new RepositorySourceReference("b",0,COMMIT)),result.sources());
    }

    @Test void skippedChunkDoesNotRemoveOverlapFromIncludedChunk() {
        String text = "shared1\nshared2\nend\n";
        var result = build(80, match("a", 0, "x".repeat(100) + "\nshared1\nshared2\n"),
                match("a", 1, text));
        assertEquals("[Source: a | chunk=1]\n" + text, result.text());
    }

    @Test void rejectsCrossRepositoryAndCrossCommitResultsEvenOutsideBudget() {
        for (var foreign : List.of(new RepositoryChunkMatch(UUID.randomUUID(), COMMIT, "a", 0, "secret", 0),
                new RepositoryChunkMatch(REPOSITORY, "b".repeat(40), "a", 0, "secret", 0))) {
            assertThrows(IllegalStateException.class, () -> build(1, match("ok", 0, "ok"), foreign));
        }
    }

    @Test void delegatesOriginalRequestAndNormalizesResultCommit() {
        String uppercase = COMMIT.toUpperCase(Locale.ROOT);
        when(retrieval.retrieve(REPOSITORY, uppercase, "question", 10)).thenReturn(List.of());
        assertEquals(COMMIT, new RepositoryContextBuilder(retrieval, 24000)
                .build(REPOSITORY, uppercase, "question").commitSha());
        verify(retrieval).retrieve(REPOSITORY, uppercase, "question", 10);
        verifyNoMoreInteractions(retrieval);
    }

    @Test void retrievalFailurePropagatesWithoutFallback() {
        var failure = new IllegalStateException("unavailable");
        when(retrieval.retrieve(REPOSITORY, COMMIT, "question", 10)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new RepositoryContextBuilder(retrieval, 24000).build(REPOSITORY, COMMIT, "question")));
        verifyNoMoreInteractions(ignoreStubs(retrieval));
    }

    @Test void redactsTokensInPathsAndContentBeforeCountingBudget() {
        String token = "ghp_" + "a".repeat(25);
        var result = build(24000, match(token + ".java", 0, "String token = \"" + token + "\";"));
        assertFalse(result.text().contains(token));
        assertEquals("[REDACTED_TOKEN].java",result.sources().get(0).sourcePath());
        assertTrue(result.text().contains("String token = \"[REDACTED_TOKEN]\";"));
        assertEquals(result.text().length(), result.characterCount());
        assertEquals(result, build(result.characterCount(), match(token + ".java", 0,
                "String token = \"" + token + "\";")));
    }

    @Test void escapesHeaderDelimitersAndNewlinesInPaths() {
        var result = build(24000, match("a\n]|.java", 0, "code"));
        assertEquals("[Source: a\\u000a\\u005d\\u007c.java | chunk=0]\ncode", result.text());
    }

    @Test void rejectsNonpositiveBudget() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryContextBuilder(retrieval, 0));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryContextBuilder(retrieval, -1));
    }
}
