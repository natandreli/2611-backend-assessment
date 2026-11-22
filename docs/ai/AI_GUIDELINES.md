# AI assistants in the Payments Integration squad — guidelines

A proposal for how the squad uses AI assistants in daily work. The aim is to keep the speed they give us without risking what this integration protects: payments, customers' coverage and payer personal data.

## 1. What is allowed

**Allowed, with the tools approved by Meridian** (enterprise agreements: our data is not used for training and its retention is limited by contract):

- Writing and refactoring code, tests, infrastructure code, pipelines and documentation.
- Explaining code, specifications and errors; proposing designs and their trade-offs.
- Reviewing our own pull requests before asking a teammate.
- Analyzing logs, metrics or incidents **only after removing** everything listed below.

**Not allowed:**

- Tools that are not on the approved list, including personal accounts and browser extensions.
- Letting an assistant act on shared environments: it can propose a command for test or prod, a person runs it.
- Merging AI-generated changes without the review described in section 2.

**Never shared with an AI tool**, even an approved one:

| Never | Use instead |
|---|---|
| Secrets: webhook secrets, Salesforce client secrets, tokens, connection strings, Key Vault values | Placeholder values; the providers' published sandbox secrets |
| Payer personal data: names, national IDs (RUT, cédula), emails, phones, payment tokens | The samples in `reference/providers/` or synthetic data |
| Production payloads, dead-letter messages, logs or database extracts | A sanitized excerpt with identifiers only, as in our logs |
| Salesforce data (policies, customers) | Field names and the data model, not records |
| Material under NDA from providers or partners, unless the agreement allows it | A summary in our own words |

When in doubt, do not paste it and ask the squad lead.

## 2. How AI-generated code and tests are validated before merge

AI-generated code goes through the same review as any other code, plus four rules:

1. **The author owns every line.** If you cannot explain why a line is there, it does not go in. "The assistant wrote it" is not an answer in a review.
2. **Tests must prove behavior, not only pass.** Review generated tests for what they assert: a test that only checks that an object is not null, or asserts that errors are swallowed, is rejected (PR-214 had both). Each change to the flow has tests for its failure paths: duplicates, out-of-order notifications, a provider or Salesforce unavailable.
3. **Critical code needs external evidence and a second reviewer.** Signature verification, cryptography, personal-data handling and money arithmetic are tested against the providers' official test vectors and the specification, never against values the assistant produced. A second squad member reviews them.
4. **Verify what the assistant claims exists.** Libraries, versions, API versions, configuration properties and Azure features are checked in the official documentation or the package registry before use; a suggested dependency that does not exist, or a look-alike name, is a supply-chain risk.

The CI gates apply unchanged: build and tests, CodeQL, dependency review, secret scanning and image scanning.

## 3. How AI use is disclosed in pull requests

The pull request template has an **AI assistance** section, filled in by the author:

- Which tool and for which part (for example, "generated the mapper and its tests; I rewrote the amount conversion").
- How the output was validated (tests added, vectors used, documentation checked).
- Anything the reviewer should look at with extra care.

Commits with significant AI-generated content carry a `Co-Authored-By` trailer for the tool. Disclosure is not a judgment of quality; it tells the reviewer where to look, and it helps the squad learn which uses work.

## 4. When one of our services calls an LLM

Example: Operations asks for a feature that summarizes the dead-lettered payment notifications with an LLM. Before any such feature is built, these controls are the minimum:

| Control | What it means for the dead-letter summary |
|---|---|
| **Approved model in our tenant** | Azure OpenAI in Meridian's subscription, reached through a private endpoint with a managed identity; no data retention, no training on our data, a region allowed by each country's data protection law. |
| **Data minimization** | Send only what the summary needs: event ID, country, provider, status, error code, reason. No personal data, no full payloads. A redaction step with its own tests runs before every call, even if the payload is supposed to be clean. |
| **Untrusted input** | Fields such as the payment reference are typed by the payer. They are passed as delimited data, never as instructions; the model has no tools and no access to other systems, so the worst a crafted reference can do is distort a summary, which is why the summary is only advice and cites its sources. |
| **Read-only, human decides** | The summary is advice. It never replays, deletes or edits messages and never writes to Salesforce; replays stay in the Operations API, with the same checks and approvals as today. |
| **Facts come from code** | Counts, time ranges and groupings are computed by our code and given to the model; the summary cites the message IDs it is based on, so an operator can check it. |
| **Clear labeling and audit** | The output is marked as AI-generated. Requests are logged with the user, the model version and the message IDs (not the prompt text), with the same access rules as other operational data. |
| **Quality before release** | An evaluation set built from synthetic dead-letter cases (including INC-0412-like ones) is run on every prompt or model change; the feature is released only when its summaries match the facts. |
| **Not on the critical path** | If the LLM is slow, unavailable or over budget, Operations still sees the dead-lettered messages through the Operations API; nothing in the payment flow depends on it. Calls have timeouts, rate limits and a monthly cost limit. |
