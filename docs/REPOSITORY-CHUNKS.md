# Repository chunk persistence

`RepositoryChunk` / `RepositoryChunkStore` follow the existing feature packages,
UUID repository references, `*Store` Spring Data interfaces, and `BaseEntity`
(UUID ID, creation time, optimistic-lock version).

V2 adds `repository_chunks`, linked by a foreign key to `repositories.id`.
There is no persistent source snapshot entity: `SourceControlProvider.Snapshot`
is a transient change/diff DTO and `Analysis` is a review job. A mandatory immutable
`commit_sha` (lowercase 40- or 64-character Git object ID) scopes chunks by revision.
The unique position is `(repository_id, commit_sha, source_path, chunk_index)`.
Its B-tree index also supports repository/revision filtering and file/chunk ordering.
Repository deletion remains restricted while chunks exist, matching existing FKs.

Each row stores a source path (up to 500 characters, consistent with findings),
zero-based chunk order, text content, lowercase SHA-256 content hash, embedding
model identifier, and a required `vector(768)`. The future indexing writer must
hash the exact UTF-8 content stored and supply the model used; persistence validates
the hash format but does not compute it. Model provenance avoids silently mixing
embedding models. pgvector stores float32 values, so callers must convert provider
doubles to floats and validate that the converted values remain finite.

Entity callbacks validate metadata, dimension count, and finite values for inserts
and updates. PostgreSQL separately enforces required fields, foreign key, uniqueness,
nonnegative order, hash/revision formats, nonempty text, and vector size/finite values.
No file enumeration, chunking, provider calls, similarity queries, or vector indexes
are added here. The store's paginated query is ordinary persistence lookup, not
semantic retrieval; future services must use `RepositoryAccess` before accessing it.

## PostgreSQL setup

The Hibernate vector module uses the same `${hibernate.version}` as Spring Boot's
Hibernate core. No Spring datasource or AI settings change.

The PostgreSQL server must have pgvector installed before Flyway runs V2.
Compose and PostgreSQL Testcontainers use `pgvector/pgvector:0.8.6-pg16`, maintaining
the existing PostgreSQL 16 major version and data directory. No container or database
is recreated by this code change. For an existing database, back up normally and
install the extension package or use a compatible pgvector-enabled server image;
do not delete the data volume. For IntelliJ using a native PostgreSQL installation,
install pgvector built for that server's exact PostgreSQL major version. Changing
Compose alone does not add the extension to native PostgreSQL.

V2 runs `CREATE EXTENSION IF NOT EXISTS vector`. If the migration role cannot
create extensions, an administrator must enable `vector` in the target database
before application startup. Flyway otherwise runs normally with JPA schema
validation. Do not edit V1 or disable validation to bypass a missing extension.

## Verification

From `backend`:

```text
mvn -B "-Dtest=RepositoryChunkPersistenceTest,RepositoryChunkPostgresTest,PostgresSchemaTest,ApiIntegrationTest" test
```

H2 checks entity/store round trips, pagination, revision/repository isolation,
duplicate positions, and insert/update validation. Its test-only `H2VectorDialect`
maps vector fields to float arrays; it does not emulate pgvector. Flyway stays disabled
in H2 tests as before. PostgreSQL tests apply V1+V2, validate JPA mappings, run the
same persistence contract, and bypass entity validation to check database constraints.
They require Docker and skip without it; a skipped test is not PostgreSQL verification.
The real embedding smoke test and provider are unchanged and are not invoked here.

Next: define authorized source acquisition at an immutable commit, with file
allow/deny rules and size limits, before implementing deterministic chunking and
an indexing writer. No repository is indexed by this persistence milestone.
