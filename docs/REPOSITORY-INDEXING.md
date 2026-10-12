# Asynchronous repository indexing

Import and Sync commit repository metadata, resolve the default branch to an immutable 40-character commit SHA, persist an indexing request, and return the existing successful repository response (201 for Import, 200 for Sync). They never wait for source download or embeddings. GitHub metadata/SHA resolution still happens synchronously and retains existing safe errors and ownership checks.

## Status API

GET /api/v1/repositories and GET /api/v1/repositories/{id}, plus Import/Sync responses, add:

```json
{
  "indexingStatus": "INDEXING",
  "latestIndexedCommitSha": "previous completed SHA or null",
  "indexingTargetCommitSha": "latest requested immutable SHA or null",
  "lastIndexingErrorCategory": null,
  "nextIndexingAttemptAt": null,
  "indexingRetryCount": 0
}
```

States: NOT_INDEXED, INDEXING (queued or running), WAITING_RETRY (exhausted embedding 429, scheduled automatically), READY, FAILED. Error categories are local enums only. Job tokens, leases, credentials, source contents and provider error bodies are not returned. An empty eligible snapshot is READY with a null published SHA; repeating Sync for that completed target is a no-op.

The repository list/details/chat poll metadata every five seconds. Pages remain usable. Chat continues to send latestIndexedCommitSha, including while INDEXING, WAITING_RETRY or FAILED; it never sends the target SHA. WAITING_RETRY says "DevPilot will resume automatically", shows the scheduled timestamp, and explains that previous knowledge remains available when a published SHA exists. It does not suggest Retry Sync or show Backend unavailable. Structured application errors preserve their safe message. Network failures/unstructured gateway responses use the backend-unavailable message.

## Job lifecycle and restart recovery

RepositoryIndexJobs uses repository rows as a durable queue. A dedicated scheduler polls every two seconds and dispatches to a bounded executor. The default is one active repository worker. A short row-lock transaction claims a job with a random fencing token and a five-minute lease. The dispatcher renews active leases on each tick, independently of the existing analysis scheduler. Each candidate write, failure transition and publication checks the token and persisted owner. Metadata Sync takes the same repository lock to prevent optimistic-write races with the worker.

Repeated Sync for the same target coalesces. A newer requested target replaces only the queued target field, not the active job. The active job finishes first, then the newest requested target is claimed. Intermediate targets are dropped. The active result may be published before the newest target completes; status remains INDEXING throughout that handoff.

A process crash leaves INDEXING and unpublished candidate rows. After lease expiry another worker automatically claims the latest requested target with a new token and resumes safely. A later Sync can also enqueue/retry. An old worker cannot write or publish after its token is replaced. Repository removal serializes on the same row; a removed repository cannot be recreated by a worker.

An exhausted embedding RATE_LIMIT atomically sets WAITING_RETRY, increments indexing_retry_count, persists next_indexing_attempt_at, and clears the lease/token. Candidates and the published SHA remain unchanged. Both discovery and the locked claim reject future retry timestamps. At the due time the existing poller claims the job with a fresh fencing token, sets INDEXING, and resumes without HTTP or an in-memory timer. A restart during waiting preserves the deadline/count; a restart during indexing uses the existing expired-lease recovery. Stale workers cannot write, heartbeat, fail or publish under a replaced token.

The default persisted cooldown is 5, 10, 20, then 30 minutes, capped at 30 minutes on every later 429. A larger Retry-After overrides the computed delay within that cap. There is no maximum automatic attempt count. Success or a terminal error clears retry metadata. Authentication/configuration, invalid request/response, dimension mismatch, source errors and other non-rate-limit failures remain FAILED. Local Google document retries remain bounded to three attempts with second-scale backoff/pacing; the minute-scale persisted job retry begins only after those are exhausted. Shutdown interrupts executor threads; any unrecorded state remains recoverable by lease expiry.

Sync of the same waiting target preserves its deadline/count. Sync discovering a newer commit replaces the waiting target and resets cooldown/count, making the new target eligible immediately. Existing chunks remain reusable by exact content/model within this repository. If an older build is active, it retains its lease and finishes before the newest requested target runs; an older build failure releases it and queues the latest target. Only one repository queue row exists, so repeated Sync creates no duplicate jobs.

