package com.devpilot.repository;

import com.devpilot.common.SecretRedactor;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RepositoryContextBuilder {
    private final RepositoryRetrievalService retrieval;
    private final int maxChars;

    public RepositoryContextBuilder(RepositoryRetrievalService retrieval,
            @Value("${app.repository-context.max-chars:24000}") int maxChars) {
        if (maxChars < 1) throw new IllegalArgumentException("Repository context budget must be positive");
        this.retrieval = retrieval;
        this.maxChars = maxChars;
    }

    public RepositoryContext build(UUID repositoryId, String commitSha, String question) {
        List<RepositoryChunkMatch> matches = retrieval.retrieve(repositoryId, commitSha, question, 10);
        String commit = commitSha.toLowerCase(Locale.ROOT);
        for (RepositoryChunkMatch match : matches) {
            if (!repositoryId.equals(match.repositoryId()) || !commit.equals(match.commitSha()))
                throw new IllegalStateException("Retrieved chunk scope does not match requested context");
        }
        StringBuilder context = new StringBuilder();
        List<RepositoryChunkMatch> included = new ArrayList<>();
        for (RepositoryChunkMatch match : matches) {
            if (included.stream().anyMatch(previous -> previous.sourcePath().equals(match.sourcePath())
                    && previous.chunkIndex() == match.chunkIndex())) continue;
            String content = match.content();
            for (RepositoryChunkMatch previous : included) {
                if (!previous.sourcePath().equals(match.sourcePath())) continue;
                if ((long) previous.chunkIndex() + 1 == match.chunkIndex()) {
                    int overlap = overlap(previous.content(), content);
                    content = content.substring(overlap);
                } else if ((long) match.chunkIndex() + 1 == previous.chunkIndex()) {
                    int overlap = overlap(content, previous.content());
                    content = content.substring(0, content.length() - overlap);
                }
            }
            if (content.isBlank()) continue;
            String block = "[Source: " + headerPath(SecretRedactor.clean(match.sourcePath()))
                    + " | chunk=" + match.chunkIndex() + "]\n" + SecretRedactor.clean(content);
            String separator = context.isEmpty() ? "" : "\n\n";
            if ((long) context.length() + separator.length() + block.length() > maxChars) continue;
            context.append(separator).append(block);
            included.add(new RepositoryChunkMatch(match.repositoryId(), match.commitSha(), match.sourcePath(),
                    match.chunkIndex(), content, match.distance()));
        }
        return new RepositoryContext(repositoryId, commit, matches.size(), included.size(),
                context.length(), context.toString(), included.stream()
                        .map(match -> new RepositorySourceReference(SecretRedactor.clean(match.sourcePath()),
                                match.chunkIndex(), commit)).toList());
    }

    private static String headerPath(String path) {
        StringBuilder escaped = new StringBuilder();
        for (char character : path.toCharArray()) {
            if (Character.isISOControl(character) || character == '\\' || character == '['
                    || character == ']' || character == '|' || character == '\u2028' || character == '\u2029')
                escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            else escaped.append(character);
        }
        return escaped.toString();
    }

    private static int overlap(String earlier, String later) {
        for (int start = 0; start < earlier.length(); start++) {
            if (start > 0 && earlier.charAt(start - 1) != '\n') continue;
            int length = earlier.length() - start;
            if (length > later.length() || earlier.charAt(earlier.length() - 1) != '\n') continue;
            if (later.regionMatches(0, earlier, start, length)
                    && earlier.substring(start).lines().filter(line -> !line.isBlank()).limit(2).count() == 2)
                return length;
        }
        return 0;
    }
}
