# Operations — Premium Payment Notifications

How the integration of [`SOLUTION_DESIGN.md`](../design/SOLUTION_DESIGN.md) is observed in production: what we promise per country, what we measure, and who is called when it breaks.

## SLIs and SLOs per country

Each SLO is measured separately for Mexico, Colombia and Chile over a rolling 28-day window, so a problem in one country cannot hide behind the volume of the others (as in INC-0412).

| SLO | SLI (good events / valid events) | Target | Source |
|---|---|---|---|
| **Freshness, 5 minutes** (NFR2) | Accepted notifications whose payment state is confirmed by Salesforce within 5 minutes of `receivedAt` / accepted notifications, except duplicates and stale ones | **95%** | Writer metric at each Salesforce confirmation, plus a job every 5 minutes that counts accepted notifications still not in Salesforce |
| **Freshness, 1 hour** (NFR2) | Same, within 1 hour | **99.5%** | Same |
| **Durability** (NFR3) | Accepted notifications that reach a final or visible state within 24 hours / notifications answered `2xx` | **100%**: zero lost | Daily query of the store; nightly reconciliation with Salesforce (ADR-0001) |

- Duplicates and stale notifications are excluded from freshness: they change nothing in Salesforce. Rejected requests are excluded everywhere: they were never accepted.
- Parked notifications and payments that need attention **count as bad** freshness events once they pass the threshold. For the customer the payment is not visible, whatever the reason.
- **Final or visible states** for durability: applied, duplicate, stale, parked, needs attention, dead-lettered. A notification still `RECEIVED` after 24 hours, or an applied payment that the nightly reconciliation does not find in Salesforce, counts as lost. Every lost notification is an incident.
- **Error budget:** 5% and 0.5% of a country's notifications in 28 days. The expected Salesforce outages use about 3 of the 5 points of the 5-minute budget (SOLUTION_DESIGN.md, section 6), so a budget burning faster than that means our own problem.

## Instrumentation

**How the correlation ID travels.** The API Manager assigns `X-Correlation-ID` to each request, before the body is read.

1. **Intake:** puts it in the logging context (MDC), the `notification` row, the canonical event (`correlationId`) and the Service Bus message properties.
2. **Writer:** reads it from the message, puts it in its logging context and writes it in the log line of each Salesforce batch, next to each record's result.
3. **Salesforce link:** Salesforce has no field for it. The link is `Payment_Key__c` and `Last_Event_Id__c`, which lead back to the `payment` and `notification` rows.
4. **Distributed trace:** the Azure SDKs propagate the W3C trace context through Service Bus, so Application Insights shows one trace from the webhook to the Salesforce call, with the correlation ID as a custom dimension.

An operator who has any identifier (provider transaction ID, policy number, correlation ID) finds the notification with the Operations API, and from its correlation ID the logs and the trace.

**What we log.** Structured JSON, one line per step and outcome, with identifiers only: correlation ID, provider, country, event ID, payment key, provider event ID, canonical status, processing outcome and reason. For Salesforce calls: batch size, duration, HTTP status and error code per record.

**What we trace.** Spans for the intake request, the store insert and the publish; the writer's message processing, its database transaction and each Salesforce call.

**What we measure** (with `country` and `provider` dimensions):

| Area | Metrics |
|---|---|
| Intake | Requests by outcome (accepted, duplicate, parked, rejected, `503`); response time p95 against each provider's deadline |
| Freshness | Time from `receivedAt` to Salesforce confirmation; age of the oldest payment not yet in Salesforce |
| Salesforce | Calls in the rolling 24 h against the budget; responses by status and error code; circuit breaker state |
| Queues | Active and dead-lettered messages per subscription and queue; retry queue depth |
| Work queues | Payments needing attention; parked notifications; partial refunds pending review |

**What we never log or send as telemetry** (NFR6):

- Payer name, national ID, email or phone, and payment tokens.
- Request or response bodies, and the parked payload: payloads stay in the store.
- Signature headers, webhook secrets, Salesforce tokens or client secrets.
- **Policy numbers.** They identify a person through Salesforce; Operations reads them through the Operations API, where access is controlled and audited. The API Manager masks the `policyNumber` query parameter in its own logs.

## Alerts

| Alert | Threshold | Severity | Who is called |
|---|---|---|---|
| Dead-lettered messages | Any message in any dead-letter queue, 5-minute check | Page | On-call engineer |
| Salesforce data errors | `400` errors in one country: more than 20 in 15 minutes, or more than 2% of that country's writes | Page | On-call engineer |
| Parked notifications | More than 20 in one country in 1 hour | Page | On-call engineer |
| Payments waiting for Salesforce | Oldest payment not in Salesforce older than 30 minutes, per country | Page | On-call engineer |
| Freshness budget burning | 5-minute SLI of a country below 80% over the last hour: the budget is burning 4 times faster than allowed | Page | On-call engineer |
| Salesforce unavailable | Circuit breaker open for more than 15 minutes | Page | On-call engineer; Salesforce team informed |
| API limit | Any `403 REQUEST_LIMIT_EXCEEDED` | Page | On-call engineer and Salesforce team |
| API budget | 60% of the rolling 24 h budget (at 80% replays pause automatically) | Notify | Squad channel; page at 80% |
| Intake failing | `503` above 1% of requests for 5 minutes | Page | On-call engineer |
| Intake slow | p95 response time above 2 seconds for 10 minutes (Andina's deadline is 5 s) | Page | On-call engineer |
| Signatures rejected | More than 50% of a provider's requests rejected for 15 minutes (a wrong or rotated secret) | Page | On-call engineer |
| Writer stopped | Messages waiting in its subscription and none processed for 10 minutes | Page | On-call engineer |
| Lost notification | Durability check finds any | Page | On-call engineer |
| Payment needs attention | Any payment marked *needs attention* (data error, or retries exhausted) | Notify | Operations work queue, business hours |
| Work queues | Parked notifications, payments needing attention and partial refunds pending review | Daily summary | Operations, business hours |

- **Pages** go to the on-call engineer of the Payments Integration squad through the Azure Monitor action group. Each alert includes the Operations API query that lists the affected notifications.
- When customers are affected, the on-call engineer informs the contact-center lead of the country, so agents can answer callers.
- Alerts are created by the infrastructure code for every country and environment, never by hand (INC-0412: only Mexico had a dead-letter alert). The dead-letter alert is already in `infra/`; the others are added there with the services' custom metrics.
