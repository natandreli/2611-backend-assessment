// Container Apps environment (in the VNet) with the intake and the writer (ADR-0002).
// Both apps authenticate to Azure with their user-assigned identity; no secrets are stored here.

param baseName string
param location string
param tags object
param zoneRedundant bool
param appsSubnetId string
param logAnalyticsWorkspaceId string
param registryServer string
param apiManagerAddressRanges array

param intakeImage string
param intakeIdentityId string
param intakeClientId string
param intakeMinReplicas int
param intakeMaxReplicas int

param writerImage string
param writerIdentityId string
param writerClientId string

param serviceBusHostName string
param keyVaultUri string
param sqlServerHostName string
param sqlDatabaseName string
param appInsightsConnectionString string

resource environment 'Microsoft.App/managedEnvironments@2025-07-01' = {
  name: 'cae-${baseName}'
  location: location
  tags: tags
  properties: {
    vnetConfiguration: {
      infrastructureSubnetId: appsSubnetId
      internal: false // reachable by the API Manager, restricted to its addresses below
    }
    zoneRedundant: zoneRedundant
    workloadProfiles: [
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
    // Console logs go to Azure Monitor and from there to the platform workspace (diagnostics below),
    // so no workspace key is needed.
    appLogsConfiguration: {
      destination: 'azure-monitor'
    }
  }
}

// 2021-05-01-preview is the latest version with log category groups (allLogs, audit).
#disable-next-line use-recent-api-versions
resource environmentDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  scope: environment
  name: 'to-log-analytics'
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
  }
}

// Trust boundary B2: only the API Manager may call the apps.
var ipRestrictions = [
  for (range, i) in apiManagerAddressRanges: {
    name: 'api-manager-${i}'
    action: 'Allow'
    ipAddressRange: range
  }
]

var commonEnv = [
  { name: 'SERVICEBUS_HOST', value: serviceBusHostName }
  { name: 'KEYVAULT_URI', value: keyVaultUri }
  { name: 'SQL_SERVER_HOST', value: sqlServerHostName }
  { name: 'SQL_DATABASE', value: sqlDatabaseName }
  { name: 'APPLICATIONINSIGHTS_CONNECTION_STRING', value: appInsightsConnectionString }
]

// Telemetry is sent with the app's own identity, because local authentication is disabled.
func appEnv(clientId string) array => [
  { name: 'AZURE_CLIENT_ID', value: clientId }
  { name: 'APPLICATIONINSIGHTS_AUTHENTICATION_STRING', value: 'Authorization=AAD;ClientId=${clientId}' }
]

// TCP probes: the services do not expose a health endpoint yet (practical/README.md, known gaps).
var probes = [
  {
    type: 'Liveness'
    tcpSocket: {
      port: 8080
    }
    periodSeconds: 10
  }
  {
    type: 'Readiness'
    tcpSocket: {
      port: 8080
    }
    periodSeconds: 5
  }
]

resource intake 'Microsoft.App/containerApps@2025-07-01' = {
  name: 'ca-${baseName}-intake'
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${intakeIdentityId}': {}
    }
  }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: true
        targetPort: 8080
        transport: 'http'
        allowInsecure: false
        ipSecurityRestrictions: ipRestrictions
      }
      registries: [
        {
          server: registryServer
          identity: intakeIdentityId
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'intake'
          image: intakeImage
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: concat(commonEnv, appEnv(intakeClientId))
          probes: probes
        }
      ]
      // Always-running replicas: no cold start against Andina's 5 s deadline (NFR4).
      scale: {
        minReplicas: intakeMinReplicas
        maxReplicas: intakeMaxReplicas
        rules: [
          {
            name: 'http-load'
            http: {
              metadata: {
                concurrentRequests: '50'
              }
            }
          }
        ]
      }
    }
  }
}

resource writer 'Microsoft.App/containerApps@2025-07-01' = {
  name: 'ca-${baseName}-writer'
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${writerIdentityId}': {}
    }
  }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      // Serves the Operations API, published through the API Manager.
      ingress: {
        external: true
        targetPort: 8080
        transport: 'http'
        allowInsecure: false
        ipSecurityRestrictions: ipRestrictions
      }
      registries: [
        {
          server: registryServer
          identity: writerIdentityId
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'writer'
          image: writerImage
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: concat(commonEnv, appEnv(writerClientId))
          probes: probes
        }
      ]
      // Exactly one replica: one batch every 5 s keeps the Salesforce call ceiling (ADR-0001).
      scale: {
        minReplicas: 1
        maxReplicas: 1
      }
    }
  }
}

output intakeUrl string = 'https://${intake.properties.configuration.ingress.fqdn}'
output writerUrl string = 'https://${writer.properties.configuration.ingress.fqdn}'
