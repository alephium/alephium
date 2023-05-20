[View code on GitHub](https://github.com/alephium/alephium/flow/src/main/scala/org/alephium/flow/core/package.scala)

This code defines two constants in the `org.alephium.flow.core` package object. The first constant, `maxForkDepth`, is an integer value set to 100. The second constant, `maxSyncBlocksPerChain`, is also an integer value set to 50. 

These constants are likely used in the larger Alephium project to set limits on the depth of forks and the number of blocks that can be synced per chain. By setting these limits, the project can ensure that the system remains stable and efficient, preventing excessive resource usage and potential crashes.

For example, the `maxForkDepth` constant may be used in the code to limit the number of times a blockchain can fork before it is considered invalid. This helps prevent the creation of too many forks, which can lead to confusion and instability in the system.

Similarly, the `maxSyncBlocksPerChain` constant may be used to limit the number of blocks that can be synced per chain during the synchronization process. This helps prevent excessive resource usage and potential crashes during the synchronization process.

Overall, this code serves as a way to set limits on certain aspects of the Alephium project, helping to ensure that the system remains stable and efficient.
## Questions: 
 1. What is the purpose of this code file?
- This code file contains the licensing information for the Alephium project.

2. What is the significance of the `package object core` statement?
- The `package object core` statement defines a package-level object named `core`, which can be used to store commonly used values or functions.

3. What are the values assigned to `maxForkDepth` and `maxSyncBlocksPerChain` used for?
- `maxForkDepth` and `maxSyncBlocksPerChain` are constants that define the maximum depth of a fork and the maximum number of synchronized blocks per chain, respectively, in the Alephium project.