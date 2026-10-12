alter table repositories drop constraint repositories_indexing_status_check;
alter table repositories add constraint repositories_indexing_status_check
    check (indexing_status in ('NOT_INDEXED','INDEXING','WAITING_RETRY','READY','FAILED'));
alter table repositories add column next_indexing_attempt_at timestamptz;
alter table repositories add column indexing_retry_count integer not null default 0
    check (indexing_retry_count >= 0);
create index ix_repository_index_retry on repositories(next_indexing_attempt_at)
    where indexing_status = 'WAITING_RETRY';

-- Recover existing exhausted 429 jobs, including first imports, without another Sync.
update repositories set indexing_status='WAITING_RETRY',
    next_indexing_attempt_at=current_timestamp + interval '5 minutes', indexing_retry_count=1,
    indexing_job_token=null, indexing_lease_until=null
    where indexing_status='FAILED' and last_indexing_error_category='RATE_LIMIT'
      and indexing_target_commit_sha is not null;