V6 adds the retry timestamp/count and extends the status constraint. It also schedules existing FAILED/RATE_LIMIT rows with a target for recovery five minutes after migration, retaining all chunks/published pointers. That one-time migration delay is fixed; runtime scheduling uses configuration. Stop old backend instances before enabling V6/new workers so old binaries cannot deserialize WAITING_RETRY or bypass its deadline.

## Incremental construction and publication

The builder walks GitHub's target commit tree using the existing non-truncated tree APIs. It holds tree metadata, then reads one eligible file at a time. Existing exclusions, binary detection, UTF-8 validation, file-size bounds, chunk boundaries and citations are retained.

A published file with the same GitHub blob SHA, embedding model and chunker version/settings is copied to the target commit without downloading its blob or calling the embedding provider. This also supports content-preserving renames. Deleted files are absent from the target tree and are not copied. Modified/added files are downloaded and deterministically chunked; exact content hash + content + model matches reuse valid vectors from the same repository, including unpublished checkpoints. A model mismatch never reuses a vector. Legacy rows lack blob metadata, so the first refresh after migration downloads/chunks their files but still reuses exact content/model vectors.

Each completed chunk is saved as published=false with a build token. Failure retains checkpoints. On retry, valid vectors are reused and visited positions receive the new token. After the complete tree has been processed, a short transaction checks the expected candidate count, deletes unvisited unpublished positions, marks the complete candidate published, and changes latestIndexedCommitSha. These actions commit or roll back together. Previously published revisions remain immutable and available for explicitly commit-scoped retrieval. A request for an already completed historical revision simply switches the pointer to that completed index.

Retrieval adds only published=true to its eligibility filter. Cosine ranking, deterministic ordering, owner/model/commit scoping, RAG prompts and source citation shapes are unchanged. An explicit request for an incomplete target commit yields no candidate chunks.

## Configuration

| Environment variable | Default | Meaning |
|---|---:|---|
| INDEXING_WORKERS | 1 | Active repository builds per process; 1?4 |
| INDEXING_POLL_MS | 2000 | Queue polling and heartbeat interval; 100?30000 ms and less than one third of the lease |
| INDEXING_LEASE_SECONDS | 300 | Lease duration; 120?3600 |
| INDEXING_RETRY_INITIAL_SECONDS | 300 | Initial persisted cooldown; minimum 120 seconds |
| INDEXING_RETRY_MAX_SECONDS | 1800 | Cooldown/Retry-After cap; initial value through 86400 seconds |
| EMBEDDING_CONCURRENCY | 1 | Google document requests including retries; 1?2 |
| EMBEDDING_MIN_INTERVAL_MS | 1000 | Minimum interval between document request starts |
| EMBEDDING_MAX_ATTEMPTS | 3 | Total document attempts; 1?5 |
| EMBEDDING_RATE_LIMIT_BACKOFF_MS | 2000 | Initial exponential rate-limit delay |
| EMBEDDING_TRANSIENT_BACKOFF_MS | 250 | Initial exponential network/5xx delay |
| EMBEDDING_MAX_BACKOFF_MS | 60000 | Maximum delay; at most 120000 |

A 429 uses max(exponential delay, parsed Retry-After), adds up to 25% positive jitter, and caps the result. Defaults yield 2?2.5 seconds then 4?5 seconds without Retry-After. A shared provider cooldown also delays other document calls after a 429. 408, 5xx, timeout and temporary network failures use the shorter bounded policy. 400, 401/403, invalid configuration/TLS, malformed responses and dimension mismatch are not retried. Query embedding behavior remains one attempt, and document concurrency/pacing does not change chat prompts or retrieval.

Successful builds log repository_index_completed with repository ID, commit SHA, file/chunk counts, reusedChunks and embeddedChunks. These counters make quota savings reviewable without logging paths or source text. Failure logs use only repository ID plus e.g. provider=google category=RATE_LIMIT httpStatus=429 attempts=3, or a local exception class for other failures. SecretRedactor remains applied before embedding source input. Provider exception bodies/causes are discarded.

## Local verification

1. Start Docker. Use Java 21 and run from the repository root:
   mvn -f backend/pom.xml test
   npm.cmd run test:client
   npm.cmd run typecheck
