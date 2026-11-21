# Review — PR-214

> Line numbers are those of the **new** files (the `+` side of the diff).

## Verdict

- [ ] Approve
- [ ] Approve with comments
- [x] Request changes

## Summary for the author

Hi @dvaldes, thanks for taking Chile on before the month-end peak; the description is clear and honest about what is still pending, which made this easy to review. The structure is a good starting point: the signature check lives in its own class, you thought about idempotency and about answering Austral quickly, and there are tests.

A few things need to change before this can merge, because in this integration a lost or wrong payment changes a customer's coverage:

- **Please rotate the Austral secret today (#1).** It is a live secret and it is now in the Git history, so moving it to Key Vault later is not enough.
- Requests without a signature are accepted (#2), and the payer's personal data reaches the logs (#3).
- A notification that fails after we answer `200` is lost for good (#4). A late notification can also undo a reversal (#5), and concurrent notifications can create the same payment twice (#6).

Issues #4 to #6 come from the shape of the prototype more than from this PR. The target design solves them with a durable queue before answering, an upsert by `Payment_Key__c` and a state machine. Happy to pair on it if it helps.

## Findings

| # | Severity | File:line | Finding | Why it matters in this scenario | Suggested fix |
|---|---|---|---|---|---|
| 1 | Blocker | `src/main/resources/application.yml:19` | A live webhook secret (`aus_live_whsec_…`) is committed in plain text. | NFR6: secrets never in code or configuration files. Anyone with read access to the repository, now or later, can sign forged Austral notifications, and removing the line does not remove it from the history. | Rotate the secret with Austral now. Read it from Key Vault (or at least an environment variable fed from Key Vault) in this PR, and add secret scanning to CI. |
| 2 | Blocker | `src/main/java/com/meridian/payments/cl/AustralWebhookController.java:44` | When `X-Austral-Signature` is missing, the signature is not checked at all (`signature != null && …`, with `required = false` on line 41). | FR1. An unsigned request is processed as a real payment: anyone can mark a payment `Captured`, and the Salesforce flow reinstates the policy without the premium being paid. | Treat a missing or empty header as invalid: answer `401` and process nothing. Add tests for a missing header and a wrong signature. |
| 3 | Blocker | `src/main/java/com/meridian/payments/cl/AustralWebhookController.java:50` | The whole notification is logged at `INFO`. The record's `toString()` includes `payer` (name, RUT, email), deserialized in `AustralNotification.java:18`. | NFR6 and Chile's data protection law: payer personal data must never reach logs or telemetry. Every payment would copy it into the log platform. | Log identifiers only (`eventId`, `paymentId`, `eventType`). Remove `payer` from the record: Spring Boot's `ObjectMapper` ignores unknown fields, so it is dropped while parsing. |
| 4 | Blocker | `src/main/java/com/meridian/payments/cl/AustralWebhookController.java:53`, `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:58` | `200` is returned before anything is stored, and the work runs on an in-memory executor. Any failure is caught and only logged, so `eventId` is never saved and nothing retries it. | NFR3: an accepted notification must never be lost. Salesforce is unavailable about 3% of the time, so some Chilean payments would silently never reach Salesforce; Austral does not retry because it got `200`. A restart or deploy also loses everything still in the executor's queue. | Store the notification durably before answering, then process it from there with retries and a dead-letter path (the target design uses Service Bus). Never swallow the exception: a failed message must be retried or dead-lettered, and alert. |
| 5 | Blocker | `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:45` | An existing payment's status is overwritten with whatever arrives, without looking at `occurredAt`. | FR5, and the Austral spec says ordering is not guaranteed. A `PAYMENT_CONFIRMED` arriving after `PAYMENT_REVERSED` sets the payment back to `Captured`: the policy is reinstated although the money was returned. | Keep the current status and its `occurredAt`, and only apply a change that moves the payment forward (`Pending` < `Captured`/`Failed` < `Refunded`); ignore older or lower ones. |
| 6 | Blocker | `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:43` | Find-then-create (lines 43–47), plus the `existsById`/`save` check (lines 35 and 57), are not atomic. Two notifications of the same payment processed at the same time by the 4 threads both create a record. | FR4: one payment, one record. With two records, `findPaymentId` returns only one of them, so a later reversal updates one and the other stays `Captured`: the policy can stay reinstated after the money was returned. It also costs two API calls per notification against the shared budget (NFR5). | Upsert by the external ID `Payment_Key__c` (one call, idempotent by design, no read). Process one payment at a time (for example, a queue partitioned by payment). |
| 7 | Major | `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:68` | An unknown `eventType` is mapped to `"Pending"`. | Combined with #5, a new event type from Austral would move a captured payment back to `Pending`, a wrong status that the policy automation acts on. | Reject or park unknown types for Operations and alert; never default to a business status. |
| 8 | Major | `src/main/java/com/meridian/payments/cl/AustralWebhookController.java:40`, `src/main/java/com/meridian/payments/cl/AustralSignatureVerifier.java:25` | The HMAC is computed over a `String` decoded by Spring and re-encoded as UTF-8, not over the raw request bytes. | The spec signs the raw body. Decoding to a `String` and encoding it again does not guarantee the same bytes (it depends on the charset in `Content-Type` and on invalid sequences), so valid notifications can get `401`, and after 8 retries in 48 hours the payment is lost. | Take the body as `byte[]` and compute the HMAC over those bytes; parse JSON only after verifying. |
| 9 | Major | `src/main/java/com/meridian/payments/cl/AustralSignatureVerifier.java:26` | `expected.equals(signature)` is not a constant-time comparison. | Austral signs no timestamp, so an attacker can try as often as the rate limit allows; a timing leak helps forge a signature byte by byte. | `MessageDigest.isEqual(expected, provided)` on the decoded bytes. |
| 10 | Major | `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:48` | `policyNumber` is sent to Salesforce without validating its format. | INC-0412: unvalidated references caused 4,312 Colombian notifications to fail with `400 INVALID_FIELD`. Here the `400` would also be swallowed by #4. | Validate `POL-CL-` + 9 digits before sending; an invalid value is parked for Operations, never guessed. |
| 11 | Major | `src/main/java/com/meridian/payments/cl/AustralNotification.java:11`, `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:49` | The amount is a `double` and is rounded to two decimals. | Money in floating point is a correctness risk in a payments integration, and CLP has no minor unit: the spec sends an integer string (`"45990"`). The rounding suggests the code assumes cents. | Map `amount` as `String` and convert with `new BigDecimal(amount)`, checking the scale against the currency (0 for CLP). |
| 12 | Major | `src/main/java/com/meridian/payments/config/AsyncConfig.java:14` | `Executors.newFixedThreadPool(4)` has an unbounded queue and no metrics. | At the month-end peak, or while Salesforce answers in up to 8 s, notifications pile up in memory with no back-pressure and no visibility, which makes #4 worse. | Remove it together with #4. If an executor stays, use a bounded `ThreadPoolTaskExecutor` with metrics. |
| 13 | Major | `src/test/java/com/meridian/payments/cl/AustralPaymentServiceTest.java:35` | The tests pass but do not prove the behavior: line 35 only asserts the service is not null, `atMost(1)` on line 44 also passes if a duplicate creates a payment (`never()` is meant), and line 51 asserts that errors are swallowed, which locks in #4. There are no signature tests. | CI is green while the cases that matter here (forged requests, duplicates, out-of-order notifications, Salesforce down) are untested. | Assert outcomes with `verify(...).createPayment(...)`, `never()` and argument captors. Test the verifier with the official vectors of `WEBHOOK_SPEC.md` and the samples, plus missing and wrong signatures. Review generated tests for what they actually assert. |
| 14 | Minor | `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:32` | `@Transactional` keeps a database transaction open across the Salesforce calls. | Calls can take up to 8 s; holding a connection that long can exhaust the pool at peak. | Keep transactions around database work only; call Salesforce outside them. |
| 15 | Minor | `src/main/java/com/meridian/payments/cl/AustralWebhookController.java:49` | Invalid JSON with a valid signature throws and returns `500`. | Austral would retry the same body 8 times in 48 hours, uselessly. | Answer `200` and keep the payload (without personal data) for Operations, or at least log the `eventId` and alert. |
| 16 | Nit | `src/main/java/com/meridian/payments/cl/AustralPaymentService.java:59` | String concatenation in the log message. | Consistency with the parameterized logging used elsewhere. | `log.error("Error processing Austral event {}", eventId, e)`. |
| 17 | Nit | `src/main/java/com/meridian/payments/cl/AustralWebhookController.java:42` | The handler declares `throws Exception`. | Hides which failures are expected. | Catch the parsing exception explicitly (see #15). |

Severity scale:

| Severity | Meaning |
|---|---|
| Blocker | Must be fixed before merge: data loss, security exposure, wrong business outcome for customers, or compliance breach |
| Major | Should be fixed before merge: reliability, correctness in edge cases, or maintainability risk with real impact |
| Minor | Can be fixed in this PR or a follow-up |
| Nit | Style or naming; optional |

## What looks good

- The signature check is its own component, easy to test in isolation.
- Idempotency by `eventId` is the right key for Austral, whose retries keep the same `eventId`.
- Answering quickly and doing the slow work later is the right instinct for provider deadlines; it only needs a durable store in between.
- The PR description states the trade-offs openly (secret, Copilot-generated tests), which made the review faster.
