[View code on GitHub](https://github.com/alephium/alephium/flow/src/main/scala/org/alephium/flow/core/HistoryLocators.scala)

The `HistoryLocators` object in the `core` package of the `alephium` project provides a method for sampling block heights within a given range. The `sampleHeights` method takes two integer arguments, `fromHeight` and `toHeight`, which represent the inclusive range of block heights to sample. The method returns an `AVector` of integers representing the sampled block heights.

The sampling algorithm works by starting at the `toHeight` block and iteratively subtracting a power of 2 from the height until the `fromHeight` block is reached. At each step, the current height is added to a mutable `ArrayBuffer` of heights. The resulting array is then converted to an `AVector` and reversed to produce the final output.

This method may be used in the larger project to efficiently sample block heights for various purposes, such as determining the difficulty of mining a block or verifying the validity of a transaction. For example, if a transaction references a block height within a certain range, the `sampleHeights` method could be used to ensure that the referenced block is valid and part of the blockchain. 

Here is an example usage of the `sampleHeights` method:

```
val fromHeight = 100
val toHeight = 200
val sampledHeights = HistoryLocators.sampleHeights(fromHeight, toHeight)
println(sampledHeights) // prints AVector(100, 125, 150, 175, 200)
```
## Questions: 
 1. What is the purpose of this code?
- This code defines a Scala object called `HistoryLocators` that contains a function `sampleHeights` which returns a vector of heights based on the input parameters `fromHeight` and `toHeight`.

2. What is the input and output of the `sampleHeights` function?
- The `sampleHeights` function takes in two integers `fromHeight` and `toHeight` and returns a vector of integers representing heights.
- The `fromHeight` and `toHeight` parameters are inclusive, meaning that the returned vector includes both the `fromHeight` and `toHeight` values.

3. What license is this code released under?
- This code is released under the GNU Lesser General Public License, version 3 or later.