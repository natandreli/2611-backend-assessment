# Azure services

The Azure services of the design ([`SOLUTION_DESIGN.md`](../design/SOLUTION_DESIGN.md)), deployed by [`infra/`](../../infra/README.md). For each one: why it, the alternatives discarded, its tier, and how it supports reliability, security and cost (Azure Well-Architected Framework). The main choices are argued in ADR-0001, ADR-0002 and ADR-0003; this document does not repeat them.

Already provided by the platform and only referenced: the API Manager, the Log Analytics workspace, the container registry and the Entra ID tenant.

## Summary

| Service | Role in the design | dev | test | prod |
|---|---|---|---|---|
| Azure Service Bus | Topic `premium-payments`, subscriptions, retry queue, dead-letter queues | Standard | Premium, 1 messaging unit | Premium, 1 messaging unit, zone redundant |
| Azure Container Apps | Intake and writer | Consumption profile | Consumption profile | Consumption profile, zone redundant |
| Azure SQL Database | Integration store | GP serverless, 1 vCore max | GP serverless, 1 vCore max | GP provisioned, 2 vCores, zone redundant |
| Azure Key Vault | Webhook and Salesforce secrets | Standard | Standard | Standard |
| Managed identities | Identity of each service | user-assigned | user-assigned | user-assigned |
| Virtual network, private endpoints, private DNS | Network isolation of the data services | 2 private endpoints | 3 private endpoints | 3 private endpoints |
| Application Insights, Azure Monitor alerts | Telemetry and alerts, on the platform workspace | workspace-based | workspace-based | workspace-based |

## Azure Service Bus

- **Why:** the only Azure broker with topics, scheduled messages, dead-letter queues, duplicate detection and sessions together, all of which the design relies on (ADR-0003).
- **Discarded:** Event Grid (no sessions or scheduled delivery), Event Hubs (no per-message dead-lettering or duplicate detection), Storage Queues (no topics).
- **Tier:** Premium in test and prod, because private endpoints and zone redundancy require it. One messaging unit handles far more than the peak of 40 messages per second. Standard in dev, which has the same features except network isolation.
- **Reliability:** messages are stored durably until each consumer confirms them (NFR3); zone redundant in prod; dead-letter queue with an alert on every entity.
- **Security:** `disableLocalAuth`, so no SAS keys exist; Entra ID roles per entity; private endpoint, no public access (test, prod).
- **Cost:** Premium is a fixed cost per messaging unit, the largest item of the estimate. Using Standard in test would save about USD 670 per month, but test would no longer have prod's network shape.

## Azure Container Apps

