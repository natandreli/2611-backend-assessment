# ADR-0002: Implementation stack and data store

- **Status:** Accepted
- **Date:** 2026-11-20

## Context

This ADR answers three questions:

1. Which **technology** do we use to build the intake and the writer: MuleSoft or an alternative?
2. Where do those services **run** on Azure?
3. Do we keep **our own state** outside Salesforce, and if so, in which **data store**?

The components and terms (intake, writer, canonical event, payment state machine) are defined in [ADR-0001](0001-salesforce-integration.md). The message broker is Azure Service Bus ([ADR-0003](0003-message-broker.md)).

The forces at play:

| Force | Value | Source |
|---|---|---|
| Reference stack | Mule 4 with DataWeave and MUnit. Java 21 (Spring Boot 3 or Azure Functions) is accepted if justified here | `TASKS.md` |
| Integration platform | Meridian's platform runs Mule 4 applications and can also front services that run on Azure. Every API must be published through the API Manager | `SCENARIO.md` |
| Provider response deadlines | Andina **5 s**; Quetzal and Austral 10 s. A service that is not ready to answer in time causes provider retries | NFR4 |
| Signature verification | HMAC-SHA256 over the **raw request bytes**, with constant-time comparison. Each provider publishes official test vectors | FR1, provider specifications |
| Volume | 15,000 notifications per day; 120,000 on peak days, with bursts of 40/s | NFR1 |
| Security | Secrets in a secrets store; managed identities with least privilege; no payer personal data in logs | NFR6 |
| State the design needs | Current status of each payment (for the state machine), the processing history of each notification (for Operations), and the canonical event (for replays) | FR4, FR5, FR7 |
| Salesforce API budget | 150,000 calls per rolling 24 h, shared org-wide limit | NFR5 |
| Delivery constraint | Reviewers read the code but do not run it. MUnit needs MuleSoft Enterprise repository credentials, which this assessment does not provide | `README.md`, `TASKS.md` |

### Terms used in this ADR

| Term | Meaning |
|---|---|
| Spring Boot | The most widely used Java framework for building web services. It provides the HTTP server, configuration and integrations with Azure services. |
| Azure Container Apps | Azure service that runs containerized applications without managing servers. It can keep a minimum number of instances (replicas) always running and add more automatically when load grows. |
| Cold start | The delay when a platform starts an instance of a service that was stopped because it had no traffic. |
| Managed identity | An identity that Azure gives to a service so it can access other Azure resources (Service Bus, SQL, Key Vault) without passwords stored anywhere. |
| Unique constraint | A database rule that rejects a second row with the same value in a column, even if two requests arrive at the same time. |
| Transaction | A group of database operations that either all happen or none happen, isolated from other operations running at the same time. |
| Partition (Cosmos DB) | The unit in which Cosmos DB distributes data, chosen by a partition key. Queries that do not include the key must read every partition. |
| TTL (time to live) | Automatic deletion of a record after a given time. |

## Options considered

This ADR covers two related decisions, evaluated separately: the stack and where it runs, and the data store.

### Stack

#### Option 1 — Mule 4 on Meridian's integration platform

The intake and the writer are built as Mule 4 applications, with DataWeave for the mappings, and run on the existing integration platform.

- Pros:
  - It is Meridian's standard integration stack: no new technology for the organization.
  - Runs on a platform that already exists, next to the API Manager.
  - DataWeave is well suited to mapping three provider formats to the canonical event.
- Cons:
  - MUnit tests need MuleSoft Enterprise repository credentials, so they cannot run in a public CI pipeline. The signature verification could not be proven against the providers' official test vectors on every change.
  - No previous experience with Mule in this delivery, which increases the risk of subtle errors in a component that handles money.

#### Option 2 — Java 21 and Spring Boot 3 on Azure Container Apps (chosen)

The intake and the writer are two Spring Boot applications, packaged as containers and run on a standard Azure Container Apps environment.

- Pros:
  - Tests (JUnit 5) run in any CI pipeline without proprietary credentials, so every pull request proves the signature verification against the official test vectors.
  - The Java standard library provides HMAC-SHA256 (`javax.crypto.Mac`) and constant-time comparison (`MessageDigest.isEqual`), and Spring gives direct access to the raw request bytes.
  - Spring Boot and the Azure SDKs for Java are widely documented and supported.
  - Container Apps keeps at least one replica always running, so there is no cold start against Andina's 5 s deadline.
  - Container Apps supports managed identities, Key Vault secret references, availability zones and gradual rollouts with revisions.
