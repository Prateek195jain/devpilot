# Repository semantic retrieval

`RepositoryRetrievalService.retrieve(repositoryId, commitSha, query, limit)` requires
the authenticated repository owner and a full 40-character GitHub commit SHA.
Uppercase SHAs are normalized to lowercase, matching indexing. The query must be
nonblank and the caller must supply a positive limit. `app.retrieval.max-limit`
(`RETRIEVAL_MAX_LIMIT`) defaults to 50 and accepts 1 through 1000. Requests above
the configured maximum are capped; there is no implicit requested limit.

The existing embedding provider embeds the redacted query outside a transaction.
Shared indexing/retrieval validation checks the configured model, response model,
768 dimensions, value count, finite doubles, and finite float conversion.
Retrieval also rejects vectors that become zero after conversion. Provider failures
propagate as the existing sanitized `EmbeddingFailure`, including retry metadata;
invalid input and denied ownership use `Problem`. No partial result is returned.

`RepositoryChunkStore.search` is implemented by a Spring Data fragment using
parameterized JDBC SQL. PostgreSQL filters repository ID, exact commit, and exact
provider model, and checks current ownership again in the same statement. It
returns only metadata and content, never vectors. An absent scope returns an
immutable empty list, with no commit or model fallback. A lost ownership check
at search time also returns no content.

The pgvector `<=>` operator measures cosine distance (lower is better). This
compares direction without requiring the provider to normalize vector magnitude.
Results sort by distance ascending, then source path using PostgreSQL `C`
collation, then chunk index. Stored zero vectors are excluded because cosine
distance is undefined for them. Results are immutable records in an immutable list.
Line positions are unavailable in the persisted chunk representation.

Search is exact, using the existing repository/commit B-tree prefix to restrict
candidates. No approximate vector index is introduced, preserving exact top-k
and stable ties. Cost grows with the number of chunks in the selected scope.
Operator reference: https://github.com/pgvector/pgvector#querying

Service tests use a mocked provider and store. `RepositoryChunkPostgresTest`
uses synthetic 768-dimensional vectors and the existing pgvector Testcontainers
image to verify SQL ranking, ties, ownership, empty scopes, and repository/commit/
model isolation. It skips cleanly when Docker is unavailable. H2 tests do not
emulate the similarity operator. Run `mvn test` for the regression suite; no real
embedding calls are made.

## Repository context construction

`RepositoryContextBuilder.build(repositoryId, commitSha, question)` delegates to
authorized retrieval once, requesting 10 matches (subject to retrieval's cap).
It preserves retrieval order, checks returned scope, and returns an immutable
`RepositoryContext` with repository ID, normalized commit, matches considered,
chunks included, exact character count, and text. Scope mismatches fail closed;
retrieval failures propagate. Empty retrieval produces empty text with zero counts.
There is no alternate commit lookup.

`REPOSITORY_CONTEXT_MAX_CHARS` (`app.repository-context.max-chars`) defaults to
24,000 and must be positive. Counts are UTF-16 code units, including complete
headers and two-LF separators. This fits roughly six default 4,000-character
chunks and leaves room relative to the existing 35,000-character context cap.
It is not a token or total AI request limit: the chat prompt builder separately
budgets combined context, question (at most 4,000 characters), and up to 12 history
turns. The provider's 5,000-token setting limits output, not input.

Blocks use `[Source: path | chunk=index]` followed by LF and source text.
Header control characters, backslashes, and delimiters are Unicode-escaped.
Line ranges are not persisted, so none are fabricated. Scores are unnecessary
for this result; retrieval already orders by cosine distance.

Duplicate file/chunk identities are omitted after inclusion. For adjacent indices
in the same file, the longest exact whole-line suffix/prefix overlap containing
at least two nonblank lines is removed from the lower-ranked chunk. This works
in either source order and only compares text actually retained from included
chunks. Without line coordinates this is deliberately conservative: single-line,
partial-line, nonadjacent, and cross-file repetitions remain. Identical repeated
source sequences cannot always be distinguished from chunker overlap.

Overlap is compared before redaction. Emitted source and paths use the existing
`SecretRedactor`, matching embedding and outbound AI behavior; recognized token
values change to placeholders, while other source whitespace and text remain
exact. This is not a comprehensive secret scanner. Budgeting happens after
redaction and header escaping. Blocks that do not fit are skipped whole, and
later smaller matches may still fit; no source or header is truncated. A single
oversized chunk can therefore produce empty context even with nonzero matches
considered. Skipped chunks never suppress later overlap.

Run `mvn -Dtest=RepositoryContextBuilderTest test` for mocked retrieval unit tests;
they need neither Docker nor an embedding provider.

## Chat integration

`POST /api/v1/repositories/{repo}/conversations/{id}/messages` accepts
`{"content":"Explain authentication","commitSha":"<full 40-character SHA>"}`.
`commitSha` is optional for backward compatibility, but if supplied must be a
full hexadecimal SHA. It selects source evidence for this turn only; neither
the conversation nor messages gain a persisted commit field. The repository
comes from the URL and must match the conversation. Service-level ownership
checks precede history/context reads, in addition to existing controller and
retrieval authorization. Chat calls only `RepositoryContextBuilder`.

Without a commit, chat uses the existing stored repository context. Empty source
context uses that same background with an explicit warning that it is not
evidence for the requested commit. Failures, including denied access and embedding
errors, propagate rather than silently degrading to fallback. No other commit
is searched. History can describe other revisions and is labeled as history;
system instructions explicitly prohibit treating it as proof of current source.
There is no separate repository-free chat endpoint; direct provider calls with
empty context continue to work.

`ChatPrompt` enforces a fixed 35,000 UTF-16-character combined system/user message
content budget for outbound chat, including all section labels, separators and
redaction replacements. This is a text budget, not a serialized JSON byte limit
or token estimate. The existing 35,000-character context limit alone did not
bound history. The complete current question (at most 4,000 characters, except
recognized secrets are redacted) and system instructions are reserved first.
Context is allocated next, then newest whole history turns, emitted oldest to
newest. Selection stops when the next older turn will not fit, retaining a
contiguous recent suffix. Default 24,000-character repository context fits in
full even with a maximum-size question. If a customized context exceeds the
remaining budget, only the prefix ending at a repository block separator is
retained; a single oversized block is omitted. Legacy prose context is cut with
an explicit truncation marker. No partial source header is emitted.

System instructions occupy their own message; repository context, conversation
history, and current question occupy labeled sections in the user message.
The existing `SecretRedactor` is reused at prompt construction, replacing the
previous outbound chat redaction call rather than adding another scanner.
Analyze/document request construction and local evidence-mode behavior remain
unchanged. Source evidence is not inserted into persisted user turns or history;
only the question and generated response are persisted as before.

Focused tests: `mvn -Dtest=ChatPromptTest,ConversationServiceTest,GeminiAIProviderTest,RepositoryContextBuilderTest test`.
The provider tests capture requests through a local fake HTTP server, with no
live AI or embedding calls. Frontend commit selection and citation UI remain
future work.
