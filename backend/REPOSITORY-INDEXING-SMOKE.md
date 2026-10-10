# Manual repository indexing smoke test

`RepositoryIndexingSmokeIT` indexes an existing owned repository (such as the
restored PagePulse repository) at one supplied immutable commit. It reuses the
production indexing, GitHub integration, acquisition, chunking, embedding, and
persistence beans. No migrations, SQL initialization, workers, or chat AI start.

## Required environment

```text
RUN_REPOSITORY_INDEXING_SMOKE=true
REPOSITORY_INDEXING_USER_ID=<PagePulse repository owner's stored UUID>
REPOSITORY_INDEXING_REPOSITORY_ID=<PagePulse repository's stored UUID>
REPOSITORY_INDEXING_COMMIT_SHA=<full 40-character GitHub commit SHA>
DATABASE_URL=jdbc:postgresql://localhost:5432/devpilot
DATABASE_USER=<database user>
DATABASE_PASSWORD=<database password>
ENCRYPTION_KEY=<original base64 32-byte key used to encrypt the restored GitHub token>
EMBEDDING_MODE=google
EMBEDDING_API_KEY=<embedding provider API key>
```

Use database UUIDs, not GitHub numeric IDs. The supplied owner is established as
the authenticated Spring Security user, and production ownership checks run.
The stored GitHub integration must belong to this owner and have a valid token
with access to PagePulse. There is no separate GitHub token environment variable:
the existing `IntegrationService` decrypts the restored credential using
`ENCRYPTION_KEY`. No JWT, webhook secret, or chat AI key is required.

Optional normal backend settings (use the same values as the backend):

```text
EMBEDDING_MODEL=gemini-embedding-2
EMBEDDING_DIMENSIONS=768
EMBEDDING_BASE_URL=https://generativelanguage.googleapis.com/v1beta
EMBEDDING_TIMEOUT_SECONDS=20
SOURCE_ACQUISITION_MAX_FILE_BYTES=524288
SOURCE_CHUNKING_MAX_CHARS=4000
SOURCE_CHUNKING_OVERLAP_CHARS=400
```

## IntelliJ IDEA run configuration

1. Ensure local PostgreSQL/pgvector is running, Flyway V1/V2 are already applied,
   and repository/user/GitHub integration data exists.
2. Open `src/test/java/com/devpilot/repository/RepositoryIndexingSmokeIT.java`.
3. Create a **JUnit** configuration with test kind **Class**, class
   `com.devpilot.repository.RepositoryIndexingSmokeIT`, and backend module classpath.
4. Select **Java 21** as the JRE and the backend directory as the working directory.
5. Enter the required environment variables above. Keep the configuration local
   and unshared. Do not activate the `test` Spring profile or SQL/HTTP debug logging.
6. Run once. It makes real GitHub and embedding calls and incurs embedding usage.
   Large repositories can take time; the number of embedding calls follows the
   number of generated chunks. Do not index the same revision concurrently.

With the environment configured and Maven using Java 21, the equivalent command is:

```text
mvn -Dtest=RepositoryIndexingSmokeIT test
```

The `*IT` name excludes it from normal `mvn test`. Even when explicitly selected,
it is skipped unless `RUN_REPOSITORY_INDEXING_SMOKE` is exactly `true`. To check
compilation and the gate without live calls, set it to `false` and run that command.

## Persistence and validation

This test commits real rows; it does not roll back. Indexing builds all chunks and
embeddings before atomically replacing rows for the exact repository and commit,
regardless of the previous embedding model. Other repositories/commits are not
replaced. Acquisition/embedding/transaction failures preserve the previous index.
A successful empty indexing result removes previous rows for that commit and
then fails this smoke test's nonempty check. A post-index verification failure
does not undo the committed indexing operation.

The smoke checks the result identity and positive counts, then queries aggregate
metadata for that exact repository/commit. Persisted count must match the result;
every row must have a nonblank path/text/model, nonnegative chunk index, 64-digit
hex hash, and a 768-dimensional vector. Distinct repository/commit/path/index
count must equal total count. Source contents and vector values are never selected
into the validation report or printed. Exceptions report only a fixed failure
stage; raw causes/messages are withheld.

Expected success output (values are illustrative):

```text
success=true commit=0123456789ab files=42 chunks=137
```

After success, use the same repository/owner/commit in `RepositoryChatSmokeIT`.