- Cons:
  - Adds a technology outside the integration platform that the team must operate.
  - Pays for replicas that are always running, even when there is no traffic.
  - The team must build container images and keep their base images patched.

#### Option 3 — Java 21 on Azure Functions (Consumption plan)

The intake is an HTTP-triggered function and the writer a Service Bus-triggered function.

- Pros:
  - Pay only for executions; no cost when idle.
  - Built-in triggers for HTTP and Service Bus.
- Cons:
  - Cold starts can take several seconds after a period without traffic (for example, at night). The first notification after an idle period could exceed Andina's 5 s deadline and be retried.
  - The Premium plan removes cold starts with pre-warmed instances, but then the cost is similar to always-running replicas and the main advantage is lost.
  - Functions are designed for short, independent executions. The writer accumulates records and sends a batch every 5 seconds, which fits a continuously running process better.

#### Option 4 — Java 21 on Azure Container Apps Express

Express is a simplified Container Apps tier for HTTP applications that scales to zero and starts in under a second.

- Pros:
  - Sub-second start, even from zero replicas.
  - Pay only while running; simpler configuration.
- Cons:
  - Designed for HTTP workloads that run while they receive requests. The writer is a continuously running consumer of Service Bus without HTTP traffic, which is not its target workload.
  - No availability zones, no Key Vault secret references and no traffic splitting between revisions.
  - Generally available only since September 2026.
  - Using Express for the intake and the standard tier for the writer would mean two environment types to operate.

### Data store

#### Option 1 — No store of our own, read the current state from Salesforce

Before each write, the writer reads the payment from Salesforce to apply the state machine.

- Pros:
  - No additional database to build, secure and pay for.
  - Salesforce remains the only place where payment state lives.
- Cons:
  - At least two calls per notification (read and write): ≈ 240,000 calls on a peak day, above the 150,000 budget.
  - During a Salesforce outage the writer cannot even decide whether a notification should be applied.
  - Operations could not see the processing history of a notification (FR7) without searching raw logs.

#### Option 2 — Azure SQL Database (chosen)

A relational database with three tables: one row per payment with its current state, one row per business event already applied, and one row per notification received with its processing history.

- Pros:
  - A **unique constraint** on the event ID of the applied events makes the database itself reject duplicates, even when two arrive at the same instant (FR4).
  - **Transactions** let the writer read the current status and update it as one isolated operation, so two notifications for the same payment never overwrite each other (FR5).
  - **Secondary indexes** make the lookups Operations needs efficient: by provider transaction ID, provider event ID, policy number, correlation ID and status (FR7).
  - The canonical event can be stored as a JSON column, which keeps the table stable when the event schema evolves and provides the source for replays.
  - Our volume (at most 120,000 notifications per day) is small for a relational database.
  - The team already knows SQL, which lowers the operational risk.
- Cons:
  - No automatic expiration of old rows: retention needs a scheduled cleanup job.
  - Schema changes need versioned migrations.
  - Scales mainly by moving to a larger tier; a much larger volume would require a review.

#### Option 3 — Azure Cosmos DB (NoSQL)

A document database with the payment key as partition key.

- Pros:
  - Automatic expiration of old documents (TTL).
  - Flexible schema and very large scale.
  - Serverless pricing for low volumes.
- Cons:
  - Uniqueness and transactions only apply within one partition. With the payment key as partition key they work for one payment, but the Operations lookups (by policy number, provider event ID or status) must read every partition, which costs more and is slower, or require extra containers that duplicate the data.
  - Its main strengths (massive scale, flexible schema) are not what this problem needs: the problem is consistency and varied lookups.
  - The team has less experience with it.

## Decision

**Stack, option 2: Java 21 and Spring Boot 3 on standard Azure Container Apps**, and **data store, option 2: Azure SQL Database**.

### Why it wins in this scenario

1. **Java 21 instead of Mule 4**, because the tests can run in a public CI pipeline without proprietary credentials. The signature verification, the mappings and the normalization rules are proven on every change with the providers' official test vectors and samples. This makes the delivery verifiable, and lets every change, including code written with AI assistance, be validated by running it and not only by reading it. The cost is an extra technology for the team, mitigated because the services are still published and governed through the API Manager.
2. **Standard Container Apps instead of Functions or Express**, because always-running replicas remove cold starts against Andina's 5 s deadline, the writer is a continuously running process, and the standard tier offers availability zones, Key Vault references and gradual rollouts. Running both services on the same platform keeps one way to deploy, monitor and configure them (operational simplicity).
3. **A store of our own**, because reading Salesforce before each write would exceed the API budget and would not work during outages, and FR7 needs a processing history that Salesforce does not keep.
4. **Azure SQL instead of Cosmos DB**, because the problem is about consistency (no duplicates, no backward state changes) and varied lookups, not about scale. Unique constraints, transactions and secondary indexes provide this directly.

