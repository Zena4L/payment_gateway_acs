# Payment Gateway Integration — User Stories

Senior Java Spring Boot Technical Assessment · Paystack Sandbox · Java 21 / Spring Boot 3.x

Each story uses the *As a / I want / so that* form, lists acceptance criteria that can be turned directly into tests, and traces back to the functional requirement (FR) IDs. Where the brief was ambiguous, a default has been chosen and marked **(assumption)**.

## Contents

- [Personas](#personas)
- [Working status set](#working-status-set)
- [Epic 0: Foundations](#epic-0-foundations)
- [Epic 1: Payment initiation](#epic-1-payment-initiation)
- [Epic 2: Payment verification](#epic-2-payment-verification)
- [Epic 3: Webhook processing](#epic-3-webhook-processing)
- [Epic 4: Transaction retrieval](#epic-4-transaction-retrieval)
- [Epic 5: Reconciliation and recovery](#epic-5-reconciliation-and-recovery)
- [Epic 6: Operations](#epic-6-operations)
- [Traceability matrix](#traceability-matrix)
- [Assumptions to confirm](#assumptions-to-confirm)
- [Out of scope](#out-of-scope)

---

## Personas

| Persona | Description |
|---|---|
| **API client** | An authenticated merchant backend or internal system that initiates and queries payments. |
| **Operations admin** | An internal user who can view all transactions and investigate issues. |
| **Payment service** | The system itself, for behavior no human triggers directly (webhooks, reconciliation). |
| **Customer** | Pays on Paystack's checkout page; never calls the API directly, so appears only in "so that" clauses. |

## Working status set

**(Assumption — finalized in the design step.)**

| Status | Meaning | Terminal |
|---|---|---|
| `INITIALIZING` | Saved locally; provider not yet confirmed | No |
| `PENDING` | Checkout created; awaiting customer | No |
| `UNKNOWN` | Provider outcome lost (timeout / 500) | No |
| `REQUIRES_REVIEW` | Provider data does not match local record | No (manual resolution only) |
| `SUCCESS` | Payment confirmed by provider | Yes |
| `FAILED` | Payment failed or never created at provider | Yes |
| `EXPIRED` | Unresolved past final cutoff | Yes |

---

## Epic 0: Foundations

The groundwork the other epics build on: the payment record, its lifecycle rules, authentication, errors and tracing. Build these first.

### US-0.1: Store payments safely
*Traces to: FR-1.4, FR-1.7, FR-1.8, FR-4.5 (foundation)*

**As the** payment service, **I want** a payment record with the right constraints, **so that** every later story can rely on the database to stop duplicates and lost updates.

**Acceptance criteria**
- [x] A Flyway migration creates the `payment` table with: ID, client reference, owning client, email, amount (`numeric`, scale 2), amount in minor units (`bigint`), currency, status, authorization URL, provider transaction ID, channel, paid-at time, gateway response, version, created and updated timestamps.
- [x] `reference` has a unique constraint.
- [x] `status` only accepts the values in the working status set.
- [x] Rows use optimistic locking through the version column.
- [x] Columns used for filtering and ownership (`status`, `created_at`, owning client) are indexed.
- [x] Amounts are held as `BigDecimal` and minor units as `long`, never floating point.
- [x] The entity never leaves the `payment` module.

### US-0.2: Enforce the state-transition policy
*Traces to: FR-2.7, FR-3.8*

**As the** payment service, **I want** every status change checked against an allowed-transitions policy, **so that** payments can never move into an invalid state.

**Acceptance criteria**
- [x] Allowed transitions are defined in one place in the domain code and documented in the README.
- [x] Terminal states (`SUCCESS`, `FAILED`, `EXPIRED`) cannot transition to any other state.
- [x] A disallowed transition is rejected, logged and leaves the payment unchanged.
- [x] `REQUIRES_REVIEW` can only be resolved by an explicit action, never automatically to `SUCCESS`.

### US-0.3: Record a transition history
*Traces to: FR-5.3*

**As an** operations admin, **I want** a history of every status change, **so that** I can see exactly how a payment reached its current state.

**Acceptance criteria**
- [x] Each transition records the previous state, new state, trigger (initiate, verify, webhook, reconciler) and timestamp.
- [x] The history is written in the same database transaction as the status change.

### US-0.4: Authenticate internal APIs
*Traces to: Brief section 7*

**As an** API client, **I want** the payment endpoints to require authentication, **so that** only authorized systems can create or inspect payments.

**Acceptance criteria**
- [x] Initiate, verify and retrieval endpoints all require authentication.
- [ ] The webhook endpoint is the only unauthenticated endpoint, protected instead by signature (US-3.1).
- [x] Clients authenticate with an API key in the `X-API-Key` header. A missing or invalid key returns `401`.
- [x] Keys are stored only as a SHA-256 hash with a short public key ID, and compared in constant time. The raw key is shown once, when it is issued.
- [x] Each key belongs to one client and has a role, `CLIENT` or `ADMIN`. The authenticated client is available to the application layer, never the raw key.
- [x] A revoked or disabled key returns `401`.
- [x] An initial admin key can be provisioned from the environment so a fresh deployment is usable **(assumption)**.

### US-0.5: Receive standardized errors
*Traces to: Brief sections 4, 7, 9*

**As an** API client, **I want** every error in the same structure, **so that** I can handle failures with one piece of code.

**Acceptance criteria**
- [x] All errors share one format containing a code, message, timestamp, path and correlation ID, plus field errors for validation failures.
- [x] Stack traces and internal exception messages are never returned.

### US-0.6: Trace requests by correlation ID
*Traces to: Brief section 9*

**As an** operations admin, **I want** each request tagged with a correlation ID, **so that** I can follow one payment's journey through the logs.

**Acceptance criteria**
- [x] The service accepts an incoming correlation ID header or generates one.
- [x] The ID is returned in the response and included in every log line for that request.
- [x] Logs avoid PII such as full email addresses.
- [x] The same correlation ID appears in error responses (US-0.5).

---

## Epic 1: Payment initiation

### US-1.1: Initiate a payment
*Traces to: FR-1.1, FR-1.3, FR-1.4, FR-1.5, FR-1.6*

**As an** API client, **I want** to initiate a payment with an email, amount, currency and reference, **so that** I receive a checkout link to send my customer to.

**Acceptance criteria**
- [x] Given a valid request, the service saves the payment as `INITIALIZING` before calling Paystack.
- [x] The amount is sent to Paystack in pesewas (GHS 150.00 → 15000), calculated with `BigDecimal`.
- [x] On a successful provider response, the status becomes `PENDING` and the authorization URL is stored. Paystack's access code is not stored (brief §6: no secret tokens in plain transaction records).
- [x] The response is `201 Created` with `reference`, `status: PENDING`, `authorizationUrl` and a message.
- [x] The response never reports the payment as successful or paid.

### US-1.2: Reject invalid payment requests
*Traces to: FR-1.2, FR-1.3*

**As an** API client, **I want** invalid requests rejected with clear field-level errors, **so that** I can fix them before any payment is created.

**Acceptance criteria**
- [x] Missing or malformed email returns `400` with a field error.
- [x] Zero, negative or missing amounts return `400`.
- [x] Amounts with more than 2 decimal places (e.g. 150.005) return `400` rather than being silently rounded.
- [x] Unsupported currencies return `400`. Only GHS is supported initially **(assumption: configurable allow-list)**.
- [x] A missing reference, or one exceeding the agreed length or containing disallowed characters, returns `400`.
- [x] No database record is created and Paystack is not called for any rejected request.

### US-1.3: Safely replay an identical request
*Traces to: FR-1.7*

**As an** API client, **I want** to resend the same initiation request after a network glitch, **so that** I can retry without risking a duplicate payment.

**Acceptance criteria**
- [x] Given a payment already exists with the same reference, email, amount and currency, the service returns the existing payment with `200 OK`.
- [x] Paystack is not called again.
- [x] The response contains the same authorization URL as the original.

### US-1.4: Reject conflicting reuse of a reference
*Traces to: FR-1.8*

**As an** API client, **I want** reuse of a reference with different details to be rejected, **so that** one reference always means exactly one logical payment.

**Acceptance criteria**
- [x] Given a payment exists with reference X, a new request for reference X with a different amount, currency or email returns `409 Conflict`.
- [x] The existing payment is unchanged.

### US-1.5: Handle concurrent duplicate requests
*Traces to: FR-1.7, FR-1.8*

**As an** API client, **I want** simultaneous identical requests to produce only one payment, **so that** a double-click or parallel retry never creates two charges.

**Acceptance criteria**
- [x] Given two requests with the same reference arrive at the same time, exactly one record is created, enforced by a database unique constraint on `reference`.
- [x] Exactly one Paystack initialize call is made for that reference.
- [x] The losing request receives the same outcome as a replay (US-1.3) or a conflict (US-1.4), never a `500`.

### US-1.6: Preserve ambiguous outcomes
*Traces to: FR-1.4, FR-1.9*

**As an** API client, **I want** a lost provider response reported as unresolved rather than failed, **so that** I don't tell a customer their payment failed when it may have gone through.

**Acceptance criteria**
- [x] Given Paystack times out or returns a `500`, the payment moves to `UNKNOWN` and the reference is preserved.
- [x] The service does not automatically call initialize again for that payment.
- [x] The response is `202 Accepted` with `status: UNKNOWN` and a message that the outcome is being confirmed **(assumption)**.
- [x] The payment is later resolved through verification (US-2.5) or reconciliation (US-5.1).

### US-1.7: Handle provider rejections and unavailability
*Traces to: FR-1.10*

**As an** API client, **I want** provider errors returned as controlled, consistent errors, **so that** I can react predictably without seeing internal details.

**Acceptance criteria**
- [x] Paystack `400` and `401` responses are mapped to a standardized error (`502 Bad Gateway`, since the fault is upstream or in configuration) and the payment moves to `FAILED`.
- [x] Paystack `429` and `503` responses (requests that were definitely not processed) are retried with exponential backoff up to a limit, then reported as a standardized `503` error.
- [x] The secret key, raw provider payloads and stack traces never appear in responses or logs.

### US-1.8: Block a duplicate payment under a new reference
*Traces to: FR-1.7*

**As an** API client, **I want** a repeated purchase sent with a fresh reference to be caught, **so that** a double-click or a client retry that generates a new reference never gives the customer two checkout links to pay.

**Acceptance criteria**
- [x] Given a payment exists for the same client, email, amount and currency, created within the duplicate window (default 60 seconds, configurable) and not `FAILED` or `EXPIRED`, a request with a new reference returns `409 Conflict` with code `duplicate-payment` **(assumption)**.
- [x] The error includes the existing payment's `existingReference` and `existingTransactionId` so the client can resume it.
- [x] Nothing is saved for the rejected request and Paystack is not called.
- [x] A `SUCCESS` or `REQUIRES_REVIEW` payment also blocks, so a paid purchase is not started again by accident.
- [x] Once the earlier payment is `FAILED` or `EXPIRED`, or the window has passed, the same details are accepted.
- [x] A different client, email or amount is never blocked.
- [x] Concurrent requests with the same details and different references create exactly one payment and make one Paystack call; the others get `duplicate-payment`, never a `500`.
- [x] Requests with the same reference keep the replay (US-1.3) and conflict (US-1.4) behaviour.
- [x] A window of `0` turns the guard off.

---

## Epic 2: Payment verification

### US-2.1: Verify a payment's outcome
*Traces to: FR-2.1, FR-2.2, FR-2.3*

**As an** API client, **I want** to check the real status of a payment by reference, **so that** I only fulfil orders that have actually been paid.

**Acceptance criteria**
- [x] An unknown local reference returns `404` and Paystack is not called.
- [x] The service calls Paystack verify and determines the outcome from the transaction's `status` field, not the HTTP status code.
- [x] `success` maps to `SUCCESS`; `failed` maps to `FAILED`; `ongoing`, `pending`, `processing`, `queued` and `abandoned` map to `PENDING` **(assumption: abandoned is not terminal because the customer can still complete it)**.
- [x] An unrecognized provider status leaves the local status unchanged and is logged for investigation.
- [x] The response returns the current local status and reference.

### US-2.2: Detect data mismatches
*Traces to: FR-2.4*

**As an** operations admin, **I want** payments whose provider data doesn't match our records flagged, **so that** tampering or bugs never result in a false success.

**Acceptance criteria**
- [x] Given Paystack's reference, amount or currency differs from the local record, the payment moves to `REQUIRES_REVIEW`, never `SUCCESS`.
- [x] The mismatch is logged with the expected and actual values (no secrets or card data).
- [x] The API response indicates the payment needs review **(assumption: `200` with status `REQUIRES_REVIEW` rather than an error)**.

### US-2.3: Record provider details
*Traces to: FR-2.5*

**As an** operations admin, **I want** provider identifiers and timestamps stored on confirmed payments, **so that** I can trace any payment back to Paystack.

**Acceptance criteria**
- [x] On `SUCCESS` or `FAILED`, the service stores the Paystack transaction ID, payment channel (card, mobile money, etc.), paid-at time and gateway response message.
- [x] `updated_at` is refreshed on every state change.

### US-2.4: Repeat verification safely
*Traces to: FR-2.6, FR-2.7*

**As an** API client, **I want** to call verify as many times as I like, **so that** polling is always safe.

**Acceptance criteria**
- [x] Verifying a payment already in a terminal state returns the same result with no state change.
- [x] A provider response that contradicts a terminal state (e.g. `failed` after a confirmed `SUCCESS`) does not change the local state, and the conflict is logged.
- [x] Concurrent verifications of the same payment produce a single state transition, enforced by optimistic locking.

### US-2.5: Resolve an UNKNOWN payment
*Traces to: FR-1.9, FR-2.2*

**As an** API client, **I want** verification to resolve a payment whose initialization outcome was lost, **so that** ambiguous payments don't stay stuck.

**Acceptance criteria**
- [x] Given an `UNKNOWN` payment, verify asks Paystack for its status by reference.
- [x] If Paystack reports the transaction, the local status follows the mapping in US-2.1.
- [x] If Paystack reports the reference doesn't exist, the payment moves to `FAILED`, since no checkout was ever created.

### US-2.6: Handle provider failures during verification
*Traces to: FR-2.2*

**As an** API client, **I want** verification failures reported clearly while leaving the payment untouched, **so that** a temporary outage never corrupts payment state.

**Acceptance criteria**
- [x] Because verify is a read, timeouts, `429`, `500` and `503` are retried with exponential backoff.
- [x] If retries are exhausted or the circuit breaker is open, the service returns a standardized `503` and the local status is unchanged.

---

## Epic 3: Webhook processing

### US-3.1: Authenticate webhook notifications
*Traces to: FR-3.1, FR-3.2, FR-3.3*

**As the** payment service, **I want** to accept only genuine Paystack notifications, **so that** nobody can fake a successful payment.

**Acceptance criteria**
- [x] The endpoint is publicly reachable and needs no API authentication.
- [x] The service computes HMAC-SHA512 over the raw request bytes using the secret key and compares it to `x-paystack-signature` in constant time.
- [x] A missing or invalid signature returns `401`, and the event is not processed or applied.
- [x] Signature comparison happens before the body is parsed into objects.

### US-3.2: Record every authenticated event
*Traces to: FR-3.4, FR-3.10*

**As an** operations admin, **I want** every webhook stored with its processing result, **so that** I can investigate what Paystack sent and what we did with it.

**Acceptance criteria**
- [x] Each authenticated event is stored with its deduplication key, event type, reference, received time, processing state and processed time.
- [x] Processing results include `PROCESSED`, `DUPLICATE`, `IGNORED`, `REJECTED` and `FAILED`, with a safe diagnostic message.
- [x] Stored diagnostics exclude secrets and the signature header.

### US-3.3: Deduplicate replayed events
*Traces to: FR-3.5*

**As the** payment service, **I want** re-delivered events acknowledged without being reapplied, **so that** Paystack's retries never cause duplicate side effects.

**Acceptance criteria**
- [x] The deduplication key is the event type combined with the Paystack transaction ID **(assumption)**, enforced by a unique constraint.
- [x] A duplicate returns `200` and is recorded as `DUPLICATE` with no state change.
- [x] Two identical events arriving concurrently result in exactly one being applied.

### US-3.4: Apply payment events safely
*Traces to: FR-3.6, FR-3.7*

**As the** payment service, **I want** a success event to complete a payment only after it is correlated and validated, **so that** payment states are always trustworthy.

**Acceptance criteria**
- [x] The event is matched to a local payment by reference.
- [x] Amount and currency must match the local record; a mismatch moves the payment to `REQUIRES_REVIEW`.
- [x] Before marking `SUCCESS`, the service confirms via Paystack verify **(assumption: defense in depth)**, or at minimum applies the same rules as Epic 2.
- [x] The state change follows the transition policy (US-0.2).

### US-3.5: Handle out-of-order events
*Traces to: FR-3.8*

**As the** payment service, **I want** late or out-of-sequence events handled correctly, **so that** a stale event never reverses a confirmed outcome.

**Acceptance criteria**
- [x] An event that would move a terminal payment to a different state is recorded but not applied.
- [x] An event that arrives before the local record exists is handled per US-3.6.

### US-3.6: Handle unknown events and references
*Traces to: FR-3.9*

**As the** payment service, **I want** unrecognized events acknowledged without action, **so that** Paystack stops retrying events we don't need.

**Acceptance criteria**
- [x] Unhandled event types are stored as `IGNORED` and return `200`.
- [x] Events for references that don't exist locally are stored as `IGNORED`, return `200` and are logged for investigation.

### US-3.7: Let Paystack retry when processing fails
*Traces to: FR-3.10*

**As the** payment service, **I want** temporary internal failures to trigger Paystack's retry, **so that** no genuine payment notification is lost.

**Acceptance criteria**
- [x] If the event can't be stored or processed because of an internal error (e.g. the database is unavailable), the service returns a non-2xx status so Paystack redelivers it.
- [x] The endpoint responds quickly and does not hold the connection open for slow work.

---

## Epic 4: Transaction retrieval

### US-4.1: List transactions
*Traces to: FR-4.1*

**As an** API client, **I want** to page through my transactions in a chosen order, **so that** I can review payment history efficiently.

**Acceptance criteria**
- [x] Results are paginated with page and size parameters, and size has a maximum cap.
- [x] Sorting is supported on created date, amount and status, defaulting to newest first.
- [x] The response includes total elements and total pages.

### US-4.2: Filter transactions
*Traces to: FR-4.2*

**As an** API client, **I want** to filter transactions by status, reference and date range, **so that** I can find specific payments quickly.

**Acceptance criteria**
- [x] Filters can be used individually or combined.
- [x] Date-range filters apply to the created date, and an inverted range returns `400`.
- [x] Queries are backed by database indexes on the filtered columns.

### US-4.3: View a single transaction
*Traces to: FR-4.3*

**As an** API client, **I want** to fetch one transaction by its ID, **so that** I can see its full current state.

**Acceptance criteria**
- [x] A valid ID that the caller is authorized to see returns the transaction.
- [x] A nonexistent ID returns `404`.

### US-4.4: Restrict access by ownership
*Traces to: FR-4.4, FR-4.5*

**As an** API client, **I want** to see only my own transactions, **so that** other clients' payment data stays private.

**Acceptance criteria**
- [x] All retrieval endpoints require authentication; unauthenticated calls return `401`.
- [x] A client sees only payments it created **(assumption: ownership recorded on each payment)**.
- [x] An operations admin can see all transactions.
- [x] A client requesting another client's transaction by ID gets `404`, not `403`, so the existence of other payments isn't revealed.

### US-4.5: Expose only safe fields
*Traces to: FR-4.6*

**As the** payment service, **I want** responses to exclude sensitive data, **so that** nothing secret leaks through the API.

**Acceptance criteria**
- [x] Responses never include the access code, secret keys, raw provider payloads or internal diagnostics.
- [x] No card numbers or CVVs exist in the system to expose.

---

## Epic 5: Reconciliation and recovery

### US-5.1: Reconcile stale payments
*Traces to: FR-5.1*

**As the** payment service, **I want** payments stuck in `INITIALIZING`, `PENDING` or `UNKNOWN` re-verified periodically, **so that** missed webhooks or lost responses are corrected automatically.

**Acceptance criteria**
- [x] A scheduled job selects unresolved payments older than a configurable threshold.
- [x] Each is verified against Paystack using the same logic as Epic 2.
- [x] The job processes payments in batches and respects provider rate limits.
- [x] Running the job multiple times, or on multiple instances, does not cause duplicate transitions.

### US-5.2: Expire abandoned payments
*Traces to: FR-5.2*

**As an** operations admin, **I want** payments unresolved past a final cutoff marked as expired, **so that** our records don't accumulate indefinitely pending payments.

**Acceptance criteria**
- [x] Payments still unresolved after a configurable cutoff are verified one final time, then moved to `EXPIRED` if still not successful.
- [x] The cutoff is configurable through typed configuration.

### US-5.3: Recover when Paystack succeeds but our database fails
*Traces to: FR-1.4, FR-3.7, FR-5.1*

**As the** payment service, **I want** a successful payment to be recorded eventually even if our database failed at the critical moment, **so that** a paying customer is never left marked as unpaid.

**Acceptance criteria**
- [x] Because the payment was saved before calling Paystack, a record always exists to reconcile against.
- [x] A failed webhook write returns non-2xx so Paystack redelivers (US-3.7).
- [x] Any remaining gap is closed by the reconciliation job (US-5.1).

---

## Epic 6: Operations

Work for the people who run the service, beyond the brief's tasks.

### US-6.1: Resolve payments under review
*Traces to: US-0.2 (`REQUIRES_REVIEW` is resolved only by an explicit action)*

**As an** operations admin, **I want** to decide the outcome of a payment under review, **so that** mismatched or reversed payments don't stay stuck forever.

**Acceptance criteria**
- [x] `POST /api/v1/admin/payments/{transactionId}/resolution` with `outcome` (`SUCCESS` or `FAILED`) and a `reason` resolves a `REQUIRES_REVIEW` payment and returns it.
- [x] The history records the change with trigger `ADMIN`, the admin's identity and the reason. The database rejects an admin step without both.
- [x] A payment not under review returns `409` and stays unchanged.
- [x] An unknown id returns `404`. An invalid outcome or a missing or too-long reason returns `400`.
- [x] Only admin keys can resolve (`403` for clients, `401` without a key).
- [x] Two admins resolving at the same time result in exactly one change; the other gets `409`.

### US-6.2: See a payment's audit trail
*Traces to: US-0.3 (transition history)*

**As an** operations admin, **I want** to see every status change of a payment, **so that** I understand why it ended up where it is before I act.

**Acceptance criteria**
- [x] `GET /api/v1/admin/payments/{transactionId}/transitions` returns every transition in order, with from, to, trigger, actor, reason and time.
- [x] Only admin keys can read it; an unknown id returns `404`.

---

## Traceability matrix

| Functional requirement | Description | User stories |
|---|---|---|
| FR-1.1 | Accept initiation request | US-1.1 |
| FR-1.2 | Validate inputs | US-1.2 |
| FR-1.3 | Convert to smallest unit with `BigDecimal` | US-1.1, US-1.2 |
| FR-1.4 | Persist before provider call | US-0.1, US-1.1, US-1.6, US-5.3 |
| FR-1.5 | Call Paystack initialize, store URL | US-1.1 |
| FR-1.6 | Return `PENDING`, never "paid" | US-1.1 |
| FR-1.7 | Idempotent replay of identical request | US-0.1, US-1.3, US-1.5, US-1.8 |
| FR-1.8 | Conflict on reference reuse with different payload | US-0.1, US-1.4, US-1.5 |
| FR-1.9 | Ambiguous outcome → `UNKNOWN`, no blind retry | US-1.6, US-2.5 |
| FR-1.10 | Controlled provider error mapping | US-1.7 |
| FR-2.1 | Look up local payment first | US-2.1 |
| FR-2.2 | Use provider `status`, not HTTP code | US-2.1, US-2.5, US-2.6 |
| FR-2.3 | Map provider → internal statuses | US-2.1 |
| FR-2.4 | Check reference, amount, currency match | US-2.2 |
| FR-2.5 | Record provider ID, channel, timestamps | US-2.3 |
| FR-2.6 | Idempotent verification | US-2.4 |
| FR-2.7 | Terminal states never move backward | US-0.2, US-2.4 |
| FR-3.1 | Public webhook endpoint | US-3.1 |
| FR-3.2 | HMAC-SHA512 over raw bytes | US-3.1 |
| FR-3.3 | Reject invalid signatures | US-3.1 |
| FR-3.4 | Persist events with dedup key | US-3.2 |
| FR-3.5 | Deduplicate replays | US-3.3 |
| FR-3.6 | Correlate with local payment | US-3.4 |
| FR-3.7 | Don't trust payload alone | US-3.4, US-5.3 |
| FR-3.8 | Out-of-order safety | US-0.2, US-3.5 |
| FR-3.9 | Acknowledge unknown events | US-3.6 |
| FR-3.10 | Record processing result and diagnostics | US-3.2, US-3.7 |
| FR-4.1 | Pagination and sorting | US-4.1 |
| FR-4.2 | Status, reference, date filters | US-4.2 |
| FR-4.3 | Get by ID | US-4.3 |
| FR-4.4 | Authentication on retrieval | US-4.4 |
| FR-4.5 | Ownership-based authorization | US-0.1, US-4.4 |
| FR-4.6 | No secrets in responses | US-4.5 |
| FR-5.1 | Reconcile stale payments | US-5.1, US-5.3 |
| FR-5.2 | Expire unresolved payments | US-5.2 |
| FR-5.3 | Status transition history | US-0.3 |
| Brief §7 | Authenticate internal APIs | US-0.4 |
| Brief §4, §7, §9 | Standardized, safe errors | US-0.5 |
| Brief §9 | Correlation IDs, no PII in logs | US-0.6 |

---

## Assumptions to confirm

These defaults resolve ambiguities in the brief and should be documented in the README:

1. **Transaction ownership:** clients see only payments they created; an admin role sees all.
2. **Reference generation:** the client supplies the reference (enables idempotency).
3. **Currencies:** GHS only, via a configurable allow-list.
4. **`abandoned` status:** treated as non-terminal (`PENDING`) because the customer can still complete payment.
5. **Webhook dedup key:** event type + Paystack transaction ID.
6. **Customer redirect (`callback_url`):** not handled by this service; if added later, it only triggers a verify.
7. **Verify on mismatch:** returns `200` with status `REQUIRES_REVIEW` rather than an error.
8. **Ambiguous initiation response:** returns `202 Accepted` with status `UNKNOWN`.
9. **Webhook confirmation:** success events are cross-checked with Paystack verify before marking `SUCCESS`.
10. **Duplicate purchase under a new reference:** the same client, email, amount and currency within 60 seconds of a live payment is treated as an accidental repeat and rejected with `409 duplicate-payment`.

## Out of scope

- Refunds and reversals
- Live-mode payments (sandbox only)
- Multiple payment providers (a provider interface keeps this possible later)
- Customer-facing UI
- Storage of card data of any kind

Delivery items such as Docker, OpenAPI/Swagger and the Postman collection are covered under non-functional and delivery requirements, not user stories.
