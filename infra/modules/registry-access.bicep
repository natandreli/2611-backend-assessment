// Pull-only access to the platform's existing container registry, deployed in the registry's
// resource group. Nothing can push with these identities.

param registryName string
param pullerPrincipalIds array

var acrPull = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '7f951dda-4ed3-4680-a7ca-43fe172d538d')

resource registry 'Microsoft.ContainerRegistry/registries@2025-04-01' existing = {
  name: registryName
}

resource pullers 'Microsoft.Authorization/roleAssignments@2022-04-01' = [
  for principalId in pullerPrincipalIds: {
    scope: registry
    name: guid(registry.id, principalId, acrPull)
    properties: {
      roleDefinitionId: acrPull
      principalId: principalId
      principalType: 'ServicePrincipal'
    }
  }
]
