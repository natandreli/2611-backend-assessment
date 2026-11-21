// Azure Key Vault for the providers' webhook secrets and the Salesforce client secret (NFR6).
// Secret values are never in this code: the platform team sets and rotates them from inside the
// network (for example, the platform's agents in the VNet), because the vault has no public access.

param name string
param location string
param tags object
param privateEndpointSubnetId string
param privateDnsZoneId string
param logAnalyticsWorkspaceId string
param secretReaderPrincipalIds array

var secretsUser = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4633458b-17de-408a-b874-0445c86b69e6')

resource vault 'Microsoft.KeyVault/vaults@2025-05-01' = {
  name: name
  location: location
  tags: tags
  properties: {
    tenantId: subscription().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enableRbacAuthorization: true // no access policies; roles only
    enableSoftDelete: true
    softDeleteRetentionInDays: 90
    enablePurgeProtection: true
    publicNetworkAccess: 'Disabled'
    networkAcls: {
      defaultAction: 'Deny'
      bypass: 'None'
    }
  }
}

// Read-only access to secret values. Assigned on the vault because the secrets are created
// outside this deployment; see infra/README.md (known gaps) for per-secret scoping.
resource readers 'Microsoft.Authorization/roleAssignments@2022-04-01' = [
  for principalId in secretReaderPrincipalIds: {
    scope: vault
    name: guid(vault.id, principalId, secretsUser)
    properties: {
      roleDefinitionId: secretsUser
      principalId: principalId
      principalType: 'ServicePrincipal'
    }
  }
]

module privateEndpoint 'private-endpoint.bicep' = {
  name: 'pe-keyvault'
  params: {
    name: 'pe-${name}'
    location: location
    tags: tags
    subnetId: privateEndpointSubnetId
    targetResourceId: vault.id
    groupId: 'vault'
    privateDnsZoneId: privateDnsZoneId
  }
}

// 2021-05-01-preview is the latest version with log category groups (allLogs, audit).
#disable-next-line use-recent-api-versions
resource diagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  scope: vault
  name: 'to-log-analytics'
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'audit'
        enabled: true
      }
    ]
  }
}

output name string = vault.name
output uri string = vault.properties.vaultUri
