// prod: zone redundant, two intake replicas always running, provisioned SQL (no auto-pause).
// IDs, address ranges and e-mail are fictional placeholders for the platform's real values.
using '../main.bicep'

param environmentName = 'prod'

param vnetAddressPrefix = '10.42.0.0/22'
param appsSubnetPrefix = '10.42.0.0/24'
param privateEndpointsSubnetPrefix = '10.42.1.0/27'

param serviceBusSku = 'Premium'

param containerRegistryId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-shared/providers/Microsoft.ContainerRegistry/registries/crmeridianplatform'
// Overridden by the CD pipeline with the image built from the commit being deployed.
param intakeImage = 'crmeridianplatform.azurecr.io/payment-intake:placeholder'
param writerImage = 'crmeridianplatform.azurecr.io/payment-writer:placeholder'
param intakeMinReplicas = 2
param intakeMaxReplicas = 10
param apiManagerAddressRanges = [
  '203.0.113.32/28'
]
param zoneRedundant = true

param sqlSkuName = 'GP_Gen5_2'
param sqlAdminGroupName = 'sg-payments-integration-prod-dba'
param sqlAdminGroupObjectId = '00000000-0000-0000-0000-000000000003'

param logAnalyticsWorkspaceId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-monitoring/providers/Microsoft.OperationalInsights/workspaces/log-meridian-platform-prod'
param alertEmail = 'payments-integration-oncall@meridian.example'
