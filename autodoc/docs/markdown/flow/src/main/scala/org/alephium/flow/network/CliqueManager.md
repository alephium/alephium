[View code on GitHub](https://github.com/alephium/alephium/blob/master/flow/src/main/scala/org/alephium/flow/network/CliqueManager.scala)

The `CliqueManager` class is a part of the Alephium project and is responsible for managing the clique network. A clique is a group of nodes that are connected to each other and share the same blockchain. The `CliqueManager` class creates and manages two types of managers: `IntraCliqueManager` and `InterCliqueManager`. The `IntraCliqueManager` is responsible for managing the communication between nodes within the same clique, while the `InterCliqueManager` is responsible for managing the communication between nodes in different cliques.

The `CliqueManager` class receives messages of type `Start`, which contains the `CliqueInfo` object. The `CliqueInfo` object contains information about the clique, such as the clique ID. Upon receiving the `Start` message, the `CliqueManager` creates an `IntraCliqueManager` actor and waits for it to become ready. Once the `IntraCliqueManager` is ready, the `CliqueManager` creates an `InterCliqueManager` actor and forwards all messages to it. The `CliqueManager` also forwards any `Tcp.Connected` messages to the `IntraCliqueManager` actor.

The `CliqueManager` class also has a `isSelfCliqueSynced` method that returns a boolean value indicating whether the self-clique is synced or not. This method is used to check if the node is ready to participate in the network.

The `CliqueManager` class is used in the larger Alephium project to manage the clique network. It creates and manages the `IntraCliqueManager` and `InterCliqueManager` actors, which are responsible for managing the communication between nodes within and between cliques. The `CliqueManager` class is an important part of the Alephium project as it ensures that the nodes in the network are connected and communicating with each other properly. 

Example usage:
```scala
val cliqueManager = system.actorOf(CliqueManager.props(blockflow, allHandlers, discoveryServer, blockFlowSynchronizer, numBootstrapNodes))
cliqueManager ! CliqueManager.Start(cliqueInfo)
```
## Questions: 
 1. What is the purpose of the `CliqueManager` class and what other classes does it interact with?
- The `CliqueManager` class is responsible for managing the intra and inter clique managers for a given `cliqueInfo`. It interacts with `IntraCliqueManager`, `InterCliqueManager`, `BlockFlowSynchronizer`, `DiscoveryServer`, and `AllHandlers`.
2. What are the different types of messages that can be sent to the `CliqueManager` class?
- The different types of messages that can be sent to the `CliqueManager` class are `Start(cliqueInfo)`, `Synced(brokerInfo)`, and `IsSelfCliqueReady`.
3. What is the purpose of the `handleWith` method in the `CliqueManager` class?
- The `handleWith` method is responsible for forwarding messages to the `InterCliqueManager` actor. It is used when the `CliqueManager` is waiting for the `IntraCliqueManager` to become ready.