# INC-0412 — Response

Colombian customers paid their overdue premium with Andina Pay, and their policies are still suspended. On-call response, Monday 2026-11-30 from 08:12 (Colombia time).

**Severity: raised from SEV-2 to SEV-1.** The 37 reported cases are a small part of the problem: the Colombian DLQ holds 4,198 approved payments since Saturday night (fewer distinct payments if Andina retries repeat some), none of them in Salesforce. Each one is a customer who paid and has no coverage.

## First 30 minutes

| Time | What I check or do | Why |
|---|---|---|
| 08:12–08:15 | Acknowledge, open the incident channel, tell the contact-center lead we are on it. | Agents keep receiving calls; they need to know someone owns it. |
| 08:15–08:20 | Read the snapshot by country: volume normal, no signature failures, Colombian queue empty, but **4,312 messages in the Colombian DLQ** and **43,120 Salesforce `400`** responses. Mexico and Chile normal. | Andina still delivers and we accept: the payments are lost **after** acceptance, between our queue and Salesforce, and only in Colombia. |
| 08:20–08:25 | Read dead-lettered messages and consumer logs by correlation ID: `MaxDeliveryCountExceeded`, `400 INVALID_FIELD: Foreign key external ID: 000215560 not found`, ten deliveries in about 2.5 seconds. **I do not purge or resubmit the DLQ.** | The error says which field fails and why. The DLQ is the only copy of these payments. |
| 08:25–08:32 | Compare with recent changes: Andina API 2.3 (Saturday 23:00), Salesforce release (Saturday 22:00), our v1.14.0 (Friday 18:05). | The first dead-letter is at 23:41, right after Andina's change. |
| 08:32–08:37 | Check the risks of waiting: API budget at 66% (38% normally), still far from the limit; dead-lettered messages do not expire; well-formed references keep flowing. | Nothing gets worse in the next hour, so a careful fix beats a fast one. |
| 08:37–08:42 | First status update: probable cause, real impact (thousands of payments, not 37), plan (hotfix, then replay). Ask the Salesforce team whether the 37 escalated policies can be reinstated by hand meanwhile. | Customers waiting on the phone get an answer before the fix; the later replay is an idempotent upsert, so manual work is not undone wrongly. |

## Most likely root cause

**Andina API 2.3 stopped normalizing `transaccion.referencia`, and v1 sends it to Salesforce unchanged.** The reference arrives exactly as typed by the payer (`"000215560"`, `"pol-co-000198004"`). Salesforce cannot find a policy with that external ID and answers `400 INVALID_FIELD`. The consumer retries the same message ten times in about 2.5 seconds and dead-letters it, with no alert.

Evidence:

- **Timing.** Andina 2.3 took effect on Saturday at 23:00; the first dead-lettered message is from 23:41.
- **The error.** Every dead-lettered message failed with "foreign key not found", and the samples carry exactly the formats that Andina used to normalize.
- **The numbers.** 4,312 dead-lettered messages × 10 deliveries = **43,120**, exactly the Colombian `400` count. The same retries explain the budget going from 38% to 66%.
- **The scope.** Only Colombia, the only provider that changed. Payers who kept the pre-filled reference still go through (32,880 upserts), which is why the main queue is empty.

The other changes do not fit. The Salesforce release adds a validation rule on cancelling `Policy__c`, which would fail with `FIELD_CUSTOM_VALIDATION_EXCEPTION`, not "foreign key not found". Our v1.14.0 only changed logging, a day earlier. It did make the diagnosis harder, because request payloads are no longer logged.

**How I confirm it:**

1. Count the DLQ by error: all `INVALID_FIELD` on the policy, and no reference with the format `POL-CO-` + 9 digits.
2. Apply the normalization rule (trim, upper case, prefix 9 digits with `POL-CO-`) to the dead-lettered references and look them up in Salesforce with one query per 200 references. Most should exist.
3. Check that the references upserted since Saturday 23:00 all have the right format.
4. Find the 37 reported transactions in the DLQ.
5. Confirm the change with Andina's changelog and their support.

## Remediation

**1. Stop new failures (hotfix v1.14.1, Colombia first).**
- Normalize and validate the reference in the mapping.
- A reference that is still invalid is not sent to Salesforce: it goes to the DLQ at once, with the reason, for Operations.
- The consumer no longer retries `400` data errors.
- The DLQ messages above become the hotfix's tests.

**2. Reprocess the dead-lettered messages safely**, with a replay script, after the hotfix is live:

1. **Copy, do not consume.** Peek every DLQ message into a file; the DLQ stays intact until the end.
2. **Normalize and classify** the references: valid and existing in Salesforce; valid but unknown; invalid. The last two go to Operations as a list, never guessed.
3. **One state per payment.** Group by `transaccion.id` and keep only the latest state by `fecha_transaccion`. `TRX-CO-5603318` is in the DLQ as `APROBADA` and `REVERSADA`: replaying both in the wrong order would reinstate a policy whose payment was reversed. Skip any payment whose Salesforce record already has a newer `Status_Changed_At__c`.
4. **Dry run.** Produce the counts and the list of policies that each replay would change, and review them with the Operations lead.
5. **The 37 escalated cases first.** The corrected messages go to the main queue `payments-co`, where the consumer with the hotfix writes them to Salesforce. Check that the Salesforce flow reinstates their policies, then replay the rest at a controlled rate. At most about 4,300 calls, about 3% of the budget.
6. **Remove a message from the DLQ only after its payment is confirmed in Salesforce.** Messages that fail again stay there, with the correlation ID kept for traceability.

It is safe because the upsert by `Payment_Key__c` cannot create duplicates, only the latest state per payment is sent, nothing is deleted before it is confirmed, and the dry run is reviewed first.

**3. Close.** Send the contact center the list of reinstated policies. Escalate to the business the coverage gap of customers whose policy was suspended while paid. Write the postmortem. Investigate separately the 2 Mexican dead-lettered messages.

## Prevention

What would have avoided this or caught it within minutes. Each point is part of the new design:

- **Normalize and validate provider data at the intake** (assumption 3). A reference that cannot be normalized is parked, visible to Operations, instead of failing in Salesforce.
- **Never retry data errors.** A `400` is marked *needs attention* at once. Transient errors are retried with backoff, never immediately (ADR-0001).
- **A dead-letter alert on every queue and country, created by the infrastructure code** (`infra/`), never by hand.
- **Per-country alerts on Salesforce `400` rate, parked notifications and freshness** (OPERATIONS.md). The `400` alert would have fired around 23:45 on Saturday.
- **API budget alert at 60%.** It would have flagged the retry storm on Sunday.
- **Provider changes treated as releases.** Subscribe to the providers' changelogs, put announced changes on the shared change calendar next to Salesforce releases, and add the new payload samples to the contract tests before the effective date. Andina announced 2.3 in advance.
- **Logs keep identifiers and the failure reason** while payloads stay out of them (v1.14.0 was right to remove them). The parked notification in the store keeps the unreadable value for Operations.
