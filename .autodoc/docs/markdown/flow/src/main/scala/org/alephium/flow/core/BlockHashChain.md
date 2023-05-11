[View code on GitHub](https://github.com/alephium/alephium/flow/src/main/scala/org/alephium/flow/core/BlockHashChain.scala)

The `BlockHashChain` code is a part of the Alephium project and serves as a core component for managing the blockchain's state. It provides functionalities to add, update, and query block hashes, their states, and their relationships in the blockchain. The code also handles the storage and retrieval of block states and height indices.

The `BlockHashChain` trait extends `BlockHashPool`, `ChainDifficultyAdjustment`, and `BlockHashChainState` traits, which provide additional functionalities related to block hashes, chain difficulty adjustment, and chain state management.

The `addHash` method is used to add a new block hash to the chain, along with its parent hash, height, weight, timestamp, and a flag indicating if it is canonical. The `addGenesis` method is used to add the genesis block hash to the chain.

The `loadFromStorage` method is responsible for loading the chain state from storage. The `checkCompletenessUnsafe` method checks if a block hash is complete, i.e., it exists in the block state storage and height index storage.

The `BlockHashChain` also provides methods to query block hashes, their states, and their relationships, such as `isCanonical`, `contains`, `getState`, `getHeight`, `getWeight`, `isTip`, `getHashes`, `getBestTip`, `getAllTips`, `getPredecessor`, `getBlockHashesBetween`, `getBlockHashSlice`, `isBefore`, and `calHashDiff`.

The `stateCache` is a cache for block states, which helps improve the performance of state-related queries. The `updateHeightIndex` method is used to update the height index storage when a new block hash is added.

Overall, the `BlockHashChain` code plays a crucial role in maintaining the blockchain's state and providing essential functionalities for querying and updating block hashes and their relationships in the Alephium project.
## Questions: 
 1. **Question**: What is the purpose of the `BlockHashChain` trait and how does it relate to the Alephium project?
   **Answer**: The `BlockHashChain` trait is a part of the Alephium project and provides functionalities related to managing block hashes in a blockchain, such as adding and retrieving hashes, checking the canonical status of a hash, and calculating the difference between two block hashes.

2. **Question**: How does the `stateCache` work and what is its purpose in the `BlockHashChain` trait?
   **Answer**: The `stateCache` is an instance of `FlowCache` that caches the block states for faster access. It is used to store and retrieve block states without having to access the underlying storage every time, improving performance.

3. **Question**: What is the purpose of the `getBlockHashesBetween` method and how does it work?
   **Answer**: The `getBlockHashesBetween` method returns all the block hashes between two given block hashes, including the new hash. It works by recursively iterating through the parent hashes of the new hash until it reaches the old hash or detects that the old hash is not an ancestor of the new hash.