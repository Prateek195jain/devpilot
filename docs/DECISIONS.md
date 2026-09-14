# Implementation decisions and assumptions

## ADR 001 — Build new source because none was supplied

Inspection found the requirements attachment and no existing code in the workspace. A title search also did not locate a supplied DevPilot source artifact. No external GitHub repository was assumed or modified. The new source tree is the deliverable; it does not assert continuity with an unseen implementation.

## ADR 002 — Modular monolith with database-backed jobs

A single Spring deployable keeps transactions, local development and ownership rules simple. PostgreSQL jobs avoid losing in-flight work on restart. No Redis/Kafka/RabbitMQ is required for the MVP. Future workers can use an outbox/broker after scale warrants it.

## ADR 003 — Explicit token integration, no OAuth setup dependency

A fine-grained GitHub PAT is verified before encryption. This is a usable MVP without requiring an externally configured OAuth application. Token rotation and webhook setup remain explicit operator/user actions. An eventual GitHub App is preferable for scoped organization installs and installation-token rotation.

## ADR 004 — BCrypt, short access tokens and rotating refresh cookies

Access tokens stay only in browser memory. Refresh cookies are HttpOnly and SameSite=Strict with exact Origin checks. HS256 uses a strong operator-supplied secret. Logout revokes refresh access immediately but an existing access token has a bounded ten-minute lifetime; no denylist is introduced prematurely.

## ADR 005 — All business authorization remains in Java

The frontend's same-origin proxy forwards credentials to Spring. It does not enforce ownership itself and does not expose an open proxy destination. Child access requires both parent ownership and child-to-parent membership. Role privilege comes from issued JWTs, never user-submitted registration data.

## ADR 006 — Bounded source context with explicit uncertainty

No blanket repository upload or vector database is implemented. The context builder uses available README, findings, memory and latest diff. This makes cost and data sharing inspectable but limits source-navigation answers. Unsupported architecture/schema/API sections are marked unverified.

## ADR 007 — Pluggable AI with a useful no-key mode

LOCAL_RULES performs actual deterministic diff checks and labels its limitations. OPENAI uses validated structured responses. There is no fake provider response presented as live AI. The default model string is configurable; live compatibility and account access have not been verified here.

## ADR 008 — Deterministic memory and explicit debt promotion

Fingerprints use issue type, file and normalized title; this is efficient but sensitive to wording changes. Memory is updated atomically with analysis completion. Debt promotion is user-directed so speculative model findings do not automatically flood a work queue.

## ADR 009 — React frontend using the available starter

The requested React/TypeScript/Tailwind stack is retained. Next routing and Fetch replace React Router/Axios because the workspace's installed Sites starter provides the former and dependencies for the latter were not available offline. The network client and hooks isolate this choice. Docker builds standard Next; the optional Sites frontend builds with Vinext. PostgreSQL/JVM execution stays outside Sites.

## ADR 010 — Validation boundary is explicit

The execution image had Java 17, no Maven/Docker/PostgreSQL, and blocked dependency downloads. Java syntax parsing and executable pure-Java components were tested, but full backend verification is not claimed. A Maven/JUnit/MockMvc suite and PostgreSQL Testcontainers schema test are committed; CI and Docker commands make the missing checks reproducible.

## ADR 011 — No horizontal complexity before evidence

One scheduled worker per instance, short database transactions, bounded external timeouts and three attempts are sufficient initially. The queue's concurrency design is not load-tested. Provider budgets, token-aware context retrieval, retention, index tuning and worker sharding are future work driven by measured demand.

## ADR 012 — Keep external actions narrowly scoped

No GitHub reviews, PR comments, code edits, or repository webhooks are created remotely by the app. GitHub integration is read-only; webhook setup is shown as an explicit instruction. No credentials are seeded or committed. Generated documents require human review before publication.
