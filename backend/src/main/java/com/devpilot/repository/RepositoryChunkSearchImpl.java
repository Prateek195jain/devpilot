package com.devpilot.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public class RepositoryChunkSearchImpl implements RepositoryChunkSearch {
    private final JdbcTemplate jdbc;

    public RepositoryChunkSearchImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Exact cosine distance (<=>), lower is better; zero stored vectors have no cosine direction. */
    @Override
    public List<RepositoryChunkMatch> search(UUID repositoryId, UUID ownerId, String commitSha,
            String model, float[] vector, int limit) {
        return List.copyOf(jdbc.query("""
                select c.repository_id, c.commit_sha, c.source_path, c.chunk_index, c.content,
                       c.embedding <=> cast(? as vector) as distance
                from repository_chunks c
                join repositories r on r.id = c.repository_id
                where c.repository_id = ? and r.owner_id = ?
                  and c.commit_sha = ? and c.embedding_model = ?
                  and c.published = true
                  and vector_norm(c.embedding) > 0
                order by distance asc, c.source_path collate "C" asc, c.chunk_index asc
                limit ?
                """, (row, rowNumber) -> new RepositoryChunkMatch(
                        row.getObject("repository_id", UUID.class), row.getString("commit_sha"),
                        row.getString("source_path"), row.getInt("chunk_index"),
                        row.getString("content"), row.getDouble("distance")),
                Arrays.toString(vector), repositoryId, ownerId, commitSha, model, limit));
    }
}
