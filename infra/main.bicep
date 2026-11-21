// Premium Payment Notifications: Azure resources of one environment (dev, test or prod).
// Design: docs/design/SOLUTION_DESIGN.md. Services and SKUs: docs/infra/AZURE_SERVICES.md.
// Deployed into an existing resource group: az deployment group create -g <rg> -p environments/<env>.bicepparam

targetScope = 'resourceGroup'

@description('Environment name, used in resource names and tags.')
@allowed([
  'dev'
  'test'
  'prod'
])
param environmentName string

@description('One Azure region serves the three countries (SCENARIO.md).')
param location string = resourceGroup().location

@description('Short prefix of the workload in resource names.')
@maxLength(5)
param namePrefix string = 'ppn'

// ---- Network ----

param vnetAddressPrefix string
@description('Subnet of the Container Apps environment (at least /27, delegated to Microsoft.App/environments).')
param appsSubnetPrefix string
param privateEndpointsSubnetPrefix string

// ---- Messaging ----

@description('Premium is needed for private endpoints and zone redundancy; Standard keeps dev cheap.')
@allowed([
  'Standard'
  'Premium'
])
param serviceBusSku string

@description('Principal IDs of the downstream consumers (Finance, Notifications). Each one may only read its own subscription. Empty to skip.')
param consumerPrincipalIds object = {
  finance: ''
  notifications: ''
}

// ---- Compute ----

@description('Resource ID of the platform container registry that holds the images.')
param containerRegistryId string

@description('Full image reference of the intake, set by the CD pipeline (for example myregistry.azurecr.io/payment-intake:<git sha>).')
param intakeImage string

@description('Full image reference of the writer.')
param writerImage string

@minValue(1)
param intakeMinReplicas int
param intakeMaxReplicas int

@description('Address ranges of the API Manager. Only these may call the intake and the Operations API (trust boundary B2).')
param apiManagerAddressRanges array

param zoneRedundant bool

// ---- Data ----

@description('Azure SQL Database SKU, for example GP_S_Gen5_1 (serverless) or GP_Gen5_2 (provisioned).')
param sqlSkuName string

@description('Entra ID group that administers the SQL server. There is no SQL login or password.')
param sqlAdminGroupName string
param sqlAdminGroupObjectId string

// ---- Observability ----

@description('Resource ID of the existing platform Log Analytics workspace (SCENARIO.md).')
param logAnalyticsWorkspaceId string

@description('E-mail of the on-call group that receives the alerts.')
param alertEmail string

var baseName = '${namePrefix}-${environmentName}'
var suffix = uniqueString(resourceGroup().id)
var tags = {
  workload: 'premium-payment-notifications'
  environment: environmentName
}

module identities 'modules/identities.bicep' = {
  name: 'identities'
  params: {
    baseName: baseName
    location: location
    tags: tags
  }
}

module network 'modules/network.bicep' = {
  name: 'network'
  params: {
    baseName: baseName
    location: location
    tags: tags
    vnetAddressPrefix: vnetAddressPrefix
    appsSubnetPrefix: appsSubnetPrefix
    privateEndpointsSubnetPrefix: privateEndpointsSubnetPrefix
    serviceBusPrivateEndpoint: serviceBusSku == 'Premium'
  }
}

module monitoring 'modules/monitoring.bicep' = {
  name: 'monitoring'
  params: {
    baseName: baseName
    location: location
    tags: tags
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    alertEmail: alertEmail
    publisherPrincipalIds: [
      identities.outputs.intakePrincipalId
      identities.outputs.writerPrincipalId
    ]
  }
}

module serviceBus 'modules/servicebus.bicep' = {
  name: 'servicebus'
  params: {
    name: 'sb-${baseName}-${suffix}'
    location: location
    tags: tags
    sku: serviceBusSku
    zoneRedundant: zoneRedundant
    privateEndpointSubnetId: network.outputs.privateEndpointsSubnetId
    privateDnsZoneId: network.outputs.serviceBusDnsZoneId
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    actionGroupId: monitoring.outputs.actionGroupId
    intakePrincipalId: identities.outputs.intakePrincipalId
    writerPrincipalId: identities.outputs.writerPrincipalId
    consumerPrincipalIds: consumerPrincipalIds
  }
}

module keyVault 'modules/keyvault.bicep' = {
  name: 'keyvault'
  params: {
    name: take('kv-${baseName}-${suffix}', 24)
    location: location
    tags: tags
    privateEndpointSubnetId: network.outputs.privateEndpointsSubnetId
    privateDnsZoneId: network.outputs.keyVaultDnsZoneId
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    secretReaderPrincipalIds: [
      identities.outputs.intakePrincipalId
      identities.outputs.writerPrincipalId
    ]
  }
}

module sql 'modules/sql.bicep' = {
  name: 'sql'
  params: {
    serverName: 'sql-${baseName}-${suffix}'
    location: location
    tags: tags
    skuName: sqlSkuName
    zoneRedundant: zoneRedundant
    adminGroupName: sqlAdminGroupName
    adminGroupObjectId: sqlAdminGroupObjectId
    privateEndpointSubnetId: network.outputs.privateEndpointsSubnetId
    privateDnsZoneId: network.outputs.sqlDnsZoneId
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
  }
}

module registryAccess 'modules/registry-access.bicep' = {
  name: 'registry-access'
  scope: resourceGroup(split(containerRegistryId, '/')[2], split(containerRegistryId, '/')[4])
  params: {
    registryName: last(split(containerRegistryId, '/'))
    pullerPrincipalIds: [
      identities.outputs.intakePrincipalId
      identities.outputs.writerPrincipalId
    ]
  }
}

module containerApps 'modules/containerapps.bicep' = {
  name: 'containerapps'
  dependsOn: [
    registryAccess
  ]
  params: {
    baseName: baseName
    location: location
    tags: tags
    zoneRedundant: zoneRedundant
    appsSubnetId: network.outputs.appsSubnetId
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    registryServer: '${last(split(containerRegistryId, '/'))}.azurecr.io'
    apiManagerAddressRanges: apiManagerAddressRanges
    intakeImage: intakeImage
    intakeIdentityId: identities.outputs.intakeId
    intakeClientId: identities.outputs.intakeClientId
    intakeMinReplicas: intakeMinReplicas
    intakeMaxReplicas: intakeMaxReplicas
    writerImage: writerImage
    writerIdentityId: identities.outputs.writerId
    writerClientId: identities.outputs.writerClientId
    serviceBusHostName: serviceBus.outputs.hostName
    keyVaultUri: keyVault.outputs.uri
    sqlServerHostName: sql.outputs.serverHostName
    sqlDatabaseName: sql.outputs.databaseName
    appInsightsConnectionString: monitoring.outputs.appInsightsConnectionString
  }
}

output intakeUrl string = containerApps.outputs.intakeUrl
output writerUrl string = containerApps.outputs.writerUrl
output serviceBusNamespace string = serviceBus.outputs.name
output keyVaultName string = keyVault.outputs.name
output sqlServerName string = sql.outputs.serverName