2. Start the backend/frontend using the existing local deployment instructions, with Google embedding configuration supplied securely. Import a test repository. The response should contain INDEXING and a target SHA, without waiting for embedding completion.
3. Poll GET /api/v1/repositories/{id} until READY. Record latestIndexedCommitSha as A. Push a change to one file, plus an addition/deletion/rename, then click Sync. Confirm status INDEXING with A still published; chat remains usable.
4. When READY, confirm SHA B and citations using B paths; deleted paths must be absent from B. Repeat Sync without a commit change and confirm it stays READY without provider calls. The automated tests measure zero calls for unchanged blobs and only new chunk calls for modified files.
5. Use the controlled provider fixtures in GoogleEmbeddingProviderTest and RepositorySyncPostgresTest to verify 429 exhaustion, hidden candidates, checkpoint reuse and complete publication without modifying real provider credentials. Do not simulate failures by changing production keys.
6. In a local environment only, restart the backend during a large build. After the lease expires, confirm it resumes and eventually reaches READY with complete target rows. During recovery the previous SHA remains usable.

Read-only database inspection (replace the UUID parameter in your SQL client):

select indexing_status, latest_indexed_commit_sha, indexing_target_commit_sha,
       last_indexing_error_category, next_indexing_attempt_at, indexing_retry_count,
       indexing_job_token, indexing_lease_until from repositories where id = :repository_id;
select commit_sha, published, count(*) from repository_chunks
       where repository_id = :repository_id group by commit_sha, published;

## Production verification

1. Run the full Java 21 suite with Docker and confirm PostgreSQL/pgvector tests are not skipped. Stop old backend instances, deploy the backend with V6 via Flyway, then deploy the frontend. Confirm readiness and migrations before testing imports.
2. Keep the conservative defaults above. Import a representative large test repository; confirm the HTTP response completes after metadata/SHA resolution, with INDEXING. Monitor category/status/attempt logs without enabling HTTP wire logging.
3. Poll the repository GET endpoint and the read-only SQL above. While INDEXING, unpublished target rows may grow, but latestIndexedCommitSha must retain A (or null for first import). Chat must continue using A.
4. Confirm READY and the correct immutable target SHA only after the target is complete. Push a small change and Sync again. Verify changed/new files and target-scoped citations; unchanged files should reuse vectors. Check repository_index_completed: a no-content-change commit should have embeddedChunks=0; a small source change should embed only new chunks. Compare indexing duration and provider quota consumption with initial import.
5. Click Sync repeatedly during a build; confirm no concurrent jobs for that repository. Push a newer commit and Sync; confirm the current build finishes, then the newest target runs.
6. If a natural 429 exhausts retries, confirm WAITING_RETRY/RATE_LIMIT, a future retry timestamp, incremented count, null lease/token, preserved old pointer and hidden candidate rows. Do not click Sync. After the timestamp, confirm INDEXING automatically and then READY when complete; repeated 429s should increase cooldown up to its cap. Verify a first import keeps a null pointer until complete.
7. Perform restart recovery verification on a staging deployment with production-equivalent configuration before relying on it in production. A rolling restart must never expose incomplete target chunks.

## Remaining limits

Rate budgets are shared within a process, not across replicas or other applications using the same Google quota. Keep one Render instance or divide the configured request budget across instances. Leases/fencing protect publication across instances, but a paused stale worker can briefly overlap a provider request before its next fence check. No partial write from that worker can publish.

GitHub metadata/SHA resolution still uses synchronous HTTP calls; GitHub outages can delay Import/Sync. Tree walking is repeated on each new target; unchanged directories are not cached. Whole eligible files are bounded by the existing size limit, but very long individual lines may still exceed the provider payload bound. Published revision history and failed checkpoints are retained; retention/garbage collection is a separate future storage task. The implementation does not promise a provider quota that the account cannot sustain; settings should be tuned to the account's limits.

## Earlier checkpointing validation results

Java 21.0.12.1: 311 backend tests passed, 0 failed, 0 skipped. PostgreSQL 16 + pgvector: 55 included tests passed (schema 2, chunk persistence/retrieval 21, removal 4, async/incremental/staged indexing 28).

Frontend: 74 tests passed; TypeScript, targeted ESLint and git diff --check passed. The backend run used an identical generated POM with isolated output directories to prevent the IDE from overwriting Maven test classes; dependencies, source files and test selection were unchanged. Reports are in backend/target/scalable-validation-build/surefire-reports; the run log is backend/target/scalable-index-verified.log. Live provider/production load tests were not run.

