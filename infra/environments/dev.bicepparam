// dev: smallest footprint. Service Bus Standard (no private endpoint), serverless SQL, one intake replica.
// IDs, address ranges and e-mail are fictional placeholders for the platform's real values.
using '../main.bicep'

param environmentName = 'dev'

param vnetAddressPrefix = '10.40.0.0/22'
param appsSubnetPrefix = '10.40.0.0/24'
param privateEndpointsSubnetPrefix = '10.40.1.0/27'

param serviceBusSku = 'Standard'

param containerRegistryId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-shared/providers/Microsoft.ContainerRegistry/registries/crmeridianplatform'
// Overridden by the CD pipeline with the image built from the commit being deployed.
param intakeImage = 'crmeridianplatform.azurecr.io/payment-intake:placeholder'
param writerImage = 'crmeridianplatform.azurecr.io/payment-writer:placeholder'
param intakeMinReplicas = 1
param intakeMaxReplicas = 2
param apiManagerAddressRanges = [
  '203.0.113.0/28'
]
param zoneRedundant = false

param sqlSkuName = 'GP_S_Gen5_1'
param sqlAdminGroupName = 'sg-payments-integration-dev-dba'
param sqlAdminGroupObjectId = '00000000-0000-0000-0000-000000000001'

param logAnalyticsWorkspaceId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-monitoring/providers/Microsoft.OperationalInsights/workspaces/log-meridian-platform-nonprod'
param alertEmail = 'payments-integration-dev@meridian.example'
