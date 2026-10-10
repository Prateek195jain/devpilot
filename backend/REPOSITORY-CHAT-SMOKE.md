# Manual repository chat validation

`RepositoryChatSmokeIT` is excluded by Maven's normal `*Test` discovery and also
requires `RUN_REPOSITORY_CHAT_SMOKE=true`. It uses real PostgreSQL data, the real
embedding provider, and the configured real AI provider. Expect four embedding
requests and four AI requests on success, with provider charges. It performs no
indexing, GitHub acquisition, schema creation, migrations, or worker startup.

Use an existing indexed repository and immutable commit, with the same embedding
model that indexed its chunks. Supply the stored owner's user UUID and an existing
conversation belonging to that repository. These are database UUIDs, not GitHub
IDs. The harness establishes that user's Spring Security context and executes
normal ownership checks; it does not exercise HTTP login or JWT verification.

Use a dedicated validation conversation: the real `ConversationService.send`
persists a user/assistant pair for each successful question. These writes are not
rolled back; earlier successful turns remain if a later question fails. Do not
send concurrently into this conversation. Database credentials need the same
read/write access as normal chat. No repository data or embeddings are changed.

## Required environment variables

```text
RUN_REPOSITORY_CHAT_SMOKE=true
REPOSITORY_CHAT_USER_ID=<existing repository owner's UUID>
REPOSITORY_CHAT_REPOSITORY_ID=<existing repository UUID>
REPOSITORY_CHAT_CONVERSATION_ID=<existing conversation UUID in that repository>
REPOSITORY_CHAT_COMMIT_SHA=<already indexed full 40-character commit SHA>
DATABASE_URL=jdbc:postgresql://localhost:5432/devpilot
DATABASE_USER=<database user>
DATABASE_PASSWORD=<database password>
EMBEDDING_MODE=google
EMBEDDING_API_KEY=<embedding provider key>
AI_MODE=gemini
AI_API_KEY=<AI provider key>
```

`AI_MODE=openai` is also supported. Local AI mode is intentionally rejected.
Optional existing backend settings: `EMBEDDING_MODEL` (default
`gemini-embedding-2`, must match the stored index), `EMBEDDING_DIMENSIONS` (768),
`EMBEDDING_BASE_URL`, `EMBEDDING_TIMEOUT_SECONDS`, `AI_BASE_URL`, `AI_MODEL`,
`RETRIEVAL_MAX_LIMIT`, and `REPOSITORY_CONTEXT_MAX_CHARS` (default 24000).
Use your backend's provider settings. No JWT, GitHub token, webhook secret, or
encryption key is needed by this restricted application context.

## IntelliJ IDEA

1. Open `src/test/java/com/devpilot/repository/RepositoryChatSmokeIT.java`.
2. Create a JUnit run configuration for class
   `com.devpilot.repository.RepositoryChatSmokeIT` using the backend module.
3. Set JRE to Java 21 and working directory to the backend project directory.
4. Set the required environment variables above in that configuration. Keep the
   configuration local/unshared; do not commit credentials. Do not enable the
   `test` Spring profile or SQL/HTTP debug logging.
5. Start the existing PostgreSQL database and run the configuration. No Docker
   test container is created; the database must already contain the index.

Alternatively, with those variables already set and Maven using Java 21:

```text
mvn -Dtest=RepositoryChatSmokeIT test
```

Without the gate, explicitly selecting the test results in one skipped test and
no application startup or external calls. Normal `mvn test` does not discover it.

## Output and review

Successful output has four lines like these (counts vary):

```text
question=1 success=true sources=3 answerChars=842
question=2 success=true sources=4 answerChars=1106
question=3 success=true sources=5 answerChars=1392
question=4 success=true sources=3 answerChars=917
```

`sources` counts distinct source/chunk headers in the actual generated context,
not unique files. Automatic checks validate scope, nonempty source context,
source headers, successful answer generation, assistant role, and conversation
identity. An empty context fails before calling AI, avoiding a misleading success
from legacy fallback. Assertions never depend on answer wording. Failure output
contains only a fixed stage label; exception causes and messages are withheld.

For human review, debug the test and pause at the success `printf` line. Inspect
`answer.content()` and `context.text()` locally, or inspect the saved conversation
in your existing local application. Do not export these values into test logs.
Check each answer against actual files at the chosen commit: purpose accuracy,
authentication location (or acknowledged absence), backend structure, database
access, useful source references, and separation of verified facts from guesses.
Passing structural checks alone does not establish answer quality.
