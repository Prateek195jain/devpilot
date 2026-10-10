package com.devpilot.repository;

import com.devpilot.common.Crypto;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pure, deterministic line-based transformation. Limits count UTF-16 code units
 * (String.length), including normalized LF terminators; they are not token limits.
 * Oversized individual lines are emitted intact and alone. Overlap is a best-effort
 * suffix of whole lines, bounded by overlapChars and room for the next new line.
 * File text is otherwise preserved; whitespace-only chunks are omitted.
 */
@Component
public class RepositorySourceChunker {
    private final int maxChars;
    private final int overlapChars;

    public RepositorySourceChunker(
            @Value("${app.source-chunking.max-chars:4000}") int maxChars,
            @Value("${app.source-chunking.overlap-chars:400}") int overlapChars) {
        if (maxChars < 1 || overlapChars < 0 || overlapChars >= maxChars)
            throw new IllegalArgumentException("Chunk size must be positive and overlap must be between zero and size minus one");
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    public List<SourceChunk> chunk(RepositorySources sources) {
        Objects.requireNonNull(sources, "Repository sources are required");
        if (sources.repositoryId() == null || sources.commitSha() == null
                || !sources.commitSha().matches("[0-9a-f]{40}|[0-9a-f]{64}"))
            throw new IllegalArgumentException("Repository identity and immutable commit SHA are required");
        SortedMap<String, RepositorySources.File> files = new TreeMap<>();
        for (var file : sources.files()) {
            if (file.path() == null || file.path().isBlank() || file.content() == null)
                throw new IllegalArgumentException("Source path and content are required");
            if (files.putIfAbsent(file.path(), file) != null)
                throw new IllegalArgumentException("Duplicate source paths are not supported");
        }
        List<SourceChunk> chunks = new ArrayList<>();
        for (var file : files.values()) chunkFile(sources, file, chunks);
        return List.copyOf(chunks);
    }

    private void chunkFile(RepositorySources sources, RepositorySources.File file, List<SourceChunk> output) {
        String normalized = file.content().replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isBlank()) return;
        // Keep each actual line's terminator, without inventing a line after a final LF.
        String[] lines = normalized.split("(?<=\n)");
        int start = 0;
        int index = 0;
        while (start < lines.length) {
            int end = start;
            StringBuilder text = new StringBuilder();
            while (end < lines.length) {
                if (end > start && (long) text.length() + lines[end].length() > maxChars) break;
                text.append(lines[end++]);
                if (text.length() >= maxChars) break;
            }
            String content = text.toString();
            if (!content.isBlank()) {
                output.add(new SourceChunk(sources.repositoryId(), sources.commitSha(), file.path(),
                        index++, content, Crypto.hash(content), start + 1, end));
            }
            if (end == lines.length) break;

            int next = end;
            long overlapSize = 0;
            // Never repeat the whole previous window. Always leave room for new content,
            // even when one line fills/exceeds maxChars or overlap is near the maximum.
            while (next > start + 1) {
                long candidateSize = overlapSize + lines[next - 1].length();
                if (candidateSize > overlapChars || candidateSize + lines[end].length() > maxChars) break;
                overlapSize = candidateSize;
                next--;
            }
            start = next;
        }
    }
}
