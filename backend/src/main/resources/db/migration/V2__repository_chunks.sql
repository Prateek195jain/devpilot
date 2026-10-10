create extension if not exists vector;

create table repository_chunks (
 id uuid primary key,
 created_at timestamptz not null,
 version bigint not null default 0,
 repository_id uuid not null references repositories(id),
 commit_sha varchar(64) not null check (commit_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
 source_path varchar(500) not null check (length(trim(source_path)) > 0),
 chunk_index integer not null check (chunk_index >= 0),
 content text not null check (length(trim(content)) > 0),
 content_hash varchar(64) not null check (content_hash ~ '^[0-9a-f]{64}$'),
 embedding_model varchar(100) not null check (length(trim(embedding_model)) > 0),
 embedding vector(768) not null,
 constraint uq_repository_chunks_position unique (repository_id, commit_sha, source_path, chunk_index)
);

-- The unique B-tree index also supports repository/revision/path filtering and ordering.
-- Similarity indexes belong to the retrieval milestone.
