# Practical part — Payment notification intake (MX, CO)

The intake receives the webhooks of **Quetzal Pagos** (Mexico) and **Andina Pay** (Colombia), verifies their signature, translates them into the canonical payment event, records each notification and publishes the event. Writing to Salesforce is out of scope (design only, see [`SOLUTION_DESIGN.md`](../docs/design/SOLUTION_DESIGN.md)).

Contracts: [`webhooks.openapi.yaml`](../docs/api/webhooks.openapi.yaml) and [`premium-payment.schema.json`](../docs/api/events/premium-payment.schema.json). The choice of Java over Mule is justified in [ADR-0002](../docs/adr/0002-stack-and-data-store.md).

## Stack and versions

| | Version |
|---|---|
| Java | 21 |
| Spring Boot (web) | 3.5.16 |
| Build | Maven 3.9.16, through the Maven Wrapper (`mvnw`) |
| Tests | JUnit 5, AssertJ, Spring MockMvc (from `spring-boot-starter-test`) |
| Event validation | `com.networknt:json-schema-validator` 1.5.9 (JSON Schema 2020-12) |

## How to build and run the tests

Requires a JDK 21 (`JAVA_HOME`). Maven is downloaded by the wrapper.

```bash
cd practical
./mvnw test          # Windows: mvnw.cmd test
./mvnw spring-boot:run   # starts the intake on port 8080, with in-memory stubs and no secrets configured
```

The build copies two things from outside `practical/`, so there is a single source of truth:

- `docs/api/events/premium-payment.schema.json` → classpath `schemas/`, used at runtime to validate every event before it is published.
- `reference/providers/*/samples/*.json` → test classpath `providers/`, **byte for byte**. The repository's `.gitattributes` prevents Git from converting their line endings, which would break the official signature test vectors.

## Project structure

```
src/main/java/com/meridian/payments/intake/
  webhook/    WebhookController (POST /webhooks/{provider}, raw bytes in, empty body out)
              IntakeService     (shared flow: verify → map → record → publish → answer)
  provider/   ProviderAdapter contract, JsonProviderAdapter (parse, validate, park), HMAC and amount helpers
    quetzal/  QuetzalSignatureVerifier, QuetzalAdapter
    andina/   AndinaSignatureVerifier, AndinaAdapter, PolicyReferenceNormalizer
  event/      CanonicalEvent, PaymentStatus, CanonicalEventValidator (JSON Schema)
  port/       EventPublisher, NotificationStore, SecretProvider (interfaces of the external dependencies)
  stub/       In-memory implementations of the ports
  config/     IntakeProperties (per-provider settings), Json (shared ObjectMapper)
src/main/resources/application.yml   per-provider configuration (country, timestamp tolerance, secret name)
```

All provider-specific logic is in one adapter per provider (NFR9). Adding Austral means adding `provider/austral/`, its configuration and its tests; `IntakeService` does not change.

## Request flow

| Step | Outcome | Response |
|---|---|---|
| 1. Verify the HMAC over the raw bytes, with constant-time comparison and the timestamp window (FR1) | Invalid → recorded as `REJECTED` (metadata only, best effort) | `401` |
| 2. Translate into the canonical event and validate it against the schema (FR2) | Cannot be read or translated → recorded as `PARKED` with the payload without personal data (no payload if the body is not JSON) | `200` |
| 3. Record the notification: `RECEIVED`, or `DUPLICATE` if its event ID is already known (FR4) | Store unavailable | `503` |
| 4. Publish to the topic with MessageId = event ID, SessionId = payment key | Broker unavailable → recorded as `FAILED` | `503` |
| 5. Answer | Recorded and published | `200` |

A correlation ID that is missing, longer than 64 characters or not a plain identifier is replaced by a generated one, so it always fits the store and cannot forge log lines.

`200` is only returned once the notification is stored durably (NFR3). A duplicate is still published: if the first attempt was not published, this copy gets through; otherwise Service Bus duplicate detection discards it. Personal data (payer name, national ID, email, payment token) is dropped after the signature is verified and never reaches the event, the store or the logs (NFR6).

