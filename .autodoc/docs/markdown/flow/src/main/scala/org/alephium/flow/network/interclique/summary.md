[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/flow/src/main/scala/org/alephium/flow/network/interclique)

The code in this folder is part of the Alephium project's interclique network module, which is responsible for managing communication between different cliques (subnetworks) in the Alephium network. The folder contains three files: `BrokerHandler.scala`, `InboundBrokerHandler.scala`, and `OutboundBrokerHandler.scala`.

`BrokerHandler.scala` defines a trait called `BrokerHandler` that extends `BaseBrokerHandler`. It provides additional functionality for inter-clique communication, handling the exchange of data between brokers in different cliques. The trait defines several variables and methods for managing data flow, such as `maxBlockCapacity`, `maxTxsCapacity`, `seenBlocks`, `seenTxs`, and `maxForkDepth`. It also defines methods for handling different types of messages between brokers, such as `handleNewBlock`, `handleRelayTxs`, `handleInv`, and `handleTxsRequest`.

`InboundBrokerHandler.scala` defines a class and an object called `InboundBrokerHandler`. The class extends `BaseInboundBrokerHandler` and implements the `BrokerHandler` trait. It handles incoming broker connections and synchronizes block flows between different nodes in the network. The object defines a `props` method that takes in several parameters and returns a new instance of the `InboundBrokerHandler` class. Example usage:

```scala
val inboundBrokerHandler = InboundBrokerHandler.props(
  selfCliqueInfo,
  remoteAddress,
  connection,
  blockflow,
  allHandlers,
  cliqueManager,
  blockFlowSynchronizer
)
val inboundBrokerHandlerRef = context.actorOf(inboundBrokerHandler)
```

`OutboundBrokerHandler.scala` defines a class and an object called `OutboundBrokerHandler`. The class extends `BaseOutboundBrokerHandler` and implements the `BrokerHandler` trait. It handles outbound connections to other brokers in the Alephium network. The object defines a `props` method that takes in several parameters and returns a `Props` object that can be used to create an instance of the `OutboundBrokerHandler` class. Example usage:

```scala
val outboundBrokerHandler = system.actorOf(
  OutboundBrokerHandler.props(
    selfCliqueInfo,
    remoteBroker,
    blockflow,
    allHandlers,
    cliqueManager,
    blockFlowSynchronizer
  )
)
```

In summary, the code in this folder is responsible for managing inter-clique communication in the Alephium network. It provides functionality for handling incoming and outgoing broker connections, synchronizing block flows between different nodes, and managing the exchange of data between brokers in different cliques. This functionality is critical for ensuring that the different cliques in the Alephium project can communicate and share data effectively.
