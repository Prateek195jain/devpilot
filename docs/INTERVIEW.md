# DevPilot — implementation-focused interview preparation

## A concise project introduction

DevPilot is an engineering workflow platform I designed as a modular monolith with Spring Boot and PostgreSQL, backed by a React interface. It connects GitHub repositories, processes signed webhooks, queues code analysis, and converts findings into persistent engineering memory and technical debt. The important design work is the asynchronous boundary, ownership enforcement, refresh-token rotation and safe treatment of AI output. The implementation includes both an LLM adapter and explicitly limited local rules, so provider dependency and product behavior are separated.

Be precise about validation: frontend builds/client tests and pure Java checks were run in the constrained environment; the full Spring/PostgreSQL suite still needs execution in CI or a Docker-capable machine. Do not claim user traffic, measured scale or production incidents.

## A demo sequence

1. Register and inspect `/users/me` to explain DTOs and JWT subject/role.
2. Connect GitHub; explain that token encryption happens on the backend.
3. Import a repository and retrieve PRs/changed-file diffs.
4. Queue an analysis and explain QUEUED → RUNNING → COMPLETED.
5. Show finding source/confidence and promote one item to debt.
6. Re-review a related change and explain deterministic memory occurrence counts.
7. Ask a repository question; explain what evidence was and was not provided.
8. Generate two README versions and compare their separate records.
9. Demonstrate an attempted cross-user repository access returning 404.

## Architecture and tradeoffs

**Why a modular monolith instead of microservices?**
The workflows frequently need a single transaction, especially webhook-to-job insertion and findings-to-memory completion. One application makes these guarantees simple and avoids distributed deployment and cross-service authorization. Modules and provider interfaces create boundaries that can later be extracted when independent scaling is valuable.

**Why Java/Spring Boot?**
Spring provides a mature web/security/persistence model, declarative transaction boundaries, bean validation and test support. Constructor injection makes dependencies explicit. Java records are useful immutable DTOs, while JPA entities remain persistence objects.

**Why PostgreSQL rather than MongoDB?**
Ownership, references, token rotation, uniqueness and consistent updates are central. PostgreSQL can enforce foreign keys and uniqueness and provide row-level locks for work claiming. The queue and work artifacts share the database, making atomic transitions easier.

**What is the flow of a request?**
The browser calls a same-origin proxy. Spring Security validates JWTs before controllers. Controllers validate DTOs, enforce parent ownership and call domain services. Services own transactions; Spring Data stores handle queries; controllers return explicit DTOs. AI and GitHub adapters are injected behind interfaces.

**Where did you use SOLID or patterns?**
`AIProvider` and `SourceControlProvider` apply dependency inversion; OpenAI/local implementations use strategy and GitHub uses adapter. Controllers, context construction, orchestration and storage separate responsibilities. Spring selects providers through conditional dependency injection. Repository and DTO patterns keep persistence separate from transport. Builders are used where supplied by frameworks, such as JWT claims and WebClient. I did not add custom factories or responsibility chains without a real need.

**What would you extract first?**
The analysis worker has slower, more expensive workloads than interactive REST calls. I would preserve atomic job creation using an outbox, then route to independently scalable workers. GitHub ingestion, repository indexing and notifications can follow if demand supports them.

## Authentication and security

**How does JWT authentication work here?**
Login checks BCrypt and issues an HS256 JWT with issuer, subject, role, jti and expiry. Resource Server verifies the signature and claims. The authenticated principal name is the UUID subject. A converter maps role claims to Spring authorities. The UI keeps the access token in memory, not localStorage.

**Why refresh tokens, and how do you stop replay?**
A ten-minute access token reduces the lifetime of leaked credentials without requiring frequent login. Refresh tokens are random opaque values; only hashes are stored. Refresh locks the row and marks it revoked while creating a replacement in one transaction. A second request with the old value fails. The frontend uses single-flight refresh to avoid racing itself.

**Does logout invalidate all JWTs?**
No. It revokes the presented refresh token and clears the cookie. Existing JWTs can remain valid until expiry. Immediate invalidation would require a token denylist or a session-version lookup, adding state to every request. All-device logout and token-family compromise handling are future improvements.

**Why disable ordinary CSRF protection?**
Protected application APIs use a bearer token that another origin cannot attach automatically. Refresh/logout use cookies, so they enforce an exact Origin and SameSite=Strict. Production must use HTTPS and Secure cookies. This is a deliberate alternative boundary, not a blanket claim that JWT eliminates CSRF.

**How do you prevent IDOR?**
Every repository-scoped request first queries by repository ID and current owner ID. Nested resources are then queried by both child ID and that repository ID. A valid child UUID from another repository is insufficient. Non-owned parents return 404. Tests exercise multiple users across analyses, memory, debt, conversations and documents.

**Why encrypt GitHub tokens but hash passwords/refresh tokens?**
The application needs to recover GitHub tokens to call GitHub, so it uses authenticated AES-GCM encryption with a unique nonce. Passwords and refresh tokens only need equality verification, so irreversible hashing is appropriate. BCrypt is slow for human passwords; high-entropy refresh tokens can use SHA-256.

**How are roles granted?**
Registration always assigns USER. ADMIN is operator-provisioned, and the security chain protects `/admin/**`. Clients cannot choose role via registration. Existing access JWTs only reflect a role change after token refresh or a new login, which is part of the short-token tradeoff.

**How are secrets and errors protected?**
Secrets are environment variables. Token encryption and JWT configuration fail startup when invalid. Response DTOs omit hashes and ciphertext. Errors avoid stack traces and upstream bodies. Structured job logs include IDs/attempts and exception type, not prompts or provider responses. Common token patterns are redacted before LLM requests, but this is not comprehensive DLP.

