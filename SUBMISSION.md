# Submission

## Candidate

| Field | Value |
|---|---|
| Candidate ID (provided by your recruiter) | ngarciarios2001@gmail.com |
| Final commit SHA | |
| Date and time of submission (with time zone) | |

## Time spent

| Part | Approximate time |
|---|---|
| A. Integration and data design | 10 h |
| B. Integration component | 1 h |
| C. Azure IaC and CI/CD | 1 h |
| D. Operations | 30 min |
| E. Code review | 30 min |
| F. Responsible AI | 1 h |
| **Total** | **14 h** |

## Stack

- Practical part (Part B): Java 21 + Spring Boot 3.5.16 + JUnit 5, built with the Maven Wrapper (justified in ADR-0002)
- Infrastructure as code: Bicep (CLI 0.45.15)
- Pipeline: GitHub Actions
- CI run link (optional): not included; CI runs on pull requests

## Where to start reading

1. [`docs/design/SOLUTION_DESIGN.md`](docs/design/SOLUTION_DESIGN.md): the whole flow, with error handling and the capacity figures.
2. [`practical/README.md`](practical/README.md), then [`IntakeService.java`](practical/src/main/java/com/meridian/payments/intake/webhook/IntakeService.java) and the tests that use the providers' official signature vectors.
3. [`docs/operations/INC-0412-RESPONSE.md`](docs/operations/INC-0412-RESPONSE.md): the design applied to a real incident.

## Known gaps and what I would do next

Most of my time went to Part A, where I made the design decisions. With more time, I would have spent longer on Parts B to F, reviewing in more depth that everything is correct.

In this order:

1. **Before any deployment:** replace the in-memory stubs in `practical/` with the Service Bus, Azure SQL and Key Vault adapters, and add the production wiring (managed identities, health probes, JSON logs, metrics). The CD pipeline is defined, but the image must not be deployed until then.
2. **The Salesforce writer, the Operations API and Austral (Chile)** are design only, as Part B's scope sets.
3. **Database migrations and users** as a Container Apps job inside the VNet, and Key Vault roles per secret instead of vault-wide.
4. **A load test** at 40 requests per second against each provider's response deadline.
5. **The other alerts of `OPERATIONS.md` in `infra/`**, once the services emit their custom metrics; only the dead-letter alert is there today.
6. **Smaller items:** two copies of an event arriving at the same instant can both be recorded as `RECEIVED`; actions are pinned by tag, not by commit SHA; no synthetic end-to-end check after deploying.

Details are in [`practical/README.md`](practical/README.md) and [`infra/README.md`](infra/README.md).
