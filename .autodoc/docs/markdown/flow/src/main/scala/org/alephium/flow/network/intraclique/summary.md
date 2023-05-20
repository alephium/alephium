[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/flow/src/main/scala/org/alephium/flow/network/intraclique)

The `.autodoc/docs/json/flow/src/main/scala/org/alephium/flow/network/intraclique` folder contains code related to handling intra-clique communication between brokers in the Alephium network. This communication is essential for synchronizing the state of the network and sharing information about new transactions and blocks.

The `BrokerHandler.scala` file defines a trait called `BrokerHandler` that extends another trait called `BaseBrokerHandler`. It provides methods and variables for handling different types of messages and events during intra-clique communication, such as `handleHandshakeInfo`, `handleInv`, and `handleTxsResponse`. The `syncing` method is used to periodically synchronize the state of the broker with other brokers in the clique.

```scala
class MyBrokerHandler extends BrokerHandler {
  // Implementations for abstract methods and variables
}
```

The `InboundBrokerHandler.scala` file defines the `InboundBrokerHandler` class, responsible for handling incoming broker connections within the Alephium network. It extends the `BaseInboundBrokerHandler` class and implements the `BrokerHandler` trait. The `props` method creates a new instance of the `InboundBrokerHandler` with the given parameters.

```scala
val props = InboundBrokerHandler.props(
  selfCliqueInfo,
  remoteAddress,
  connection,
  blockflow,
  allHandlers,
  cliqueManager,
  blockFlowSynchronizer
)
val inboundBrokerHandler = system.actorOf(props)
```

The `OutboundBrokerHandler.scala` file contains the implementation of the `OutboundBrokerHandler` class, responsible for handling outbound connections to other brokers in the Alephium network. It extends the `BaseOutboundBrokerHandler` class and implements the `BrokerHandler` trait. The companion object's `props` method creates a new instance of the class with the given parameters.

```scala
val outboundBrokerHandler = OutboundBrokerHandler.props(
  selfCliqueInfo,
  remoteBroker,
  blockflow,
  allHandlers,
  cliqueManager,
  blockFlowSynchronizer
)
val outboundBroker = system.actorOf(outboundBrokerHandler)
```

In summary, this folder contains code for handling intra-clique communication between brokers in the Alephium network. The `BrokerHandler` trait provides a standardized way of handling different types of messages and events, while the `InboundBrokerHandler` and `OutboundBrokerHandler` classes handle incoming and outgoing connections, respectively. These components work together to ensure the entire network is in sync and facilitate communication between brokers in the same clique.
