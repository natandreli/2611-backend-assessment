# Infrastructure as code

Azure resources of the design in [`SOLUTION_DESIGN.md`](../docs/design/SOLUTION_DESIGN.md), one deployment per environment. Why each service and its SKU: [`AZURE_SERVICES.md`](../docs/infra/AZURE_SERVICES.md). Nothing here has been deployed.

## Tool and version

**Bicep** (validated with Bicep CLI 0.45 through Azure CLI 2.88). Bicep was chosen over Terraform because the design only uses Azure, it needs no state file to store and protect, and it can be validated offline.

## Structure

```
infra/
  main.bicep                 one environment: wires the modules together
  bicepconfig.json           linter rules; security rules are errors
  environments/
    dev.bicepparam           parameters of each environment
    test.bicepparam
    prod.bicepparam
  modules/
    identities.bicep         one user-assigned managed identity per service (intake, writer)
    network.bicep            VNet, subnets, private DNS zones
    private-endpoint.bicep   private endpoint + DNS registration, reused by the three data services
    servicebus.bicep         namespace, topic premium-payments, 3 subscriptions, queue salesforce-retry,
                             dead-letter settings, per-entity roles, dead-letter alert
    keyvault.bicep           vault (RBAC, no public access), read-only role for both services
    sql.bicep                server (Entra ID only), database, backups, private endpoint
    containerapps.bicep      environment in the VNet, intake and writer apps
    monitoring.bicep         Application Insights on the platform workspace, on-call action group
    registry-access.bicep    AcrPull on the platform registry
  sql/
    grant-identities.sql     database users and permissions of the two identities
```

The resource group, the container registry, the Log Analytics workspace and the API Manager already exist at platform level (SCENARIO.md) and are passed as parameters.

## What it configures

| Requirement | Where |
|---|---|
| Dead-letter configuration | Every subscription and the retry queue: `maxDeliveryCount` 10, `deadLetteringOnMessageExpiration`, time-to-live 14 days. Duplicate detection 3 days and sessions on the writer's subscription (ADR-0003) |
| Compute | Container Apps: intake with always-running replicas (no cold start, NFR4), writer with exactly one replica (ADR-0001) |
| Secrets store | Key Vault with RBAC, purge protection and no public access. Secret **values** are set by the platform team, never in code |
| Identities and least privilege | One identity per service. Service Bus roles are scoped to the entity, not the namespace: the intake can only send to the topic; the writer can only read its subscription, send to the topic (replays) and use the retry queue; each downstream consumer can only read its own subscription. Database permissions per table in `sql/grant-identities.sql`. No connection strings or SAS keys: `disableLocalAuth` on Service Bus and Application Insights, Entra-only SQL |
| Network (trust boundaries B2, B3) | Apps accept traffic only from the API Manager's addresses. Service Bus (Premium), SQL and Key Vault only through private endpoints |
| Observability | Application Insights on the existing workspace; diagnostic logs of every resource to the workspace; alert on any dead-lettered message in any entity, which pages on-call (INC-0412). The full alert set is in `OPERATIONS.md` |

## Parameters per environment and country

| | dev | test | prod |
|---|---|---|---|
| Service Bus | Standard, public endpoint, Entra ID only | Premium, private endpoint | Premium, private endpoint, zone redundant |
| Intake replicas (min-max) | 1-2 | 1-4 | 2-10, across zones |
| Azure SQL | Serverless GP_S_Gen5_1 | Serverless GP_S_Gen5_1 | Provisioned GP_Gen5_2, zone redundant |
| Alerts to | team mailbox | team mailbox | on-call |

test has the same network shape as prod, so a deployment that works in test also works in prod.

**Per country:** the design needs no per-country resources. All countries share one topic (the country is a message property, ADR-0003), and a fourth country means a new adapter, its configuration and its Key Vault secret, not new infrastructure (NFR9). What differs per country is configuration of the intake (provider settings, secret names) and the per-country SLO alerts of `OPERATIONS.md`, which use the `country` dimension of the telemetry.

## Validation (without deploying)

| Command | Result |
|---|---|
| `az bicep lint --file infra/main.bicep` | No errors or warnings |
| `az bicep build --file infra/main.bicep` | Builds; resource types and properties checked against the current API versions |
| `az bicep build-params --file infra/environments/<env>.bicepparam` | The three environments build |

CI runs the same three commands on every pull request. `az deployment group what-if` needs an Azure subscription, so it only runs in CD.

## Prerequisites for a real deployment

