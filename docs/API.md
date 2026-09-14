# HTTP API

Base path: `/api/v1`. Responses use JSON. Send bearer access tokens to every endpoint except auth, webhook and health. The frontend proxies requests from its own origin.

List endpoints return Spring page JSON (`content`, `number`, `totalElements`, `totalPages`). `page` is zero-based; default size is 30, maximum 100. GitHub list endpoints are one-based, fixed 30 records per page. Response DTOs omit entity secrets and large raw source snapshots.

## Identity

| Method | Path | Request / behavior |
|---|---|---|
| POST | /auth/register | `{name,email,password}` → 201 `{accessToken,user}` + refresh cookie |
| POST | /auth/login | `{email,password}` → session |
| POST | /auth/refresh | Refresh cookie + exact allowed Origin → rotated session |
| POST | /auth/logout | Refresh cookie + exact allowed Origin → 204 |
| GET | /users/me | Current user DTO |
| GET | /admin/stats | ADMIN only; aggregate counters |

Password minimum 12 characters on registration, maximum 72 UTF-8 bytes. Every self-registration is USER, irrespective of unknown extra request fields.

## GitHub and repositories

| Method | Path | Request / behavior |
|---|---|---|
| GET | /github | Connection status/login; never token |
| POST | /github | `{token}` verifies and encrypts token |
| DELETE | /github | Disconnect credentials → 204 |
| GET | /github/repositories?page=1 | Live GitHub repositories |
| GET | /repositories | Owned repositories; paginated |
| POST | /repositories | `{fullName:"owner/repository"}` → 201 |
| GET | /repositories/{r} | Owned repository |
| POST | /repositories/{r}/sync | Refresh metadata + README |
| GET | /repositories/{r}/branches?page=1 | Live branch names |
| GET | /repositories/{r}/changes?kind=PR&page=1 | PR or COMMIT metadata |
| GET | /repositories/{r}/diff?kind=PR&ref=42 | Bounded diff + changed files + truncated flag |
| POST | /webhooks/github | Raw JSON, X-Hub-Signature-256, X-GitHub-Delivery, X-GitHub-Event → 202 |

GitHub commit refs must be 7–40 hex characters; PR refs must be positive numeric identifiers. Branch deletion events do not trigger analysis.

## Engineering workflows

All paths below are relative to `/repositories/{r}` and enforce ownership before child lookup.

| Method | Path | Request / behavior |
|---|---|---|
| POST | /analyses | `{kind:"PR",ref:"42"}` → 202 job; identical active job reused |
| GET | /analyses | Paginated jobs; queue/run/failure/source timestamps |
| GET | /analyses/{id} | One owned analysis |
| GET | /findings?analysisId={id} | Optional analysis filter; paginated |
| GET | /memories | Paginated memory records |
| PATCH | /memories/{id} | `{status:"ACTIVE"}` or ARCHIVED |
| GET | /technical-debt?status=OPEN&severity=HIGH | Optional status/severity filters |
| POST | /technical-debt | See debt request below → 201 |
| PATCH | /technical-debt/{id} | `{status,estimatedEffort}` |
| GET | /stats | Total/open/inProgress/resolved debt, critical findings, category and severity counts |
| GET | /conversations | Paginated conversations |
| POST | /conversations | `{title}` → 201 |
| GET | /conversations/{id}/messages | Paginated newest-first messages |
| POST | /conversations/{id}/messages | `{content}` up to 4,000 characters → persisted user/assistant pair |
| GET | /documents | Paginated versions |
| GET | /documents/{id} | One document version |
| POST | /documents | `{kind}` → 201 new immutable version |

Debt creation body:

```json
{"title":"Validate request DTOs","description":"Add validation to external inputs","category":"API","severity":"HIGH","estimatedEffort":"2 hours","sourceFindingId":null}
```

When sourceFindingId is present, the backend verifies repository ownership of that finding and derives title/description/category/severity from it. A repeated promotion returns the existing debt.

Debt statuses: OPEN, IN_PROGRESS, RESOLVED, IGNORED. Severity: CRITICAL, HIGH, MEDIUM, LOW. Document kinds: PROJECT_OVERVIEW, ARCHITECTURE, API_DOCUMENTATION, AUTHENTICATION_FLOW, DATABASE_DESIGN, MODULE_DOCUMENTATION, SETUP_GUIDE, README.

## Errors

Normal controller errors have timestamp, status, error, message, path and fieldErrors. Authentication/security filters return JSON. The early auth throttle and proxy availability errors use smaller JSON envelopes with a message; the frontend intentionally accepts both.

- 400: invalid DTO, ref, pagination or malformed payload.
- 401: missing/invalid access token or expired/replayed refresh token; invalid webhook signature.
- 403: role denied or refresh/logout Origin mismatch.
- 404: missing resource, including resources owned by someone else.
- 409: duplicate or concurrent-state conflict.
- 413: request/webhook too large.
- 429: authentication throttle or repository analysis budget.
- 502: external integration failure / unreachable proxied backend.
- 503: frontend backend destination not configured.

Errors never include provider tokens, request passwords, database exception details or raw upstream response bodies. X-Request-ID can be used to locate request logs.
