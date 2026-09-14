# DevPilot

An engineering workflow platform built with Java 21, Spring Boot, PostgreSQL, React, TypeScript, and Tailwind CSS. Connect GitHub, queue a change review, retain findings as engineering memory, track debt, and discuss or document repository evidence.

## Implementation status

This workspace contained only the supplied requirements, with no pre-existing repository. This implementation was created from that brief. It is a substantial MVP implementation, **not a verified production release**. See [docs/VALIDATION.md](docs/VALIDATION.md) for checks actually run and environmental blockers.

- Registration/login, BCrypt, ten-minute JWT access tokens, seven-day rotating refresh cookies, logout, USER/ADMIN authorization, ownership isolation.
- Encrypted GitHub token integration; paged repository import, metadata/README sync, branches, commits, PRs, changed files and diffs.
- HMAC-SHA256 webhook verification, persistent delivery deduplication, atomic job enqueue.
- Persistent analysis queue with bounded retries, stale-worker recovery, completion fencing, and per-repository daily limits.
- Structured AI provider interface; configurable OpenAI-compatible adapter and explicitly labelled deterministic local rules.
- Findings with evidence, confidence, recommendations and source labels. Persistent recurring memory feeds future context.
- Finding-to-debt promotion, manual debt, status transitions, effort, severity/category statistics.
- Persisted repository conversations, bounded context, message history and concurrent-send protection.
- Eight documentation kinds with immutable version history and Markdown download.
- Connected React interface with authentication, repository selection, every workflow above, pagination, loading/empty/error states and responsive navigation.
- Flyway schema, Actuator health, correlation IDs, structured logs, Docker Compose and CI verification workflow.

## Quick start with Docker

Prerequisites: Docker with Compose, Python 3, internet access for images/dependencies, and a GitHub personal access token for repository operations.

```sh
python3 scripts/setup-env.py
docker compose up --build
```

Open **http://localhost:5173**. The first build downloads dependencies and runs the backend tests. PostgreSQL persists in the `postgres-data` volume.

1. Create an account (minimum 12-character password).
2. In **Settings**, connect a fine-grained GitHub token with read access to Metadata, Contents, and Pull requests for the desired repositories.
3. In **Repositories**, import `owner/repository` or select one from the GitHub list.
4. Open the repository, choose a PR or commit, and click **Analyze**.
5. Follow **Code analysis**, inspect **Findings**, and choose **Track as debt**.
6. Explore **Engineering memory**, **Repository chat**, and **Documentation**.

Local rules work without an LLM key, but GitHub access is still required to retrieve actual repository changes. No accounts, secrets, or example findings are seeded.

The `.env` generator creates unique secrets, refuses to overwrite existing values, and never prints secrets. On Windows use `python scripts/setup-env.py` if that is your Python command.

## Enable LLM analysis

Edit the generated `.env`:

```dotenv
AI_MODE=openai
AI_API_KEY=your-provider-key
AI_MODEL=your-enabled-model
AI_BASE_URL=https://api.openai.com/v1
```

Then recreate the backend: `docker compose up -d --force-recreate backend`. A compatible Chat Completions endpoint supporting JSON object output is required. Model availability is account-dependent. This implementation has **not** been exercised against a live LLM account.

AI mode transmits bounded repository code/context to the configured provider. Token-pattern redaction reduces accidental disclosure; it is not a complete secret scanner. Review the repository's data-sharing requirements before enabling it.

## Run services directly

Use Java **21** and Maven **3.9+** for the backend. Start a PostgreSQL 16 database, set the values in `.env` in your process environment, then:

```sh
cd backend
mvn verify
mvn spring-boot:run
```

For the React frontend, from the repository root with Node 22 and pnpm 11.25.0:

```sh
corepack enable
corepack prepare pnpm@11.25.0 --activate
pnpm install --frozen-lockfile
pnpm dev:docker
```

Set `BACKEND_URL=http://localhost:8080`. Next reads root `.env`; Maven does not, so export variables explicitly using your shell or IDE. Set `DATABASE_URL=jdbc:postgresql://localhost:5432/devpilot` for a directly started backend. The Compose database does not expose a host port by default; use your own local PostgreSQL or an explicit local port override.

