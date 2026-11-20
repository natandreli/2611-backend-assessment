# ADR-0001: Integration with Salesforce

- **Status:** Accepted
- **Date:** 2026-11-19

## Context

This ADR answers four questions:

1. Do we write to Salesforce **synchronously or asynchronously**?
2. Do we write **per record or in batches**?
3. How do we **protect the shared API budget**?
4. How do we **live with Salesforce's 97% availability**?

Every payment must be recorded in Salesforce as a `Premium_Payment__c` whose status follows the provider's lifecycle (FR3). Each payment must be recorded exactly once in business terms (FR4), and a late or out-of-order notification must never leave it in a contradictory state (FR5). The `Status__c` field drives a Salesforce flow that reinstates or suspends policies, so a wrong status changes a customer's coverage.

The forces at play:

| Force | Value | Source |
|---|---|---|
| Provider response deadlines | Andina **5 s**; Quetzal and Austral 10 s | Provider specifications, NFR4 |
| Salesforce latency | Single upsert p95 ≈ 1.2 s, up to **8 s** under load. 200-record upsert p95 ≈ 4 s | `API_CONSTRAINTS.md` |
| Salesforce availability | ≈ **97%** per month, in unplanned windows of 5 to 90 minutes | NFR5 |
| API budget | **150,000 calls per rolling 24 h** for this integration. Every REST request is one call, whatever the number of records. Exceeding the **org-wide** limit blocks **every** integration of the org, including the contact center | NFR5 |
| Concurrency | At most **25** concurrent requests from the integration user | `API_CONSTRAINTS.md` |
| Volume | 15,000 notifications per day; **120,000** on peak days, with bursts of **40/s** for 15 minutes | NFR1 |
| Freshness | 95% within **5 minutes**; 99.5% within **1 hour** | NFR2 |

### Terms used in this ADR

The overall solution is described in [`SOLUTION_DESIGN.md`](../design/SOLUTION_DESIGN.md), and the stack and data store in [ADR-0002](0002-stack-and-data-store.md). In short: providers call an **intake** service through the API Manager; the intake verifies the notification, translates it into a **canonical event** and publishes it to **Azure Service Bus**; a **writer** service reads those events and records them in Salesforce.

| Term | Meaning |
|---|---|
| Intake | The service that receives provider webhooks, verifies their signature, translates them into canonical events and publishes them. It answers the provider only after the event is safely stored in Service Bus. |
| Canonical event | The single, provider-independent representation of a payment notification (FR2). It carries no payer personal data. |
| Azure Service Bus | Azure's managed message broker. It keeps each message until a consumer has processed it, so messages survive a Salesforce outage or a restart of our services. The canonical events are published to a **topic**; each consumer (the writer, Finance, Notifications) reads its own copy through a **subscription**. The choice of Service Bus over other Azure messaging services is justified in [ADR-0003](0003-message-broker.md). |
| Writer | The service that reads canonical events from its Service Bus subscription and writes them to Salesforce. It is the only component that calls Salesforce. |
| Integration store | The integration's own database (Azure SQL, see ADR-0002). It keeps the current state of each payment and the processing history of each notification, so the writer never has to read Salesforce. |
| Payment key | `Payment_Key__c`, the external ID of `Premium_Payment__c`: a provider prefix plus the provider's transaction ID (for example `AND-TRX-CO-5521907`). One record per payment; its status is updated as the payment moves through its lifecycle. |
| Payment state machine | The rule that decides whether a notification may change a payment's status. Statuses have a rank (`Pending` 1 < `Captured` / `Failed` 2 < `Refunded` 3) and a payment can only move forward. A notification with a lower rank than the current status is ignored; the provider's state-change time only breaks ties between statuses of the same rank (FR5). See assumption 1 in [`ASSUMPTIONS.md`](../ASSUMPTIONS.md). |
| Needs attention | Status of a payment that cannot be processed automatically and must be reviewed by Operations (for example, an unknown policy number). It is not retried until someone fixes the cause. |
| Replay | Sending a payment again through the writer after the cause of its failure has been fixed. It is safe because the upsert by payment key and the state machine prevent duplicates and backward moves. |
| Dead-letter queue (DLQ) | A Service Bus queue where messages that could not be processed are moved, so they stop blocking the flow and can be inspected and replayed. |
| p50 / p95 | Latency percentiles: 50% / 95% of requests take this long or less. |

