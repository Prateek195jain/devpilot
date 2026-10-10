# Opt-in embedding smoke test

`EmbeddingSmokeIT` directly constructs the actual `GoogleEmbeddingProvider` through
the `EmbeddingProvider` interface. It sends one query containing
`A Java service validates login credentials.` using the provider's query formatting,
with no retries. It checks exactly 768 finite values and matching metadata.
It does not start Spring, access a database, or index repositories.

The test's report contains only `success`, `model`, and `dimensions`.
It never prints the credential, response vector, or underlying exception.
A failed check fails the test with a generic message. On failure, the reported
model and dimensions describe the requested configuration, not a successful response.

## Run in IntelliJ

1. Open `backend/src/test/java/com/devpilot/ai/EmbeddingSmokeIT.java`.
2. Use the gutter beside the class to create its JUnit run configuration
   (choose **Modify Run Configuration**, if offered). Otherwise use
   **Run > Edit Configurations > + > JUnit**.
3. Name it `Embedding smoke (live)`, select **Test kind: Class**, set **Class** to
   `com.devpilot.ai.EmbeddingSmokeIT`, and choose the backend `devpilot` module
   for **Use classpath of module**. Use the project's Java 21 JDK.
4. In **Environment variables** (under **Modify options** if hidden), copy the
   `EMBEDDING_*` entries from your working backend run configuration. These entries
   are specific to each run configuration and are not automatically inherited from
   the backend configuration. Add `RUN_EMBEDDING_SMOKE=true`:

   ```text
   RUN_EMBEDDING_SMOKE=true
   EMBEDDING_MODE=google
   EMBEDDING_API_KEY=<same key as your backend configuration>
   EMBEDDING_MODEL=gemini-embedding-2
   EMBEDDING_DIMENSIONS=768
   EMBEDDING_BASE_URL=https://generativelanguage.googleapis.com/v1beta
   EMBEDDING_TIMEOUT_SECONDS=20
   ```

   Keep this configuration local/unshared. If your backend configuration loads an
   environment file, configure the same file loading for this JUnit configuration.
   The test reads process environment variables; it does not load `.env` itself.
   Leave `AI_MODEL` unchanged; this test does not read it.
5. Apply and run **Embedding smoke (live)** once. A passing test reports:

   ```text
   success=true model=gemini-embedding-2 dimensions=768
   ```

Maven's normal Surefire discovery does not include `*IT` classes. IntelliJ's
broader discovery skips this test unless `RUN_EMBEDDING_SMOKE=true`. Keep that
variable only in the dedicated smoke configuration.

For an explicit Maven run from `backend`, with these variables already present
in that process's environment:

```text
mvn -Dtest=EmbeddingSmokeIT test
```

Without the opt-in variable, the explicitly selected test is skipped and makes
no request. Maven and IntelliJ also display their usual test-runner status output.