### How the services run

| Service | Replicas | Scaling | Notes |
|---|---|---|---|
| Intake | At least 1 always running (2 in production, spread across availability zones) | Adds replicas with HTTP load | The response to the provider depends **only** on the publish to Service Bus. After publishing, the intake records the notification in the database on a best-effort basis (see below). It does **not** depend on the database, so a database problem never makes us reject a notification. |
| Writer | Exactly 1 | Not scaled out | One replica handles the peak (one 200-record batch every 5 s) and keeps the Salesforce call ceiling of ADR-0001. If it stops, Container Apps restarts it and messages wait in Service Bus. |

- Both services use a **managed identity** to reach Service Bus, Azure SQL and Key Vault, with only the roles they need. The intake can only send to the topic and insert rows in the `notification` table; the writer can only read its subscription and write the integration's tables.
- Provider webhook secrets and the Salesforce client secret live in **Azure Key Vault** and are never stored in code or configuration files (NFR6).

### How the data is stored

- Three tables. The schema, keys, indexes and retention are described in [`SOLUTION_DESIGN.md`](../design/SOLUTION_DESIGN.md).
  - `payment`: one row per payment (payment key) with its current status, used by the state machine.
  - `processed_event`: one row per business event applied, with the event ID as primary key (deduplication) and the canonical event as a JSON column (replays).
  - `notification`: one row per request received (correlation ID), with its processing status. Several rows can share an event ID: the original notification and its duplicates.
- **Every request the intake receives is recorded**, so Operations can find any notification without searching raw logs (FR7). This includes duplicates, which Service Bus discards before they reach the writer, and requests rejected because of an invalid signature, which are never published:
  - The intake inserts one row in the `notification` table **after** the publish to Service Bus has succeeded (or after rejecting the request), with a short timeout (for example 500 ms). This write is **best-effort**: if the database does not answer in time, the intake logs the failure and still answers the provider, because the notification is already safe in Service Bus. The writer records the notification again when it processes the event, so an accepted notification is never missing from the history.
  - A duplicate is shown with status *duplicate*, linked to the first notification with the same event ID.
  - For a rejected request, only metadata is stored: provider, correlation ID, reason and time. The body is never stored, because its sender is unknown. The API Manager's rate limits prevent flooding the table.
- The writer owns the payment state and updates the processing status of each notification (recorded, failed, needs attention, dead-lettered).
- Azure SQL authentication uses each service's managed identity, with no passwords.
- Schema changes are versioned migrations that live with the code and run in the deployment pipeline.
- A scheduled job deletes rows older than the retention periods of assumption 9 in [`ASSUMPTIONS.md`](../ASSUMPTIONS.md).
- The tier, sizing and cost are described in [`AZURE_SERVICES.md`](../infra/AZURE_SERVICES.md).

## Consequences

- Positive:
  - Every pull request proves, by running tests, that the signature verification matches the providers' test vectors and that the mappings produce the expected canonical events.
  - No cold start against provider deadlines.
  - The database enforces deduplication and safe state changes, instead of relying only on application code.
  - The intake does not depend on the database, so the database is never a reason to reject a notification.
  - Every notification, including duplicates and rejected requests, is visible to Operations in one place (FR7).
- Negative:
  - If the database is unavailable at the exact moment a duplicate or a rejected request arrives, that request is missing from the history. It still appears in logs and metrics.
  - Meridian gets a second integration technology (Java services next to Mule applications) that the team must learn and operate.
  - Always-running replicas and a database have a fixed monthly cost, even with no traffic.
  - With a single writer replica, a restart or a deployment pauses writes to Salesforce for a short time (seconds to a few minutes).
  - The integration store adds a component to secure, back up and keep consistent with Salesforce.
  - Retention cleanup and schema migrations must be built and maintained.
- Risks and how they are mitigated:
  - *Team unfamiliar with Java services:* the services follow standard Spring Boot conventions, and the `practical/README.md` documents how to build, test and run them.
  - *Writer pause during restarts:* messages wait in Service Bus, and a pause of a few minutes stays within the 5-minute freshness target for most payments.
  - *Database drift from Salesforce:* the nightly reconciliation of ADR-0001 reports differences.
  - *Growth beyond the current volume:* the volume could grow several times before Azure SQL needs a larger tier; the decision is reviewed if a much larger volume is expected.
