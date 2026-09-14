# Validation record

Date: 2026-09-11. This separates source implementation from observed execution.

## Passed in the build workspace

| Check | Observed result |
|---|---|
| TypeScript strict checking | Passed for the connected frontend and proxy |
| Sites/Vinext production build | Passed, including API proxy and workspace routes |
| Next.js Node production build | Passed with webpack and standalone output for the Docker frontend |
| Built frontend route/proxy smoke test | Passed for `/`, `/login`, `/workspace/dashboard`, and the controlled 502 response when the Java backend is unavailable |
| Frontend lint | Passed with no errors or warnings |
| Frontend API-client behavior tests | 6 passed: in-memory bearer handling, one refresh retry, concurrent single-flight rotation, field errors, network vs signed-out distinction, logout token cleanup |
| Executable Java core checks | 14 passed against actual LocalRules, WebhookSignature, DiffEvidence and SecretRedactor classes |
| Java source syntax parsing | All 76 Java main/test sources parsed without syntax errors using the available Java compiler API |
| XML/schema consistency | POM XML parsed; all 12 entity column sets match the initial Flyway migration |
| Source formatting | Java lexical tokens verified unchanged while expanding compressed statement/block formatting |

The Java core checks cover added vs removed risky code, new-line locations, coverage warnings, swallowed exceptions, logging, possible credentials, valid/tampered/missing/malformed webhook signatures, invented finding locations and token redaction. They use the locally available Java 17 runtime/compiler API; the full application targets Java 21.

## Not executed / not verified

- Maven compilation and the complete Spring Boot/JUnit/MockMvc suite. Maven was absent, Java 21 was absent, and dependency network requests did not complete under the environment's access restrictions.
- PostgreSQL migration execution and Hibernate runtime schema validation. No PostgreSQL/Docker runtime was available.
- Docker Compose startup or backend Docker image build. A frontend Node build alone does not validate the Compose stack.
- Live GitHub token integration, signature delivery from GitHub, real PR/commit retrieval, and live OpenAI-compatible provider responses. No credentials were supplied.
- Multi-user browser journey, responsive visual inspection, browser WebMCP registration, concurrency/load/recovery fault injection. No browser QA session was requested or run.
- The hosted frontend is not an end-to-end deployment until BACKEND_URL points at an operational Java backend with matching origin/cookie settings.

## Written verification gates

- `CoreBehaviorTest`: JUnit coverage for rules, AES-GCM authentication and HMAC behavior.
- `ApiIntegrationTest`: registration/auth security, refresh rotation/replay, Origin enforcement/logout, cross-owner isolation, role protection, analysis completion idempotency, memory, debt transitions, webhook deduplication, conversation persistence and document versions.
- `PostgresSchemaTest`: Testcontainers PostgreSQL migration/startup/JPA validation; skips explicitly when Docker is unavailable.
- `.github/workflows/verify.yml`: Java 21 `mvn verify` and frontend type/client/build jobs.
- Backend Docker build runs Maven verification before packaging the runtime image.

## Required next verification on a capable runtime

Run `python3 scripts/setup-env.py`, then `docker compose up --build`. Resolve any backend compile/runtime failures before relying on the app. In a Java 21 + Docker environment run `cd backend && mvn verify` so the PostgreSQL test executes. Then validate two-user ownership isolation in the browser, real GitHub retrieval/webhooks, external provider failures and HTTPS refresh-cookie behavior.

This is an implementation handoff with explicit unverified gates, not evidence of a production deployment or a claim that every requirement was runtime-tested.