## Webhooks, jobs and consistency

**How do you verify GitHub webhooks?**
Compute HMAC-SHA256 on the exact received bytes using the webhook secret, compare signature bytes in constant time, and only then parse JSON. Parsing/re-serializing before signing would change bytes and break validation. There is also a body-size bound and header validation.

**What if GitHub sends the same event twice?**
The delivery ID has a unique constraint. An already-seen ID with the same digest gets an accepted duplicate response with no new work. The event and queued analyses are committed together, so a crash cannot record receipt without scheduling the work. Concurrent unique-constraint failures are handled after rollback.

**Why not just use `@Async`?**
An in-memory task can disappear when the process dies. A persisted job can be retried, observed and recovered. The HTTP receiver returns after database work rather than waiting for GitHub/LLM latency.

**What happens when a worker crashes?**
A RUNNING row older than 15 minutes is eligible for requeue. Each claim increments an attempt. Completion compares the job's attempt and status with the current row; stale late workers cannot commit over a newer attempt. GitHub and LLM calls have bounded timeouts. This lease mechanism needs load/concurrency verification before scaling.

**How are retries bounded?**
Failures requeue below three attempts with increasing wait. After the third failed attempt the record becomes FAILED. A user can explicitly queue a new review. A rolling per-repository daily limit caps new analysis requests.

**Do you guarantee exactly-once analysis?**
No external network execution may repeat during retries or crashes. The design aims for idempotent database completion: a completed or superseded attempt cannot write again, and findings/memory/status commit together. This is more accurate than claiming global exactly-once semantics.

**How could deadlocks or contention arise?**
Repository locks serialize related writes; multiple busy jobs on one repository can contend. Current claim queries may serialize workers behind an eligible row. PostgreSQL `SKIP LOCKED`, consistent lock ordering and retry policies would improve throughput after testing. Transactions should never hold locks across LLM calls.

## AI, memory and context

**How do you prevent malformed model output from corrupting records?**
Require JSON, deserialize into typed records, validate types/severity/string bounds/nested findings/confidence and check referenced file/line locations against visible diff evidence. Bad output fails the job and does not partially persist findings. Database completion is transactional.

**Can you guarantee the model's findings are correct?**
No. Structural validity and matching source locations do not prove semantic correctness. The product includes confidence and provider labels, presents recommendations for review, and uses explicit promotion into debt. A human still validates the finding.

**How is prompt injection handled?**
The system prompt identifies source code, diffs and previous text as untrusted evidence. The model has no tool execution path. Output is validated and rendered as text. This limits consequences but does not fully eliminate prompt influence; provider behavior still needs evaluation.

**How does engineering memory work?**
Normalize the finding title and hash it with issue type and file. The repository plus fingerprint is unique. Each completed analysis contributes at most one occurrence per fingerprint. Frequent ACTIVE patterns become context for the next review. Archived records are retained but excluded from active context.

**What are the weaknesses of that fingerprint?**
Different wording splits equivalent findings; identical wording can merge distinct issues in one file. Repeated reviews of the same immutable commit can count again. Future improvements include stable rule identifiers, location-aware grouping and semantic similarity with conservative merge rules.

**How is chat repository-aware?**
It receives the repository metadata/README, recent findings, frequent active memories, latest analyzed diff and recent conversation turns. Retrieval is bounded by record counts and characters. It does not have arbitrary source browsing, so it must admit when the authentication flow or schema has not been observed.

**Why no embeddings yet?**
A bounded evidence set supports the initial workflow without an index lifecycle, chunking, embedding model cost or retrieval evaluations. Whole-repository question answering would justify that added system once the product needs it.

## Debt, docs and concurrency

**How do you prevent duplicate debt from one finding?**
Check under a repository lock and enforce a database unique key on repository/source finding. Repeated promotion returns the existing record. A finding from another repository fails the scoped lookup.

**How are status timestamps handled?**
Resolving sets resolvedAt. Reopening or ignoring clears it. updatedAt changes for status/effort updates. These are domain transitions rather than arbitrary CRUD field writes.

**What if two messages are sent at once?**
Both might generate a remote answer, but only one can commit against the original conversation version. The final transaction locks the conversation, checks its version and updates its timestamp; a stale competing send returns 409. This prevents silently interleaving answers generated from inconsistent history, but it can waste one AI call.

**How do document versions avoid races?**
Generate outside a transaction, then lock the repository, read the latest kind/version and insert the next version. A unique database constraint provides a final safeguard. Existing versions stay immutable.

## Testing and honest scope

**What did you actually test?**
Frontend typing/builds, refresh retry/single-flight/error handling, executable deterministic rule checks and webhook cryptographic verification, plus syntax parsing of Java sources. Spring MockMvc and PostgreSQL Testcontainers tests are written but were not executable in the constrained environment. State this distinction explicitly.

**What would you validate next?**
Run `mvn verify` with Java 21 and Docker, validate Flyway against PostgreSQL, exercise real GitHub token permissions and multi-page/binary diffs, test provider JSON failures, perform two-user browser flows, and load-test concurrent refresh/job/memory/document writes. Verify Compose startup and HTTPS cookie/origin behavior before deployment.

**What would you improve before real customer traffic?**
Per-user AI budgets, token-aware context limits, edge throttling, refresh-family compromise handling, webhook secret rotation per integration, retention/backup policies, metrics/alerts, GitHub App support, semantic memory evaluation and full-source retrieval.

**What should you avoid claiming?**
Do not claim semantic analysis in LOCAL_RULES mode, complete code understanding from bounded snippets, immediate JWT revocation on logout, exactly-once upstream calls, proven horizontal scaling, or a production-ready release without completing the blocked runtime gates.
