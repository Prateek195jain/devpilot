ALTER TABLE repositories ADD COLUMN latest_indexed_commit_sha varchar(40)
 CHECK (latest_indexed_commit_sha ~ '^[0-9a-f]{40}$');

-- Historical indexes have no completion marker. Use the most recently written
-- usable revision, breaking timestamp ties by SHA. Future successes publish the
-- marker atomically with their chunks, independently of commit or branch order.
UPDATE repositories r SET latest_indexed_commit_sha = (
 SELECT c.commit_sha FROM repository_chunks c
 WHERE c.repository_id = r.id AND length(c.commit_sha) = 40
 GROUP BY c.commit_sha
 ORDER BY max(c.created_at) DESC, c.commit_sha ASC
 LIMIT 1
);