- Per environment: a resource group, an Entra ID group of SQL administrators, and a managed identity for GitHub with a **federated credential** for `repo:<org>/<repo>:environment:<env>`. It needs Contributor on the resource group and permission to assign only the roles used here (Role Based Access Control Administrator with a condition), also on the registry's resource group for AcrPull.
- `prod-preview` uses a separate identity that can read and run what-if, but not deploy.
- GitHub environments `build`, `dev`, `test`, `prod-preview` and `prod` with the variables `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `RESOURCE_GROUP` and `WRITER_IMAGE` (`ACR_NAME` for `build`). These are identifiers, not secrets.
- `prod` protected with required reviewers and restricted to the `main` branch.
- The secret values in Key Vault: `quetzal-webhook-secret`, `andina-webhook-secret`, `salesforce-client-secret`, set from inside the network because the vault has no public access.

## CI gates (`.github/workflows/ci.yml`)

Each gate catches a problem that would be expensive or dangerous in this integration:

| Gate | Why it is essential here |
|---|---|
| Build and tests | The tests prove the signatures against the providers' official vectors, the mapping, duplicates and the `503` paths. A regression there loses payments or accepts forged ones |
| Bicep lint and build | A broken template is found in the pull request, not in a deployment. The linter fails on secrets in parameters or outputs |
| CodeQL | Static analysis for injection, unsafe deserialization and similar flaws in a component exposed to the internet |
| Dependency review | Blocks a pull request that adds a library with a known high or critical vulnerability |
| Secret scanning (gitleaks) | NFR6: secrets never in code. The whole history is scanned; only the providers' fictional test secrets are allowed |
| Image build and Trivy scan | The image that would run in production builds, and has no fixable high or critical vulnerability in the base image or libraries |

Dependabot (`.github/dependabot.yml`) proposes weekly updates of Maven dependencies, the base image and the actions, and they go through the same gates.

## CD controls (`.github/workflows/cd.yml`, `deploy.yml`)

- **No stored secrets.** GitHub requests an OIDC token, and each environment's identity trusts only that environment of this repository.
- **Separate identities per environment.** The dev identity cannot change test or prod.
- **Build once, promote the same image.** The image is tagged with the commit and the same tag goes to dev, test and prod.
- **Same checks as CI** run again before the image is built.
- **Infrastructure and application in one deployment.** Each environment runs what-if and then the Bicep deployment, which also creates the new app revisions. Infrastructure changes follow the same path and approvals as code.
- **Prod approval with the plan in front of the approver.** `prod-preview` runs what-if for prod with a read-only identity; `prod` waits for a required reviewer.
- **Health check before promoting.** The pipeline waits until the new intake revision is ready; otherwise the next environment is not deployed. With single-revision mode, the previous revision keeps serving if the new one never becomes ready.
- **One release at a time.** The CD concurrency group is never cancelled half-way.
- **Rollback** means deploying the previous commit again: its image is still in the registry.

## Known gaps

- **The intake image must not be deployed yet.** It uses the in-memory stubs of `practical/` (Part B scope), so a deployed intake would keep notifications only in memory and lose them on restart. Before the first deployment, the stubs are replaced by the Service Bus, Azure SQL and Key Vault adapters, and the image gets the Application Insights Java agent; the infrastructure already provides the identities, endpoints and settings they need.
- **Database schema and users.** Bicep cannot create database users, and SQL is reachable only through its private endpoint, so GitHub-hosted runners cannot run `sql/grant-identities.sql` or the schema migrations. They should run as a Container Apps job inside the VNet, triggered by the pipeline; that job is not included.
- **Key Vault roles are vault-wide.** Both identities can read every secret of the vault, because the secrets are created outside this deployment. Once they exist, the role can be assigned per secret (the intake only needs the webhook secrets, the writer only the Salesforce secret).
- **Writer image.** The writer is not implemented (design only), so its image reference comes from the `WRITER_IMAGE` variable.
- **No end-to-end check after deploying.** The apps only accept the API Manager, so the pipeline checks the revision state instead of sending a request. A synthetic signed notification through the API Manager would be the next step.
- **dev Service Bus has a public endpoint.** It accepts only Entra ID identities, but it is not network-isolated.
- **Actions are pinned by version tag**, not by commit SHA. Dependabot keeps them updated; pinning by SHA would be stricter.
- **gitleaks in an organization.** The gitleaks action needs a free license key (`GITLEAKS_LICENSE`) when the repository belongs to a GitHub organization instead of a personal account.
- **API Manager configuration** (API definitions, policies, rate limits of assumption 10) belongs to the platform and is not included.
