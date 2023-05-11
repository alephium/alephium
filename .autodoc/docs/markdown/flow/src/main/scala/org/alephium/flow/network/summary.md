[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/flow/src/main/scala/org/alephium/flow/network)

The code in the `.autodoc/docs/json/flow/src/main/scala/org/alephium/flow/network` folder is responsible for managing the network communication and connections between nodes in the Alephium blockchain platform. It includes classes and traits for bootstrapping the network, managing cliques, handling discovery, and synchronizing data between nodes.

For example, the `Bootstrapper.scala` file defines the `Bootstrapper` class, which is responsible for connecting nodes and creating cliques. Depending on the node's role, it uses one of its subclasses: `SingleNodeCliqueBootstrapper`, `CliqueCoordinatorBootstrapper`, or `BrokerBootstrapper`. The `BootstrapperHandler` trait defines common methods and variables for these subclasses.

```scala
val tcpController: ActorRefT[TcpController.Command] = ???
val cliqueManager: ActorRefT[CliqueManager.Command] = ???
val nodeStateStorage: NodeStateStorage = ???
val bootstrapper = Bootstrapper.props(tcpController, cliqueManager, nodeStateStorage)
```

The `CliqueManager.scala` file defines the `CliqueManager` class, which manages communication between different cliques in the Alephium network. It coordinates the creation of intra-clique and inter-clique managers and handles messages like `Start`, `Synced`, and `IsSelfCliqueReady`.

```scala
val blockflow: BlockFlow = ???
val allHandlers: AllHandlers = ???
val discoveryServer: ActorRefT[DiscoveryServer.Command] = ???
val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command] = ???
val numBootstrapNodes: Int = ???
val cliqueManager = system.actorOf(CliqueManager.props(blockflow, allHandlers, discoveryServer, blockFlowSynchronizer, numBootstrapNodes))
```

The `DiscoveryServer.scala` file implements a variant of the Kademlia protocol for discovering and maintaining a list of peers in the Alephium network. The `DiscoveryServer` class handles different types of messages and payloads, such as `Ping`, `Pong`, `FindNode`, and `Neighbors`.

```scala
val cliqueInfo: CliqueInfo = ???
val udpServer: ActorRefT[UdpServer.Command] = ???
val misbehaviorManager: ActorRefT[MisbehaviorManager.Command] = ???
val discoveryServer = system.actorOf(DiscoveryServer.props(cliqueInfo, udpServer, misbehaviorManager))
```

The `InterCliqueManager.scala` file manages connections and interactions between different cliques in the Alephium network. It maintains the state of connected brokers and handles broadcasting transactions and blocks to other brokers in the network.

```scala
val blockflow: BlockFlow = ???
val allHandlers: AllHandlers