## File inventory

- backend/src/main/java/com/devpilot/ai/EmbeddingFailure.java
- backend/src/main/java/com/devpilot/ai/EmbeddingPacing.java
- backend/src/main/java/com/devpilot/ai/GoogleEmbeddingProvider.java
- backend/src/main/java/com/devpilot/repository/IndexingErrorCategory.java
- backend/src/main/java/com/devpilot/repository/IndexingStatus.java
- backend/src/main/java/com/devpilot/repository/RepositoryChunk.java
- backend/src/main/java/com/devpilot/repository/RepositoryChunkSearchImpl.java
- backend/src/main/java/com/devpilot/repository/RepositoryChunkStore.java
- backend/src/main/java/com/devpilot/repository/RepositoryIndexJobs.java
- backend/src/main/java/com/devpilot/repository/RepositoryIndexWorker.java
- backend/src/main/java/com/devpilot/repository/RepositoryIndexingService.java
- backend/src/main/java/com/devpilot/repository/RepositoryService.java
- backend/src/main/java/com/devpilot/repository/RepositorySourceAcquisitionService.java
- backend/src/main/java/com/devpilot/repository/RepositorySourceChunker.java
- backend/src/main/java/com/devpilot/repository/RepositoryStore.java
- backend/src/main/java/com/devpilot/repository/RepositorySyncService.java
- backend/src/main/java/com/devpilot/repository/TrackedRepository.java
- backend/src/main/java/com/devpilot/repository/TrackedRepositoryView.java
- backend/src/main/resources/application.yml
- backend/src/main/resources/db/migration/V5__repository_index_jobs.sql (unchanged by automatic retry)
- backend/src/main/resources/db/migration/V6__repository_index_retry.sql
- backend/src/test/java/com/devpilot/ai/EmbeddingPacingTest.java
- backend/src/test/java/com/devpilot/ai/GoogleEmbeddingProviderTest.java
- backend/src/test/java/com/devpilot/repository/RepositoryChatSmokeIT.java
- backend/src/test/java/com/devpilot/repository/RepositoryIndexingServiceTest.java
- backend/src/test/java/com/devpilot/repository/RepositoryIndexingSmokeIT.java
- backend/src/test/java/com/devpilot/repository/RepositorySyncPostgresTest.java
- backend/src/test/java/com/devpilot/repository/RepositorySyncServiceTest.java
- components/devpilot/app.tsx
- components/devpilot/indexing-status.tsx
- components/devpilot/screens/Chat.tsx
- components/devpilot/screens/Repositories.tsx
- components/devpilot/screens/RepositoryDetails.tsx
- docs/REPOSITORY-INDEXING.md
- lib/devpilot-api.ts
- package.json
- tests/chat-message.test.mjs
- tests/client.test.mjs
- tests/repository-indexing.test.mjs

## Automatic retry verification (V6)

Run these exact commands from the repository root on this Windows workstation. Docker Desktop must already be running:

```powershell
$env:JAVA_HOME='C:\Users\prateek\.jdks\temurin-21.0.12.1'
$env:PATH="$env:JAVA_HOME\bin;C:\Users\prateek\AppData\Local\Programs\DockerDesktop\resources\bin;$env:PATH"
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
mvn.cmd '-Dmaven.repo.local=C:\Users\prateek\.m2\repository' -f backend/pom.xml test
npm.cmd run test:client
npm.cmd run typecheck
npx.cmd eslint components/devpilot/indexing-status.tsx lib/devpilot-api.ts tests/repository-indexing.test.mjs tests/client.test.mjs
git diff --check
```

Inspect the Maven summary and PostgreSQL report files: Docker-dependent tests must have zero skipped cases. Tests advance the persisted timestamp inside their isolated test database rather than sleeping for minutes. The real HTTP 429 fixture still verifies three local requests before WAITING_RETRY; shared H2/PostgreSQL tests verify the due-time poller and reused checkpoint vectors. No live Google credentials are required.

For manual local restart verification, import once using a controlled embedding endpoint that returns 429. Observe WAITING_RETRY, future timestamp, retry count 1, null lease/token and candidate chunks. Restart the backend before the deadline without Sync. Restore successful responses at the endpoint. Observe no claim before the deadline, an automatic claim afterward, reuse of saved vectors, and atomic READY publication. Repeat with an existing published index and confirm chat keeps its published SHA throughout. Non-rate-limit fixtures must remain FAILED without a scheduled timestamp.

