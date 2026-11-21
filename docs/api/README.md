# API contracts

| Contract | File | Used by |
|---|---|---|
| Provider webhooks (Mexico and Colombia) | [`webhooks.openapi.yaml`](webhooks.openapi.yaml) (OpenAPI 3.1) | Quetzal Pagos and Andina Pay, through the API Manager |
| Canonical payment event, version 1.0 | [`events/premium-payment.schema.json`](events/premium-payment.schema.json) (JSON Schema 2020-12) | Consumers of the Service Bus topic `premium-payments`: the Salesforce writer, Finance and Notifications |

Chile (Austral Pagos) and the Operations API are part of the design but not of these contracts yet; see [`SOLUTION_DESIGN.md`](../design/SOLUTION_DESIGN.md).

## How the webhook contract evolves without breaking providers

The providers own their payload formats; the OpenAPI document describes what they send, not what we would like them to send.

- **Tolerant reader.** The intake ignores fields it does not know, so a provider can add fields without coordination (for example, `transaccion.canal` in Andina API 2.3).
- **Provider changes are absorbed in the adapter.** All provider-specific logic lives in one adapter per provider. A change in meaning, such as Andina 2.3 no longer normalizing `transaccion.referencia`, is handled there, with tests built from the provider's new samples.
- **Never answer a valid notification with a client error.** A notification with a valid signature that does not match the contract is parked for Operations and answered with `200`. A `4xx` would only make the provider retry the same content until it gives up.
- **Endpoint URLs do not change.** Each provider has configured its URL; `/payments/v1/webhooks/{provider}` stays stable. If a provider ever publishes an incompatible version, the adapter detects the version from the payload or a header, or a second path is added and both stay active until the provider has moved.
- **Secret rotation without downtime.** Two secrets can be active at the same time, and the intake accepts either until the provider has switched.

## How the event contract evolves without breaking consumers

The version is carried in the event (`schemaVersion`), in the schema's `$id` (`…/premium-payment/v1.schema.json`) and as a Service Bus message property (`schemaVersion`).

| Change | Version | Examples | Consumers |
|---|---|---|---|
| Compatible | Minor: `1.0` → `1.1` | A new optional field; a new enum value (a new country, provider or currency) | Keep working. A new schema file is published for the minor version |
| Breaking | Major: `1.x` → `2.0` | Removing or renaming a field, changing a type or the meaning of a value, making a field required | Migrate during a transition period |

- **A minor version never breaks a consumer that follows the rules below.** A new enum value is announced before it is first published, because a consumer may need to handle it (for example, a new status).
- **A major version is published in parallel.** During a transition period agreed with the consumers, the intake publishes each event in both versions on the same topic. Each consumer filters its subscription by the `schemaVersion` property and moves to the new version when it is ready; the old version is then retired.
- **The producer validates every event against the schema before publishing**, so an event that breaks the contract, or carries a field that is not in it (for example, personal data), never reaches the topic.

What consumers must do:

- **Ignore fields they do not know**, so minor versions do not break them.
- **Be idempotent by `eventId`.** Service Bus discards duplicates within 3 days, but a later provider retry or an Operations replay can deliver the same event again.
- **Not assume ordering.** Notifications for the same payment can arrive out of order; use `status` and `statusChangedAt` to decide which one is newer (as the writer does, see section 4 of the solution design).
- **Never expect personal data.** Customer details are looked up in Salesforce by `policyNumber` (assumption 6).