## How the providers' deadlines are met (NFR4)

Andina expects an answer within 5 s, Quetzal within 10 s. The request path only verifies an HMAC, maps a small JSON document, looks up the event ID, inserts one row and publishes one message; its only network calls are to the store and the broker, never to Salesforce. In production the store and Service Bus calls need client timeouts (about 2 s each) so that a slow dependency becomes a `503` and a provider retry instead of a timeout, and the intake keeps at least one replica always running to avoid cold starts (ADR-0002).

## What is stubbed and how

| Dependency | Interface | Stub | Production |
|---|---|---|---|
| Azure Service Bus topic `premium-payments` | `EventPublisher` | `InMemoryEventPublisher`: keeps the messages and, like duplicate detection, discards a repeated MessageId. Can be made unavailable | Service Bus SDK with a managed identity |
| Azure SQL, table `notification` | `NotificationStore` | `InMemoryNotificationStore`: rows by correlation ID. Can be made unavailable | JDBC with a managed identity, schema of SOLUTION_DESIGN section 7 |
| Azure Key Vault | `SecretProvider` | `ConfiguredSecretProvider`: reads `stub.secrets.<name>`, set only in `src/test/resources/application-test.yml` with the providers' fictional sandbox secrets | Key Vault SDK with a managed identity |

The API Manager is not part of this component: the tests call the intake directly and send `X-Correlation-ID` as the API Manager would.

## What the tests prove

75 tests, all passing (`./mvnw test`).

| Behavior | Tests |
|---|---|
| **FR1** Signatures: every official test vector of both providers is accepted; a body changed by one byte, another secret, a changed Andina timestamp, a non-Base64 signature and malformed headers are rejected; Quetzal multiple `v1` and two active secrets (rotation); timestamp window limits (300 s, 10 min) | `QuetzalSignatureVerifierTest`, `AndinaSignatureVerifierTest` |
| **FR2** Mapping of every sample: states, minor → major units, time zones → UTC, partial refund, reversal; invalid references, unknown states or types, invalid amounts and invalid JSON are parked; unknown fields are ignored | `QuetzalAdapterTest`, `AndinaAdapterTest` |
| **INC-0412** Andina 2.3 references: `" pol-co-000198004 "` and `"000215560"` are normalized; `"mi seguro del carro"` and other invalid values are parked, never guessed | `AndinaAdapterTest` |
| **FR4** An Andina retry with a new `notificacion_id` and a Quetzal redelivery are recorded as `DUPLICATE` and reach the topic once; each lifecycle state is its own event | `WebhookFlowTest.Duplicates` |
| **NFR3** `503` when the store or the broker is unavailable; nothing is lost: the provider's retry after recovery is published | `WebhookFlowTest.Unavailable` |
| **NFR6** No payer personal data in the event, the parked payload, the store or the logs | adapter tests, `WebhookFlowTest.noPersonalDataAnywhere` |
| Invalid signature answers `401` even when the store is down; parking answers `503` when the store is down, because the store is the parked notification's only copy | `WebhookFlowTest` |
| An invalid correlation ID (too long, or with a line break) is replaced and never logged | `WebhookFlowTest.Accepted` |

## Known gaps

- **Stubs only.** No Service Bus, SQL or Key Vault code; the interfaces are where those SDKs plug in, with the timeouts described above.
- **No load or deadline tests.** The request does no slow work (no Salesforce call), but response time under 40 requests per second is not measured here.
- **Concurrent duplicates.** Two copies of the same event arriving at the same instant can both be recorded as `RECEIVED`. Service Bus duplicate detection still publishes one, and the writer's state machine makes the second harmless; only the history label differs.
- **No body size limit** is set in the application; the API Manager is expected to enforce one.
- **Austral (Chile)** and the **Operations API** are not implemented (design only).
- **Production wiring** (managed identities, health probes, structured JSON logs with the correlation ID, metrics) is not included.
