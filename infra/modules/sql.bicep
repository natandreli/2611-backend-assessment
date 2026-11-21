// Azure SQL Database, the integration store (ADR-0002). Entra ID authentication only: no SQL
// logins or passwords. The services' database users and permissions are in infra/sql/.

param serverName string
param location string
param tags object
param skuName string
param zoneRedundant bool
param adminGroupName string
param adminGroupObjectId string
param privateEndpointSubnetId string
param privateDnsZoneId string
param logAnalyticsWorkspaceId string

var databaseName = 'payments'
var isServerless = contains(skuName, '_S_')

resource server 'Microsoft.Sql/servers@2025-01-01' = {
  name: serverName
  location: location
  tags: tags
  properties: {
    minimalTlsVersion: '1.2'
    publicNetworkAccess: 'Disabled'
    administrators: {
      administratorType: 'ActiveDirectory'
      azureADOnlyAuthentication: true
      principalType: 'Group'
      login: adminGroupName
      sid: adminGroupObjectId
      tenantId: subscription().tenantId
    }
  }
}

resource database 'Microsoft.Sql/servers/databases@2025-01-01' = {
  parent: server
  name: databaseName
  location: location
  tags: tags
  sku: {
    name: skuName
  }
  properties: {
    zoneRedundant: zoneRedundant
    // Serverless (dev, test) pauses after an hour without connections; provisioned (prod) never pauses.
    autoPauseDelay: isServerless ? 60 : null
    minCapacity: isServerless ? json('0.5') : null
    requestedBackupStorageRedundancy: zoneRedundant ? 'Zone' : 'Local'
  }
}

resource backupRetention 'Microsoft.Sql/servers/databases/backupShortTermRetentionPolicies@2025-01-01' = {
  parent: database
  name: 'default'
  properties: {
    retentionDays: 14 // point-in-time restore
  }
}

module privateEndpoint 'private-endpoint.bicep' = {
  name: 'pe-sql'
  params: {
    name: 'pe-${serverName}'
    location: location
    tags: tags
    subnetId: privateEndpointSubnetId
    targetResourceId: server.id
    groupId: 'sqlServer'
    privateDnsZoneId: privateDnsZoneId
  }
}

// 2021-05-01-preview is the latest version with log category groups (allLogs, audit).
#disable-next-line use-recent-api-versions
resource diagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  scope: database
  name: 'to-log-analytics'
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        category: 'Basic'
        enabled: true
      }
    ]
  }
}

output serverName string = server.name
output serverHostName string = server.properties.fullyQualifiedDomainName
output databaseName string = database.name