The frontend is a React client with a same-origin API proxy. Docker uses the supported Next.js runtime; the optional private Sites preview uses the compatible Vinext build. React Router and Axios from the requested brief were replaced by the existing starter's Next router and a tested Fetch service layer, avoiding additional uncached dependencies. All business state lives in Spring/PostgreSQL.

## Repository structure

```text
app/                         React routes and same-origin backend proxy
components/devpilot/         Product screens, reusable view helpers, data hooks
components/ui/               Existing accessible Shadcn primitives
lib/devpilot-api.ts          API client, refresh single-flight, API DTO types
backend/src/main/java/com/devpilot/
  auth/ user/ common/        Identity and security
  repository/ github/       Ownership and source-control adapter
  webhook/ analysis/ ai/     Receiving, durable work and AI strategies
  memory/ technicaldebt/     Persistent lessons and actionable work
  conversation/ documentation/
backend/src/main/resources/db/migration/
backend/src/test/             Spring, security and PostgreSQL test suites
tests/                       Executable frontend client tests
docs/                        Architecture, API, decisions, interview notes
```

## Verification

```sh
pnpm typecheck
pnpm lint
pnpm test:client
pnpm build:docker
bash scripts/smoke-frontend.sh
cd backend
mvn verify
sh scripts/test-core.sh
```

`mvn verify` runs JUnit/MockMvc tests against H2 in PostgreSQL mode, plus a Testcontainers PostgreSQL Flyway/JPA test when Docker is available. The CI workflow has Docker and is the intended full verification gate. The Docker backend image runs tests during its build; Testcontainers skips if a Docker daemon is unavailable inside that build.

Health: `GET http://localhost:8080/actuator/health`. No credentials or detailed database health information are exposed. JWT-protected admin counters: `GET /api/v1/admin/stats`. Accounts register as USER; operator-controlled database provisioning is required to grant ADMIN, followed by a fresh login. No role escalation endpoint exists.

## Webhooks and deployment

GitHub must reach `/api/v1/webhooks/github` over public HTTPS. Configure `application/json`, the generated `WEBHOOK_SECRET`, and Push/Pull request events in GitHub. The Compose defaults bind both app ports to localhost; place an HTTPS reverse proxy in front for external access.

For deployment set `FRONTEND_ORIGIN` to the exact public origin and `SECURE_COOKIES=true`. Keep the database private. Same-origin browser requests go through the frontend proxy, avoiding cross-site refresh-cookie handling. Refresh/logout require an exact Origin match; direct API callers must supply that origin as well.

A private Sites deployment can host the frontend only. It requires a reachable, separately deployed Java backend through the `BACKEND_URL` server setting. **An unconfigured Sites preview cannot sign users in or execute workflows**, and displays that limitation explicitly. Sites does not host JVM/PostgreSQL processes.

## Important limitations

- Full backend compilation, Spring integration tests, PostgreSQL execution and live GitHub/LLM calls were blocked in the build environment. Do not treat syntax parsing as compilation or runtime verification.
- Repository context is README + bounded recent findings/memory/latest diff; there is no whole-repository index or embeddings. Chat cannot reliably answer questions about source it has never seen.
- Local rules are deliberately narrow; document generation and chat in local mode summarize evidence rather than infer architecture.
- PR/commit metadata is fetched live. Analyses retain the reference and bounded diff; separate historical PR/commit tables are not implemented.
- One scheduled worker per instance; locks permit multiple instances, but multi-worker concurrency/load behavior has not been tested. Upstream calls run outside worker database transactions.
- Diff coverage is limited to 300 files/60,000 characters and provider response limits; partial coverage is explicitly reported. A too-large GitHub response can fail the job rather than produce a partial result.
- Global webhook secret, operator-managed provider keys, no OAuth/App installation flow, no team workspaces, no billing. Production needs secret rotation, retention/backups and edge rate limits.
- Access JWTs remain valid up to ten minutes after logout; refresh tokens revoke immediately. Token-family compromise detection and all-device logout are not implemented.
- Auth throttling is per backend network peer (60 login/registration requests/minute). Put per-client limits at the edge; do not trust arbitrary forwarded headers.
- Analysis budget is 100 new jobs per repository/day; there are no token-cost quotas for chat/documentation yet.

Read [Architecture](docs/ARCHITECTURE.md), [API](docs/API.md), [Decisions](docs/DECISIONS.md), [Validation](docs/VALIDATION.md) and [Interview preparation](docs/INTERVIEW.md).
