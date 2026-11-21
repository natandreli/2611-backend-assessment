# ADR-0003: Message broker between the intake and its consumers

- **Status:** Accepted
- **Date:** 2026-11-20

## Context

The intake must store every canonical event durably before answering the provider (NFR3), and the canonical event must reach several independent consumers: the writer that records payments in Salesforce, and the Finance and Notifications systems, which subscribe on their own (FR6). The task asks for the canonical event to be published "on a queue or topic", on Azure.

[ADR-0001](0001-salesforce-integration.md) defines how payments are written to Salesforce. That design relies on the following broker capabilities:

| Capability | Why the design needs it | Requirement |
|---|---|---|
| Publish once, deliver to several independent consumers | The writer, Finance and Notifications each need their own copy, and new consumers must be added without changing the intake | FR6, NFR9 |
| Durable storage until a consumer confirms processing | Messages must survive a Salesforce outage or a restart of our services | NFR3 |
| Scheduled delivery | Retrying a record "in 30 seconds" with exponential backoff, instead of redelivering it immediately as v1 did in INC-0412 | NFR5 |
| Dead-letter queue | Moving messages that cannot be processed out of the flow, so they can be inspected and replayed | FR7 |
| Duplicate detection by message ID | Discarding provider retries (≈ 3% of notifications) before they reach consumers | FR4, NFR1 |
| Ordered, one-at-a-time processing per key | Two notifications for the same payment must never be processed in parallel, or a stale state could overwrite a newer one | FR5 |

### Terms used in this ADR

| Term | Meaning |
|---|---|
| Topic and subscription | A topic receives each message once; every subscription attached to it gets its own copy and is read independently. |
| Session | A Service Bus feature that groups messages by a key (here, the payment key). Messages of one session are delivered in order and to one consumer at a time. |
| Duplicate detection | Service Bus discards a message whose `MessageId` was already received within a configurable time window (up to 7 days). |
| Partition (Event Hubs) | An ordered log that a consumer reads by keeping track of its own position (offset). |

## Options considered

### Option 1 — Azure Service Bus, topic with subscriptions (chosen)

The intake publishes each canonical event to a Service Bus topic. Each consumer has its own subscription and receives its own copy, which stays in the broker until the consumer confirms it was processed.

- Pros:
  - Supports every capability in the table: topics and subscriptions, scheduled messages, built-in dead-letter queues, duplicate detection and sessions.
  - Designed for business transactions, where each message matters individually.
  - Already used by v1 of the integration (INC-0412), so Operations knows how to monitor and operate it.
- Cons:
  - More expensive than Storage Queues.
  - Network isolation through private endpoints requires the Premium tier, which has a fixed monthly cost.

### Option 2 — Azure Event Grid

The intake publishes each canonical event to an Event Grid topic, and Event Grid pushes it to an HTTP endpoint of each subscriber.

- Pros:
  - Pay per event, with no fixed cost.
  - Pushes events to subscribers and retries failed deliveries for up to 24 hours.
- Cons:
  - No scheduled delivery, so the writer's backoff would need another component.
  - No sessions: two events for the same payment can be delivered in parallel.
  - Dead-lettered events go to a separate storage account and must be replayed with custom tooling.
  - Designed to notify that something happened, not to carry business transactions that must be processed exactly once.

### Option 3 — Azure Event Hubs

The intake appends each canonical event to an event hub, an ordered log split into partitions. Each consumer reads the log at its own pace and keeps track of how far it has read.

- Pros:
  - Very high throughput (millions of events per second) and replay by re-reading the log.
  - Ordering within a partition.
- Cons:
  - Built for telemetry and streaming, far beyond our peak of 40 messages per second.
  - No per-message dead-letter queue, scheduled delivery or duplicate detection: each consumer tracks its own position in the log, and a single failing message blocks its partition or must be handled with custom code.

### Option 4 — Azure Storage Queues

The intake puts each canonical event in a simple queue of an Azure Storage account, and a consumer reads and deletes it.

- Pros:
  - Cheapest option and very simple.
- Cons:
  - Point-to-point only: no topics, so every consumer would need its own queue and the intake would have to publish to each one, which breaks FR6 and NFR9.
  - No dead-letter queue, duplicate detection or sessions; all of them would have to be built.

## Decision

**Option 1: Azure Service Bus**, with one topic, `premium-payments`, for the canonical events of all countries.

### Why it wins in this scenario

It is the only option that provides every capability ADR-0001 relies on without custom code. The other options would require building scheduled retries, dead-lettering, deduplication or per-payment ordering ourselves, which is exactly the kind of reliability code that is hard to get right.

### How it is used

- **One topic for all countries.** The country is a property of the message, not a separate queue. Adding a fourth country does not add infrastructure to the shared flow (NFR9). Consumers that only want one country can filter their subscription by that property.
- **One subscription per consumer:** `salesforce-writer`, `finance` and `notifications`. Each consumer reads at its own pace; a slow consumer does not affect the others.
- **`MessageId` = the canonical event ID** (defined per provider in section 4 of the [solution design](../design/SOLUTION_DESIGN.md)). With duplicate detection enabled over a **3-day window**, which covers the longest provider retry period (Quetzal, 3 days), provider retries are discarded by the broker.
- **Session ID = the payment key** on the `salesforce-writer` subscription, so notifications for the same payment are processed one at a time.
- **Each subscription has its own dead-letter queue**, monitored with an alert in every environment and for every country (see INC-0412, where only Mexico had one).
- **The correlation ID travels as a message property**, so every consumer can log it (NFR7).
- **One auxiliary queue**, `salesforce-retry`, holds the writer's scheduled retries, also with sessions by payment key. It has its own dead-letter queue and alert. Notifications that cannot be mapped are kept in the integration store, not in a queue (see ADR-0002).
- The tier, sizing and cost are described in [`AZURE_SERVICES.md`](../infra/AZURE_SERVICES.md).

## Consequences

- Positive:
  - Scheduled retries, dead-lettering, deduplication and per-payment ordering are provided by the platform, which reduces the amount of reliability code we have to write and test.
  - New consumers subscribe without any change to the intake (FR6, NFR9).
  - Messages survive outages of Salesforce and of our own services (NFR3).
- Negative:
  - Higher cost than Storage Queues or Event Grid, especially the Premium tier, which the design needs for private endpoints.
  - Sessions add complexity to the writer: it must handle several sessions at once to keep throughput.
  - Duplicate detection only covers its time window. A duplicate that arrives after 3 days is not discarded by the broker.
- Risks and how they are mitigated:
  - *Duplicates outside the detection window:* the payment state machine ignores a notification that does not move the payment forward, and the Salesforce upsert by payment key is idempotent, so a late duplicate is still recorded only once.
  - *A dead-letter queue grows unnoticed (INC-0412):* every subscription's dead-letter queue has a depth alert from day one, created by the infrastructure code rather than by hand.
  - *Lock-in to a Service Bus feature set:* the intake and the writer access the broker through an interface, so the broker could be replaced without changing business logic.
