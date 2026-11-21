// Application Insights on the existing platform Log Analytics workspace, and the action group that
// pages on-call. Local authentication is disabled: the services send telemetry with their managed
// identity (Monitoring Metrics Publisher), so the connection string alone cannot be used to write.

param baseName string
param location string
param tags object
param logAnalyticsWorkspaceId string
param alertEmail string
param publisherPrincipalIds array

var metricsPublisher = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '3913510d-42f4-4e42-8a64-420c390055eb')

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: 'appi-${baseName}'
  location: location
  tags: tags
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: logAnalyticsWorkspaceId
    DisableLocalAuth: true
    IngestionMode: 'LogAnalytics'
  }
}

resource publishers 'Microsoft.Authorization/roleAssignments@2022-04-01' = [
  for principalId in publisherPrincipalIds: {
    scope: appInsights
    name: guid(appInsights.id, principalId, metricsPublisher)
    properties: {
      roleDefinitionId: metricsPublisher
      principalId: principalId
      principalType: 'ServicePrincipal'
    }
  }
]

resource onCall 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: 'ag-${baseName}-on-call'
  location: 'global'
  tags: tags
  properties: {
    groupShortName: 'ppn-oncall'
    enabled: true
    emailReceivers: [
      {
        name: 'on-call'
        emailAddress: alertEmail
        useCommonAlertSchema: true
      }
    ]
  }
}

output appInsightsConnectionString string = appInsights.properties.ConnectionString
output actionGroupId string = onCall.id
