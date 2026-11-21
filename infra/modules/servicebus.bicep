// Azure Service Bus (ADR-0003): topic premium-payments with one subscription per consumer, and the
// queue salesforce-retry for the writer's scheduled retries. Access only with Entra ID identities.

param name string
param location string
param tags object

@allowed([
  'Standard'
  'Premium'
])
param sku string
param zoneRedundant bool
param privateEndpointSubnetId string
param privateDnsZoneId string
param logAnalyticsWorkspaceId string
param actionGroupId string
param intakePrincipalId string
param writerPrincipalId string
param consumerPrincipalIds object

var isPremium = sku == 'Premium'

// Built-in roles
var dataSender = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '69a216fc-b8fb-44d8-bc22-1f3c2cd27a39')
var dataReceiver = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4f6d3b9b-027b-4f4c-9142-0e5a2a2247e0')

// Shared settings of every entity: 10 deliveries before the dead-letter queue, and expired
// messages go to the dead-letter queue instead of being dropped (SOLUTION_DESIGN.md, section 5).
var maxDeliveryCount = 10
var messageTimeToLive = 'P14D'

resource namespace 'Microsoft.ServiceBus/namespaces@2026-01-01' = {
  name: name
  location: location
  tags: tags
  sku: {
    name: sku
    tier: sku
    capacity: isPremium ? 1 : null
  }
  properties: {
    disableLocalAuth: true // no SAS keys or connection strings, only managed identities (NFR6)
    minimumTlsVersion: '1.2'
    publicNetworkAccess: isPremium ? 'Disabled' : 'Enabled'
    zoneRedundant: isPremium ? zoneRedundant : false
  }
}

resource topic 'Microsoft.ServiceBus/namespaces/topics@2026-01-01' = {
  parent: namespace
  name: 'premium-payments'
  properties: {
    requiresDuplicateDetection: true
    duplicateDetectionHistoryTimeWindow: 'P3D' // longest provider retry period (Quetzal)
    defaultMessageTimeToLive: messageTimeToLive
    maxSizeInMegabytes: 5120
  }
}

resource writerSubscription 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2026-01-01' = {
  parent: topic
  name: 'salesforce-writer'
  properties: {
    requiresSession: true // SessionId = payment key: one payment at a time (FR5)
    lockDuration: 'PT1M'
    maxDeliveryCount: maxDeliveryCount
    defaultMessageTimeToLive: messageTimeToLive
    deadLetteringOnMessageExpiration: true
  }
}

resource consumerSubscriptions 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2026-01-01' = [
  for consumer in ['finance', 'notifications']: {
    parent: topic
    name: consumer
    properties: {
      lockDuration: 'PT1M'
      maxDeliveryCount: maxDeliveryCount
      defaultMessageTimeToLive: messageTimeToLive
      deadLetteringOnMessageExpiration: true
    }
  }
]

resource retryQueue 'Microsoft.ServiceBus/namespaces/queues@2026-01-01' = {
  parent: namespace
  name: 'salesforce-retry'
  properties: {
    requiresSession: true
    lockDuration: 'PT1M'
    maxDeliveryCount: maxDeliveryCount
    defaultMessageTimeToLive: messageTimeToLive
    deadLetteringOnMessageExpiration: true
  }
}

// ---- Least-privilege roles, scoped to the entity (not the namespace) ----

resource intakeSendsToTopic 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: topic
  name: guid(topic.id, intakePrincipalId, dataSender)
  properties: {
    roleDefinitionId: dataSender
    principalId: intakePrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource writerSendsToTopic 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: topic // replays of parked notifications
  name: guid(topic.id, writerPrincipalId, dataSender)
  properties: {
    roleDefinitionId: dataSender
    principalId: writerPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource writerReadsSubscription 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: writerSubscription
  name: guid(writerSubscription.id, writerPrincipalId, dataReceiver)
  properties: {
    roleDefinitionId: dataReceiver
    principalId: writerPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource writerUsesRetryQueue 'Microsoft.Authorization/roleAssignments@2022-04-01' = [
  for role in [dataSender, dataReceiver]: {
    scope: retryQueue
    name: guid(retryQueue.id, writerPrincipalId, role)
    properties: {
      roleDefinitionId: role
      principalId: writerPrincipalId
      principalType: 'ServicePrincipal'
    }
  }
]

resource consumersReadOwnSubscription 'Microsoft.Authorization/roleAssignments@2022-04-01' = [
  for (consumer, i) in ['finance', 'notifications']: if (!empty(consumerPrincipalIds[consumer])) {
    scope: consumerSubscriptions[i]
    name: guid(consumerSubscriptions[i].id, consumerPrincipalIds[consumer], dataReceiver)
    properties: {
      roleDefinitionId: dataReceiver
      principalId: consumerPrincipalIds[consumer]
      principalType: 'ServicePrincipal'
    }
  }
]

// ---- Network and observability ----

module privateEndpoint 'private-endpoint.bicep' = if (isPremium) {
  name: 'pe-servicebus'
  params: {
    name: 'pe-${name}'
    location: location
    tags: tags
    subnetId: privateEndpointSubnetId
    targetResourceId: namespace.id
    groupId: 'namespace'
    privateDnsZoneId: privateDnsZoneId
  }
}

// 2021-05-01-preview is the latest version with log category groups (allLogs, audit).
#disable-next-line use-recent-api-versions
resource diagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  scope: namespace
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
        category: 'AllMetrics'
        enabled: true
      }
    ]
  }
}

// A message in any dead-letter queue pages on-call, for every entity and therefore every country.
// INC-0412: the v1 dead-letter queue of Colombia grew for two days without an alert.
resource deadLetterAlert 'Microsoft.Insights/metricAlerts@2026-01-01' = {
  name: 'alert-${name}-dead-letters'
  location: 'global'
  tags: tags
  properties: {
    description: 'Messages in a dead-letter queue of the premium payments namespace. The EntityName dimension says which topic or queue.'
    severity: 1
    enabled: true
    scopes: [
      namespace.id
    ]
    evaluationFrequency: 'PT5M'
    windowSize: 'PT5M'
    criteria: {
      'odata.type': 'Microsoft.Azure.Monitor.SingleResourceMultipleMetricCriteria'
      allOf: [
        {
          criterionType: 'StaticThresholdCriterion'
          name: 'dead-lettered-messages'
          metricNamespace: 'Microsoft.ServiceBus/namespaces'
          metricName: 'DeadletteredMessages'
          dimensions: [
            {
              name: 'EntityName'
              operator: 'Include'
              values: [
                '*'
              ]
            }
          ]
          operator: 'GreaterThan'
          threshold: 0
          timeAggregation: 'Maximum'
        }
      ]
    }
    actions: [
      {
        actionGroupId: actionGroupId
      }
    ]
  }
}

output name string = namespace.name
output hostName string = '${namespace.name}.servicebus.windows.net'
