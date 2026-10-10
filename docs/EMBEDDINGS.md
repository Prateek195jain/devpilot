# Google embedding provider

The backend embedding interface is independent of `AIProvider` and every `AI_*`
generation setting. No source index or database persistence is added in this phase.

Set these environment variables in the IntelliJ backend run configuration:

```text
EMBEDDING_MODE=google
EMBEDDING_API_KEY=<your Google API key>
EMBEDDING_MODEL=gemini-embedding-2
EMBEDDING_DIMENSIONS=768
EMBEDDING_BASE_URL=https://generativelanguage.googleapis.com/v1beta
EMBEDDING_TIMEOUT_SECONDS=20
```

Keep the key in backend environment variables. Do not put it into tracked files,
frontend variables, URLs, or logs. Leave `AI_MODEL` and the root `.env` unchanged.
The default embedding mode is disabled. Enabling Google creates the provider
without contacting Google. Missing credentials cause a safe failure when used;
they do not block startup readiness. No health contributor is registered.

Each call embeds exactly one document or query using `embedContent`. For
`gemini-embedding-2`, Google documents prompt instructions rather than `taskType`:

- Documents: `title: {title} | text: {content}` (missing title becomes `none`).
- Code queries: `task: code retrieval | query: {query}`.

Reference: https://ai.google.dev/gemini-api/docs/embeddings#task-types-with-embeddings-2

The response must have a single `embedding.values` array of the configured length,
numeric finite values, and a finite nonzero norm. Values are returned immutably
with model/dimension metadata. Google normalizes this model's reduced-dimensional
vectors; the provider does not silently truncate or renormalize responses.

The total call deadline defaults to 20 seconds (configurable 1–60 seconds), with
connection establishment capped at 5 seconds and responses capped at 128 KiB.
Redirects are disabled. Inputs above 24,000 characters are rejected, never sliced.
This payload bound is not a tokenizer guarantee: future chunking must respect
Google's token limit. Callers must authorize repository access and sanitize source
before invoking this low-level provider. Existing token redaction is an additional
defense, not a complete secret scanner.

`EmbeddingFailure` exposes safe categories and retryability. Authentication and
invalid requests/responses are not automatically retried. Rate limits and temporary
failures are retryable by a future durable worker; `Retry-After` supports seconds
and HTTP dates, bounded to one day. Calls do not retry internally. Remote bodies,
keys, input text, and nested transport exceptions are not retained in failures.

Hosted embeddings may incur charges. Confirm the project's current free-tier
eligibility, rate limits, and pricing before bulk indexing:
https://ai.google.dev/gemini-api/docs/pricing

Provider-contract tests use a local HTTP stub, synthetic credentials, and no database:

```text
mvn -B -Dtest=GoogleEmbeddingProviderTest,GeminiAIProviderTest,CoreBehaviorTest test
```
