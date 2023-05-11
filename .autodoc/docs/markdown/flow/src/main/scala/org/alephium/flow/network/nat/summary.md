[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/flow/src/main/scala/org/alephium/flow/network/nat)

The `Upnp.scala` module in the Alephium project provides functionality for managing UPnP (Universal Plug and Play) port mappings, which enables devices on a network to discover and communicate with each other. This module is particularly useful for allowing external clients to connect to the Alephium network without requiring manual port forwarding on the router.

The module consists of the `Upnp` object and the `UpnpClient` class. The `Upnp` object defines constants and methods for discovering UPnP gateway devices and creating an `UpnpClient` instance to manage port mappings. The `getUpnpClient` method takes an `UpnpSettings` object as input and returns an `Option[UpnpClient]`. It sets the HTTP read timeout and discovery timeout based on the provided settings, discovers UPnP gateway devices on the network, and returns a new `UpnpClient` instance if a valid gateway device is found.

```scala
val upnpSettings = UpnpSettings(readTimeout = 5000, discoveryTimeout = 10000)
val upnpClientOption = Upnp.getUpnpClient(upnpSettings)
```

The `UpnpClient` class represents a UPnP gateway device and provides methods for adding and deleting port mappings. The `addPortMapping` method takes an external port number and an internal port number as input and returns a `Boolean` indicating whether the port mapping was successfully added. It uses the `GatewayDevice` instance associated with the `UpnpClient` to add port mappings for both TCP and UDP protocols.

```scala
val externalPort = 12345
val internalPort = 54321
val success = upnpClient.addPortMapping(externalPort, internalPort)
```

The `deletePortMapping` method takes an external port number as input and returns a `Boolean` indicating whether the port mapping was successfully deleted. It uses the `GatewayDevice` instance associated with the `UpnpClient` to delete port mappings for both TCP and UDP protocols.

```scala
val externalPort = 12345
val success = upnpClient.deletePortMapping(externalPort)
```

In the context of the Alephium project, this module can be used to automatically configure a user's router to allow incoming connections to an Alephium node running on their home network. This simplifies the process of setting up a node and makes it more accessible to users who may not be familiar with manual port forwarding.
