// test: same network shape as prod (Service Bus Premium behind a private endpoint), so the
// deployment and the connectivity are tested before prod. Smaller and not zone redundant.
// IDs, address ranges and e-mail are fictional placeholders for the platform's real values.
using '../main.bicep'

param environmentName = 'test'

param vnetAddressPrefix = '10.41.0.0/22'
param appsSubnetPrefix = '10.41.0.0/24'
param privateEndpointsSubnetPrefix = '10.41.1.0/27'

param serviceBusSku = 'Premium'

param containerRegistryId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-shared/providers/Microsoft.ContainerRegistry/registries/crmeridianplatform'
// Overridden by the CD pipeline with the image built from the commit being deployed.
param intakeImage = 'crmeridianplatform.azurecr.io/payment-intake:placeholder'
param writerImage = 'crmeridianplatform.azurecr.io/payment-writer:placeholder'
param intakeMinReplicas = 1
param intakeMaxReplicas = 4
param apiManagerAddressRanges = [
  '203.0.113.16/28'
]
param zoneRedundant = false

param sqlSkuName = 'GP_S_Gen5_1'
param sqlAdminGroupName = 'sg-payments-integration-test-dba'
param sqlAdminGroupObjectId = '00000000-0000-0000-0000-000000000002'

param logAnalyticsWorkspaceId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-monitoring/providers/Microsoft.OperationalInsights/workspaces/log-meridian-platform-nonprod'
param alertEmail = 'payments-integration-test@meridian.example'
