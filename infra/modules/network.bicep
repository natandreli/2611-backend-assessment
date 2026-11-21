// Virtual network for the Container Apps environment and the private endpoints (trust boundary B3):
// Service Bus, Azure SQL and Key Vault are only reachable from inside this network.

param baseName string
param location string
param tags object
param vnetAddressPrefix string
param appsSubnetPrefix string
param privateEndpointsSubnetPrefix string

@description('Service Bus Standard (dev) does not support private endpoints.')
param serviceBusPrivateEndpoint bool

resource vnet 'Microsoft.Network/virtualNetworks@2025-05-01' = {
  name: 'vnet-${baseName}'
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: [
        vnetAddressPrefix
      ]
    }
    subnets: [
      {
        name: 'snet-apps'
        properties: {
          addressPrefix: appsSubnetPrefix
          delegations: [
            {
              name: 'container-apps'
              properties: {
                serviceName: 'Microsoft.App/environments'
              }
            }
          ]
        }
      }
      {
        name: 'snet-private-endpoints'
        properties: {
          addressPrefix: privateEndpointsSubnetPrefix
        }
      }
    ]
  }
}

var privateDnsZoneNames = union(
  [
    'privatelink${environment().suffixes.sqlServerHostname}'
    'privatelink.vaultcore.azure.net'
  ],
  serviceBusPrivateEndpoint ? ['privatelink.servicebus.windows.net'] : []
)

resource zones 'Microsoft.Network/privateDnsZones@2024-06-01' = [
  for zoneName in privateDnsZoneNames: {
    name: zoneName
    location: 'global'
    tags: tags
  }
]

resource links 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = [
  for (zoneName, i) in privateDnsZoneNames: {
    parent: zones[i]
    name: 'link-${vnet.name}'
    location: 'global'
    properties: {
      registrationEnabled: false
      virtualNetwork: {
        id: vnet.id
      }
    }
  }
]

output appsSubnetId string = vnet.properties.subnets[0].id
output privateEndpointsSubnetId string = vnet.properties.subnets[1].id
output sqlDnsZoneId string = zones[0].id
output keyVaultDnsZoneId string = zones[1].id
output serviceBusDnsZoneId string = serviceBusPrivateEndpoint ? zones[2].id : ''
