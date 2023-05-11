[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/flow/src/main/scala/org/alephium)

The `Utils.scala` file in the `org.alephium` package provides utility functions for displaying various types of data in a human-readable format, which can be used across the Alephium project. These functions are particularly useful for debugging and logging purposes, as they help developers visualize the data structures used in the project.

For example, the `showDigest` function takes a vector of `RandomBytes` objects and returns a string representation of the vector. This can be used to display a compact representation of a list of hashes or IDs:

```scala
import org.alephium.flow.Utils._

val digest = AVector(RandomBytes(1), RandomBytes(2), RandomBytes(3))
val digestStr = showDigest(digest)
println(digestStr) // Output: "[ 01..03 ]"
```

Similarly, the `showTxs` function takes a vector of `TransactionTemplate` objects and returns a string representation of the vector, which can be used to display a compact representation of a list of transactions:

```scala
import org.alephium.flow.Utils._

val txs = AVector(TransactionTemplate(TransactionId(1)), TransactionTemplate(TransactionId(2)), TransactionTemplate(TransactionId(3)))
val txsStr = showTxs(txs)
println(txsStr) // Output: "[ 01..03 ]"
```

The `showFlow` and `showDataDigest` functions can be used to display a compact representation of nested data structures, such as a list of lists of `RandomBytes` objects or a list of `FlowData` objects, respectively.

The `showChainIndexedDigest` function is useful for displaying a compact representation of a list of pairs of `ChainIndex` and vectors of `TransactionId` objects, which can be helpful for visualizing the structure of the blockchain:

```scala
import org.alephium.flow.Utils._

val chainIndexedDigest = AVector((ChainIndex(1), AVector(TransactionId(1), TransactionId(2))), (ChainIndex(2), AVector(TransactionId(3), TransactionId(4))))
val chainIndexedDigestStr = showChainIndexedDigest(chainIndexedDigest)
println(chainIndexedDigestStr) // Output: "[ 1 -> [ 01..02 ], 2 -> [ 03..04 ] ]"
```

Lastly, the `unsafe` function is used to extract the value from an `IOResult` object when it is known that the operation that produced the object will not fail. This can be helpful for simplifying code when working with I/O operations that are guaranteed to succeed.

In summary, the `Utils.scala` file provides a set of utility functions that help with displaying various types of data in a human-readable format. These functions are useful for debugging, logging, and visualizing the data structures used in the Alephium project. They can be used in conjunction with other parts of the project to provide a better understanding of the data being processed and the state of the system.
