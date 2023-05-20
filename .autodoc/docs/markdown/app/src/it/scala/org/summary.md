[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/app/src/it/scala/org)

The code in this folder is essential for ensuring the correct functioning of the Alephium project, as it contains integration tests that verify the proper interaction between the various components of the system. These tests are written in Scala and are designed to ensure that the different parts of the Alephium project work together correctly.

For example, the **AlephiumFlowSpec.scala** file contains the AlephiumFlowSpec class, which tests the flow of data and transactions within the Alephium network. It checks the proper functioning of block and transaction propagation, as well as the correct handling of invalid transactions and blocks. Developers can use this class to test their changes related to transaction handling and block propagation:

```scala
val flow = new AlephiumFlowSpec
flow.test("propagate valid transactions") { ... }
flow.test("reject invalid transactions") { ... }
```

Similarly, the **BlockFlowSynchronizerSpec.scala** file contains the BlockFlowSynchronizerSpec class, which tests the synchronization of block flows between different nodes in the Alephium network. It ensures that nodes can correctly synchronize their blockchains with each other, even in the presence of forks and conflicting blocks. Developers can use this class to test their changes related to blockchain synchronization:

```scala
val synchronizer = new BlockFlowSynchronizerSpec
synchronizer.test("synchronize block flows between nodes") { ... }
synchronizer.test("handle forks and conflicting blocks") { ... }
```

The subfolders in this folder contain more specific integration tests for different aspects of the Alephium project. For instance, the **api** subfolder contains tests for the Alephium API, which is used by clients to interact with the Alephium network. Developers working on the API can use these tests to ensure that their changes do not break the API's functionality:

- **WalletApiSpec.scala**: Tests the wallet-related API endpoints, such as creating and managing wallets, and sending transactions.
- **NodeApiSpec.scala**: Tests the node-related API endpoints, such as querying the blockchain and managing the node's configuration.

The **mining** subfolder contains integration tests for the Alephium mining process. Developers working on the mining algorithm can use these tests to ensure that their changes do not break the mining process:

- **CpuMinerSpec.scala**: Tests the CPU mining algorithm, ensuring that it can find valid block solutions and submit them to the network.
- **MiningCoordinatorSpec.scala**: Tests the coordination of mining activities between different miners and nodes, ensuring that they can work together to mine new blocks.

In summary, developers working on the Alephium project should be familiar with these tests and use them to validate their changes and ensure that they do not introduce any regressions or unexpected behavior.