For the reported production repository, deploy V6/new backend after old instances stop, then the frontend. No manual Sync is needed for the existing FAILED/RATE_LIMIT row. Query:

```sql
select indexing_status, indexing_target_commit_sha, latest_indexed_commit_sha,
       last_indexing_error_category, next_indexing_attempt_at, indexing_retry_count,
       indexing_job_token, indexing_lease_until
from repositories
where id='0d0a1a98-ce60-4601-8744-ee9732cc5233';

select commit_sha, published, build_token, count(*) as chunks,
       count(distinct source_path) as files
from repository_chunks
where repository_id='0d0a1a98-ce60-4601-8744-ee9732cc5233'
group by commit_sha, published, build_token;
```

Immediately after migration, expect WAITING_RETRY/RATE_LIMIT, target 65a11c75ac1bfe3d50f46a5b406516d8d1072941, null published pointer, retry count 1 and a timestamp five minutes after migration. The reported 205 candidate chunks across 158 files must still exist (or more if subsequent attempts already progressed). Record chunk IDs and vectors before the deadline; migration/pause must not modify them. Poll GET /api/v1/repositories/0d0a1a98-ce60-4601-8744-ee9732cc5233 with the authenticated owner session. Confirm the automatic-resume UI and no manual-Sync instruction.

Leave Sync untouched. At/after the deadline expect INDEXING and a fresh lease/token; existing vectors are reused and only missing work calls embeddings. A resumed build restages visited chunks under the new fencing token. A repeated exhausted 429 sets a later deadline with increased count; monitor repository_index_retry_scheduled logs. Eventually expect READY, cleared retry metadata and latestIndexedCommitSha equal to the completed target, with all target chunks published in one transaction. Retry timing is approximate by polling interval, worker availability and provider/source latency.

Verify restart recovery first on staging: restart during WAITING_RETRY, retain its timestamp/count, and observe automatic progress after the deadline. Restart during INDEXING and observe recovery after lease expiry. While waiting for A, push B and Sync once: the queued target becomes B, cooldown/count reset, A candidates remain reusable, and there is still one queue row. Never change production provider credentials to force 429/auth failures.

Limits: automatic resume applies specifically to exhausted embedding RATE_LIMIT. Other failure categories remain terminal. A permanently exhausted quota can stay WAITING_RETRY indefinitely; there is no promise of completion until the provider allows requests. Cooldown is per repository, while document pacing is shared per process; quota is not globally coordinated across replicas. Retained checkpoints/history still require future retention policy. Production/live-provider verification was not performed by this local change.
## V6 change report

Changed production files: IndexingStatus.java; RepositoryIndexJobs.java; RepositoryIndexWorker.java (attempt log wording); RepositoryStore.java; TrackedRepository.java; TrackedRepositoryView.java; application.yml; new V6__repository_index_retry.sql; components/devpilot/indexing-status.tsx; lib/devpilot-api.ts. Changed tests: PostgresSchemaTest.java; RepositoryIndexingServiceTest.java; RepositorySyncServiceTest.java; RepositorySyncPostgresTest.java; tests/client.test.mjs; tests/repository-indexing.test.mjs. This document was updated. V5, provider/local pacing implementation, Sync/source builder, authentication, ranking, prompts, citation/security logic and embedding dimensions/model were not changed.

Validation on 2026-10-12: Java 21.0.12.1 full suite passed 324 tests, zero failures/errors/skips, including 60 PostgreSQL/pgvector tests. Frontend passed 77 tests, zero failures/skips. TypeScript, targeted ESLint and git diff --check passed. Full backend verification used an identical generated POM with isolated build/source/resource directories to prevent IDE output collisions; dependencies/test selection were unchanged. Full log: backend/target/automatic-retry-verified.log. Reports: backend/target/automatic-retry-validation-build/surefire-reports. Frontend log: backend/target/automatic-retry-client.log. The subsequent targeted rerun after the final diagnostic log addition passed 60 tests, zero failures/errors/skips; log: backend/target/automatic-retry-final-targeted.log. No production deployment or live provider load test was performed.