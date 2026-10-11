alter table repositories add column indexing_status varchar(20) not null default 'NOT_INDEXED'
    check (indexing_status in ('NOT_INDEXED','INDEXING','READY','FAILED'));
alter table repositories add column indexing_target_commit_sha varchar(40)
    check (indexing_target_commit_sha ~ '^[0-9a-f]{40}$');
alter table repositories add column last_indexing_error_category varchar(40)
    check (last_indexing_error_category in ('RATE_LIMIT','PROVIDER_UNAVAILABLE','TIMEOUT','NETWORK','AUTHENTICATION',
    'INVALID_REQUEST','INVALID_RESPONSE','DIMENSION_MISMATCH','CONFIGURATION','SOURCE_UNAVAILABLE','INTERNAL'));
alter table repositories add column indexing_job_token uuid;
alter table repositories add column indexing_lease_until timestamptz;
update repositories set indexing_status='READY', indexing_target_commit_sha=latest_indexed_commit_sha
    where latest_indexed_commit_sha is not null;
create index ix_repository_index_jobs on repositories(indexing_status,indexing_lease_until);

-- Existing rows were published atomically by the previous indexer.
alter table repository_chunks add column published boolean not null default true;
alter table repository_chunks add column build_token uuid;
alter table repository_chunks add column blob_sha varchar(40) check (blob_sha ~ '^[0-9a-f]{40}$');
alter table repository_chunks add column chunking_key varchar(100);
create index ix_repository_chunk_reuse on repository_chunks(repository_id,content_hash,embedding_model);
create index ix_repository_blob_reuse on repository_chunks(repository_id,blob_sha,embedding_model,chunking_key);
