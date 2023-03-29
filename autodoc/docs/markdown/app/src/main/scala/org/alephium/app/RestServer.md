[View code on GitHub](https://github.com/alephium/alephium/blob/master/app/src/main/scala/org/alephium/app/RestServer.scala)

The `RestServer` class is a REST API server that exposes various endpoints for interacting with the Alephium blockchain. It is built using the Vert.x web framework and uses the Tapir library for defining the API endpoints. 

The `RestServer` class takes in a `Node` instance, which represents a node in the Alephium network, a `Miner` actor reference, a `BlocksExporter` instance, and an optional `WalletServer` instance. It also takes in a `BrokerConfig`, an `ApiConfig`, and an `ExecutionContext`. 

The `RestServer` class extends several traits, including `EndpointsLogic`, `Documentation`, `Service`, `VertxFutureServerInterpreter`, and `StrictLogging`. These traits provide various functionality, such as defining the API endpoints, generating documentation for the API, defining the service, and logging.

The `RestServer` class defines a `routes` variable, which is a collection of all the API endpoints that the server exposes. These endpoints include methods for getting information about the node, getting information about the blockchain, building and submitting transactions, and interacting with contracts. 

The `RestServer` class also defines a `startSelfOnce` method, which starts the server and listens for incoming HTTP requests. It returns a `Future` that completes when the server is started. The `stopSelfOnce` method stops the server and returns a `Future` that completes when the server is stopped.

The `RestServer` class is used in the Alephium project to provide a REST API for interacting with the blockchain. It can be used by clients to query information about the blockchain, build and submit transactions, and interact with contracts. The `RestServer` class is a critical component of the Alephium project, as it provides a standardized way for clients to interact with the blockchain.
## Questions: 
 1. What is the purpose of this code?
   - This code defines a REST server for the Alephium project, which exposes various endpoints for interacting with the Alephium blockchain.
2. What external libraries or frameworks does this code use?
   - This code uses several external libraries and frameworks, including Vert.x, Tapir, and Scala Logging.
3. What is the role of the `WalletServer` parameter in the `RestServer` constructor?
   - The `WalletServer` parameter is an optional parameter that allows the `RestServer` to include additional endpoints for interacting with the Alephium wallet, if a `WalletServer` instance is provided.