- **Why:** runs the two Java services as containers without managing servers, with always-running replicas, VNet integration, managed identities and revisions (ADR-0002).
- **Discarded:** Azure Functions Consumption (cold starts against Andina's 5 s deadline), Functions Premium (similar cost, no advantage for a continuously running writer), Container Apps Express (no availability zones or Key Vault references), App Service (fewer scaling options for the same cost), AKS (cluster operations out of proportion for two services).
- **Tier:** Consumption workload profile. Intake: 0.5 vCPU, 1 GiB, 2 to 10 replicas in prod, scaling with concurrent requests. Writer: 0.5 vCPU, 1 GiB, exactly one replica.
- **Reliability:** two intake replicas spread across zones in prod; a failed replica is restarted; single-revision mode keeps the previous revision serving if a new one does not become ready.
- **Security:** user-assigned identities, no secrets in the configuration; ingress restricted to the API Manager's addresses; images run as a non-root user.
- **Cost:** pay per vCPU-second and GiB-second; always-running replicas are a fixed base, the price of no cold starts.

## Azure SQL Database

- **Why:** the store needs a unique key per payment, transactions for the state machine and several secondary indexes for Operations (ADR-0002).
- **Discarded:** Cosmos DB (uniqueness and transactions only within a partition; Operations lookups would cross partitions), Azure Database for PostgreSQL (equally valid technically, but the team knows SQL Server and Azure SQL has Entra-only authentication and serverless).
- **Tier:** General Purpose. Prod: provisioned, 2 vCores, zone redundant; the peak of 40 notifications per second means a few hundred simple queries per second, which is small for it. dev and test: serverless, up to 1 vCore, paused after an hour without connections.
- **Reliability:** zone redundant in prod; point-in-time restore for 14 days; Azure SQL availability SLA of 99.99%, which matters because the intake answers `503` while the database is unavailable.
- **Security:** Entra ID authentication only (no SQL logins or passwords); each identity has only the table permissions it needs (`infra/sql/grant-identities.sql`); private endpoint, no public access; encryption at rest; no payer personal data stored.
- **Cost:** provisioned in prod because the database is used all day; serverless in dev and test, where it is idle most of the time. Auto-pause only happens if the services' connection pools let idle connections close.

## Azure Key Vault

- **Why:** the secrets store of NFR6, with versioning, which allows two active webhook secrets during a rotation.
- **Discarded:** Container Apps secrets (no central rotation or audit), App Configuration (for configuration, not secrets).
- **Tier:** Standard. Premium adds HSM-backed keys, which the design does not use.
- **Reliability:** replicated within the region by the service; soft delete and purge protection prevent losing a secret by mistake.
- **Security:** RBAC (Key Vault Secrets User for the two services only); private endpoint, no public access; audit logs to the workspace.
- **Cost:** a few dollars per month; price per operation, and the services cache secrets.

## Managed identities

- **Why:** NFR6 requires managed identities. User-assigned, one per service, so roles can be assigned before the apps exist and each service has its own set.
- **Discarded:** system-assigned identities (created with the app, so the first deployment cannot have its roles ready), service principals with secrets (a secret to rotate and to leak).
- **Cost:** free.

## Virtual network, private endpoints and private DNS

- **Why:** trust boundary B3: Service Bus, SQL and Key Vault are reachable only from the Container Apps environment.
- **Discarded:** service endpoints and firewall rules (the services would still have public endpoints), public endpoints with Entra ID only (accepted only for Service Bus in dev).
- **Reliability:** private endpoints and private DNS zones are regional, managed services.
- **Cost:** about USD 7 per private endpoint per month plus data processed; private DNS zones are cents.

## Application Insights and Azure Monitor alerts

- **Why:** the platform already has Azure Monitor and a Log Analytics workspace (SCENARIO.md). Application Insights adds distributed tracing with the correlation ID across the intake, Service Bus and the writer (NFR7).
- **Discarded:** a separate workspace for this integration (duplicates the platform's retention and access model), a third-party monitoring tool (not in the platform).
- **Tier:** workspace-based Application Insights, pay per GB ingested in the platform workspace.
- **Reliability:** the dead-letter alert covers every topic and queue, so no country can be forgotten (INC-0412).
- **Security:** local authentication disabled: only the services' identities can send telemetry; telemetry never contains payer personal data (NFR6).
- **Cost:** driven by log volume. The services log identifiers only, one or two lines per notification.

## Monthly cost estimate (order of magnitude)

Approximate pay-as-you-go list prices in USD for one region, without reservations. They must be checked with the Azure Pricing Calculator before a budget is approved.

| Item | dev | test | prod | Assumption |
|---|---|---|---|---|
| Service Bus | 10 | 680 | 680 | Standard base fee; Premium 1 messaging unit. Operations of the peak month are within what is included |
| Container Apps | 70 | 80 | 120 | 2 to 3 replicas of 0.5 vCPU and 1 GiB running all month; burst replicas add little |
| Azure SQL | 40 | 60 | 450 | Serverless active about 40% of the time; prod provisioned 2 vCores, zone redundant, with storage and backups |
| Private endpoints and DNS | 15 | 25 | 25 | 2 or 3 endpoints |
| Key Vault | 1 | 1 | 5 | Secrets cached by the services |
| Log ingestion | 5 | 10 | 30 | Prod about 10 GB per month: about 870,000 notifications per month with request telemetry and a few log lines each, plus platform logs |
| **Total** | **≈ 140** | **≈ 860** | **≈ 1,310** | **≈ 2,300 per month for the three environments** |

- About 60% of the total is Service Bus Premium in test and prod: the price of network isolation and zone redundancy.
- Not included: the API Manager, the Log Analytics workspace, the container registry and network egress, which belong to the platform.
- The peak days do not change the order of magnitude: compute is mostly a fixed base, and the Salesforce writes are capped by the batch interval, not by volume.
