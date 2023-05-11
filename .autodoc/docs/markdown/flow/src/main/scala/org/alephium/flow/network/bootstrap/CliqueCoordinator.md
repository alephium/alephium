[View code on GitHub](https://github.com/alephium/alephium/flow/src/main/scala/org/alephium/flow/network/bootstrap/CliqueCoordinator.scala)

The code defines the `CliqueCoordinator` class, which is responsible for coordinating the connection of brokers in the Alephium network. The class receives information about the brokers and waits for all of them to be connected before broadcasting the clique information to all the brokers. Once all the brokers are ready, the class broadcasts a `Ready` message to all the brokers and waits for them to acknowledge receipt of the message. Once all the brokers have acknowledged receipt of the message, the class broadcasts the clique information to all the brokers and waits for them to terminate the connection. Once all the brokers have terminated the connection, the class sends the clique information to the bootstrapper and stops itself.

The `CliqueCoordinator` class has three states: `awaitBrokers`, `awaitAck`, and `awaitTerminated`. In the `awaitBrokers` state, the class waits for the brokers to connect and sends the broker information to the `BrokerConnector` actor. In the `awaitAck` state, the class waits for the brokers to acknowledge receipt of the `Ready` message. In the `awaitTerminated` state, the class waits for the brokers to terminate the connection.

The `CliqueCoordinator` class has two companion objects: `CliqueCoordinator` and `Event`. The `CliqueCoordinator` object defines the `props` method, which creates a new instance of the `CliqueCoordinator` class. The `Event` object defines the `Ready` event, which is broadcast to all the brokers when all the brokers are ready.

Example usage:

```scala
val bootstrapper: ActorRefT[Bootstrapper.Command] = ???
val privateKey: SecP256K1PrivateKey = ???
val publicKey: SecP256K1PublicKey = ???
implicit val brokerConfig: BrokerConfig = ???
implicit val networkSetting: NetworkSetting = ???

val cliqueCoordinator = system.actorOf(CliqueCoordinator.props(bootstrapper, privateKey, publicKey))
```
## Questions: 
 1. What is the purpose of the `CliqueCoordinator` class?
- The `CliqueCoordinator` class is responsible for coordinating the connection and communication between brokers in the Alephium network.

2. What is the `Ready` event used for?
- The `Ready` event is used to indicate that all brokers in the network have successfully connected and are ready to communicate with each other.

3. What is the purpose of the `awaitTerminated` method?
- The `awaitTerminated` method is used to handle the termination of broker actors and to ensure that all brokers have been closed before sending the `IntraCliqueInfo` to the `Bootstrapper`.