[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/app/src/it/scala/org/alephium)

The folder located at `.autodoc/docs/json/app/src/it/scala/org/alephium` contains integration tests for the Alephium project. These tests are written in Scala and are designed to ensure that the various components of the Alephium project work together correctly.

### Files

1. **AlephiumFlowSpec.scala**: This file contains the AlephiumFlowSpec class, which tests the flow of data and transactions within the Alephium network. It checks the proper functioning of block and transaction propagation, as well as the correct handling of invalid transactions and blocks.

   Example usage:

   ```scala
   val flow = new AlephiumFlowSpec
   flow.test("propagate valid transactions") { ... }
   flow.test("reject invalid transactions") { ... }
   ```

2. **BlockFlowSynchronizerSpec.scala**: This file contains the BlockFlowSynchronizerSpec class, which tests the synchronization of block flows between different nodes in the Alephium network. It ensures that nodes can correctly synchronize their blockchains with each other, even in the presence of forks and conflicting blocks.

   Example usage:

   ```scala
   val synchronizer = new BlockFlowSynchronizerSpec
   synchronizer.test("synchronize block flows between nodes") { ... }
   synchronizer.test("handle forks and conflicting blocks") { ... }
   ```

### Subfolders

1. **api**: This subfolder contains integration tests for the Alephium API, which is used by clients to interact with the Alephium network. The tests in this folder ensure that the API correctly handles requests and responses, and that it can properly interact with the underlying Alephium components.

   Example files:

   - **WalletApiSpec.scala**: Tests the wallet-related API endpoints, such as creating and managing wallets, and sending transactions.
   - **NodeApiSpec.scala**: Tests the node-related API endpoints, such as querying the blockchain and managing the node's configuration.

2. **mining**: This subfolder contains integration tests for the Alephium mining process. The tests in this folder ensure that the mining algorithm works correctly, and that miners can successfully mine new blocks and propagate them to the rest of the network.

   Example files:

   - **CpuMinerSpec.scala**: Tests the CPU mining algorithm, ensuring that it can find valid block solutions and submit them to the network.
   - **MiningCoordinatorSpec.scala**: Tests the coordination of mining activities between different miners and nodes, ensuring that they can work together to mine new blocks.

In summary, the code in this folder is crucial for ensuring the correct functioning of the Alephium project, as it contains integration tests that verify the proper interaction between the various components of the system. Developers working on the Alephium project should be familiar with these tests and use them to validate their changes and ensure that they do not introduce any regressions or unexpected behavior.
