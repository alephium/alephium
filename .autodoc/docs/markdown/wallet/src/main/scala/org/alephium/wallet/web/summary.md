[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/wallet/src/main/scala/org/alephium/wallet/web)

The code in this folder provides wallet-related functionality for the Alephium project, allowing users to interact with the Alephium blockchain through various wallet operations. The main components are the `BlockFlowClient`, `WalletEndpointsLogic`, and `WalletServer`.

`BlockFlowClient.scala` defines a trait and an object that implement methods for interacting with the Alephium blockchain, such as fetching balances, preparing transactions, and posting transactions. The `BlockFlowClient` object provides an implementation of these methods, taking parameters like the default URI for the Alephium blockchain, the maximum age of a cached response, an optional API key, and an object that sends requests to the Alephium blockchain.

`WalletEndpointsLogic.scala` defines a trait that provides the implementation for various wallet-related endpoints, such as creating, restoring, locking, unlocking, and deleting wallets, as well as transferring funds, signing data, and deriving new addresses. The methods in this trait use the `walletService` object to perform the necessary operations and return the results in the appropriate format.

`WalletServer.scala` defines a class that extends `WalletEndpointsLogic` and `WalletDocumentation`, using `VertxFutureServerInterpreter` to define routes for various wallet-related operations. The `WalletServer` class takes a `WalletService` instance, a `Duration` object, and an optional `ApiKey` object, as well as an implicit `GroupConfig` object and an `ExecutionContext`. The class defines a `routes` `AVector` that maps each method to a `Route` object and a `docsRoute` that provides Swagger UI documentation for the defined routes.

Here's an example of how this code might be used:

```scala
val walletService = new WalletService()
val blockflowFetchMaxAge = Duration.ofMinutes(5)
val maybeApiKey = Some(ApiKey("myApiKey"))
implicit val groupConfig = GroupConfig()
implicit val executionContext = ExecutionContext.global

val walletServer = new WalletServer(walletService, blockflowFetchMaxAge, maybeApiKey)

// Use the defined routes to handle wallet-related requests
val router = Router.router(vertx)
walletServer.routes.foreach(_.apply(router))
```

In this example, a `WalletService` instance is created, and a `WalletServer` instance is initialized with the necessary parameters. The defined routes in the `WalletServer` are then used to handle wallet-related requests using a Vert.x `Router`.