## Options considered

### Option 1 — Synchronous, per record, inside the webhook request

The intake writes to Salesforce and only then answers the provider.

- Pros:
  - Simplest flow.
  - The payment is already in Salesforce when the provider gets its `2xx`.
- Cons:
  - Salesforce latency (up to 8 s) exceeds Andina's 5 s deadline, so the provider treats slow requests as failures and retries them, creating duplicates.
  - During a Salesforce outage every notification is rejected; once the provider's retries run out, the payment is lost.
  - A Salesforce problem becomes a problem with all three providers.
  - On a peak day it would use ≈ 120,000 calls (80% of the budget) before any retry.

### Option 2 — Asynchronous, per record

The intake publishes to a queue and answers immediately; a consumer upserts one record per message.

- Pros:
  - Providers are decoupled from Salesforce.
  - Salesforce outages only delay processing.
- Cons:
  - The call count still grows with volume: ≈ 120,000 calls on a peak day, plus retries. INC-0412 shows the risk: immediate per-message retries pushed v1 to 66% of the budget in one day.
  - It cannot keep up with the month-end burst. At 40 notifications per second and a p95 latency of ≈ 1.2 s per call, it needs ≈ 48 requests in flight at the same time, almost twice the 25 allowed. The queue would keep growing for the whole 15-minute burst and freshness would degrade.

### Option 3 — Asynchronous, in batches with the composite upsert API (chosen)

A writer consumes the canonical events, applies the payment state machine in the integration's own store, and upserts up to **200 records per request** (`PATCH /composite/sobjects/Premium_Payment__c/Payment_Key__c`, `allOrNone: false`). A batch is sent when it reaches 200 records **or** 5 seconds after the previous send, whichever comes first.

- Pros:
  - Providers are decoupled from Salesforce.
  - The number of calls is bounded by time, not by volume.
  - Freshness stays far below the 5-minute target.
- Cons:
  - More complex writer: batching, per-record results and retries.
  - Up to 5 s of added latency.

### Option 4 — Salesforce Bulk API 2.0

The writer creates asynchronous bulk jobs that Salesforce processes in the background.

- Pros:
  - Designed for very large data loads.
- Cons:
  - Each job needs several calls: create, upload, close and poll for results.
  - Salesforce processes jobs in the background with no guaranteed completion time, which puts the 5-minute freshness target at risk.
  - Our volume (at most 40/s) does not need it.

## Decision

**Option 3: asynchronous, batched upserts by external ID.**

### Why it wins in this scenario

1. **Asynchronous**, because Salesforce latency (up to 8 s) is longer than Andina's 5 s deadline and Salesforce is unavailable about 3% of the time. Only an asynchronous design lets us accept every notification without depending on Salesforce at that moment (NFR3, NFR4). This rules out Option 1.
2. **In batches**, because per-record writes grow with volume (≈ 120,000 calls on a peak day) and need ≈ 48 concurrent requests during the month-end burst, while batches of 200 keep the burst at one call every 5 seconds. This rules out Option 2. Batches through the composite API answer in seconds, unlike Bulk API jobs, so they fit the 5-minute freshness target (NFR2). This rules out Option 4.
3. **The budget is protected** by a fixed ceiling of 17,280 calls per day (11.5%) whatever the volume, a budget guard with alerts, never reading from Salesforce, and never retrying permanent errors.
4. **We live with 97% availability** by letting messages wait in Service Bus during outages behind a circuit breaker, retrying transient errors with backoff, and draining the backlog in parallel once Salesforce is back.

### Writing to Salesforce
- The record key is `Payment_Key__c` = provider prefix + provider transaction ID (for example `AND-TRX-CO-5521907`). The upsert by external ID is idempotent: repeating it does not create a second record (FR4).
- Before a payment enters a batch, the writer applies the state machine in its own store: a state with a lower rank than the current one is ignored, and the provider's state-change time only breaks ties between states of the same rank (FR5). The writer never reads from Salesforce to make this decision, so no budget is spent on reads.
- Within a batch there is at most one record per `Payment_Key__c`, carrying the latest state.
- The integration writes only `Premium_Payment__c`. It never writes `Policy__c` or the policy status.

