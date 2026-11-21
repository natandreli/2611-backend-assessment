// One user-assigned managed identity per service, so each gets only the roles it needs (NFR6).
// User-assigned (not system-assigned) so role assignments exist before the apps are created.

param baseName string
param location string
param tags object

resource intake 'Microsoft.ManagedIdentity/userAssignedIdentities@2024-11-30' = {
  name: 'id-${baseName}-intake'
  location: location
  tags: tags
}

resource writer 'Microsoft.ManagedIdentity/userAssignedIdentities@2024-11-30' = {
  name: 'id-${baseName}-writer'
  location: location
  tags: tags
}

output intakeId string = intake.id
output intakePrincipalId string = intake.properties.principalId
output intakeClientId string = intake.properties.clientId
output writerId string = writer.id
output writerPrincipalId string = writer.properties.principalId
output writerClientId string = writer.properties.clientId