### Budget and capacity
- A single writer flushing every 5 s makes at most 86,400 / 5 = **17,280 calls per day (11.5% of the budget)**, whatever the volume. That leaves 88% for retries and replays.
- A 40/s burst fills a 200-record batch every 5 s: the 36,000 notifications of a 15-minute burst take ≈ 180 calls.
- Concurrent requests are capped at **5** (20% of the agreed 25), which is enough to drain a backlog and leaves room for other work of the integration user.
- A budget guard counts calls over a rolling 24 h. It raises an alert at 60% of the budget and pauses non-urgent work (replays) at 80%.
- The OAuth access token is cached and reused until it expires (≈ 2 h).

### Error handling

| Salesforce response | Scope | Action |
|---|---|---|
| `401 INVALID_SESSION_ID` | Whole request | Obtain a new token once and retry immediately |
| `400 UNABLE_TO_LOCK_ROW` | One record | Retry that record in a later batch with exponential backoff |
| `500`, `502`, `503`, timeout | Whole request | Retry with exponential backoff; repeated failures open the circuit breaker |
| `403 REQUEST_LIMIT_EXCEEDED` | Whole org | Open the circuit breaker immediately and alert; resume only when usage recovers |
| `400` data errors (`INVALID_FIELD`, `FIELD_CUSTOM_VALIDATION_EXCEPTION`, `REQUIRED_FIELD_MISSING`) | One record | **Never retried.** The payment is marked *needs attention* with the error, an alert is raised, and it is replayed once the data is fixed |

- Retries of individual records use exponential backoff (for example 30 s, 1 min, 2 min, 4 min, then every 5 min) through scheduled messages, never immediate redelivery. Retries continue for up to **24 hours**. After that, the message is moved to the dead-letter queue, an alert is raised, and it is replayed once the cause is fixed.
- The **circuit breaker** stops all writes while Salesforce is unavailable or the org limit is reached. The writer stops reading messages, so they wait in Service Bus without using up retries, and it probes with a single small batch every minute. A 90-minute outage costs about 90 probe calls; nothing is lost.
- Concurrency is only used to catch up: in normal operation one batch is in flight at a time. After an outage, up to 5 batches run in parallel to drain the backlog. Parallelism does not increase the number of calls, only the speed.
- A nightly **reconciliation** compares payments older than one hour in the integration's store with Salesforce (a query returns up to 2,000 records per call) and reports differences to Operations. It does not correct them automatically.

## Consequences

- Positive:
  - Providers never wait for Salesforce, and a Salesforce outage never makes us reject a notification (NFR3, NFR4).
  - Salesforce usage has a fixed ceiling (11.5% of the budget), so this integration cannot exhaust the org-wide limit that the contact center depends on.
  - After an outage the backlog drains quickly. A 90-minute outage on a peak day accumulates ≈ 7,500 notifications (≈ 38 batches), drained in about a minute.
  - Permanent errors no longer burn budget or hide in a dead-letter queue, which is what happened in INC-0412.
- Negative:
  - Up to 5 s of extra latency per payment because of batching.
  - The writer is more complex: batching, per-record results, retry scheduling and a circuit breaker must be built and tested.
  - The payment state lives in two places (the integration's store and Salesforce). They can drift if Salesforce data is changed manually.
  - The integration cannot guarantee NFR2 during long Salesforce outages. With ≈ 3% monthly unavailability, about 3% of notifications may wait longer than 5 minutes, which still meets the 95% target but with a small margin. Notifications that arrive in the first 30 minutes of a 90-minute outage exceed 1 hour.
- Risks and how they are mitigated:
  - *Drift between the store and Salesforce:* the nightly reconciliation compares recent payments and reports differences.
  - *Freshness during outages:* freshness is measured and reported per country (NFR7), and long outages are escalated to the Salesforce team.
  - *Tuning:* the flush interval, backoff values and concurrency cap are configuration, not code, so they can be adjusted with real data.
