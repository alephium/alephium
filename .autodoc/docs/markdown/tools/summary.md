[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/tools)

The `.autodoc/docs/json/tools` folder contains essential tools and utilities for the Alephium project, which are crucial for tasks such as generating documentation, calculating inflation rates, validating patches, and generating wallets. These tools help developers understand and work with the Alephium protocol more effectively.

`BuiltInFunctions.scala` is a tool that generates a JSON file containing information about the built-in functions in the Alephium protocol. This JSON file can be used by other tools to provide information about the functions to users, such as an IDE or a web-based documentation tool. Example usage:

```scala
import org.alephium.tools.BuiltInFunctions

// Generate JSON file containing built-in function information
BuiltInFunctions.main(Array())
```

`DBV110ToV100.scala` is a Scala script that performs a database migration from version 1.1.0 to version 1.0.0 for the Alephium project. This script is intended to be run as a standalone application using the `sbt run` command and is used to ensure that the database schema is correctly migrated between versions.

`MiningRewards.scala` is a tool that calculates the inflation rate of the Alephium cryptocurrency based on different parameters, such as the hashrate of the network and the number of years since the network's inception. This tool is useful for developers working on the Alephium project who need to calculate the inflation rate of the cryptocurrency based on different parameters.

```scala
import org.alephium.tools.MiningRewards

// Calculate inflation rate
MiningRewards.main(Array())
```

`OpenApiUpdate.scala` generates and updates the OpenAPI documentation for the Alephium project. By generating OpenAPI documentation, developers can easily see what endpoints are available, what parameters they accept, and what responses they return. Example usage:

```scala
// Generate OpenAPI documentation
OpenApiUpdate.main(Array())
```

`ValidateDifficultyBombPatch.scala` is a tool used to validate the difficulty bomb patch in the Alephium blockchain. This tool is used to ensure that the difficulty bomb patch is working as intended and that the expected hash rate matches the actual hash rate.

`WalletGen.scala` is a tool for generating wallets for the Alephium cryptocurrency. It generates a set of public and private keys, along with their corresponding addresses and mnemonics. Example usage:

```scala
import org.alephium.tools.WalletGen
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.GroupIndex

implicit val config: GroupConfig = new GroupConfig {
  override def groups: Int = 4
}

val (address, pubKey, priKey, mnemonic) = WalletGen.gen(GroupIndex.unsafe(0))

println(s"Address: ${address.toBase58}")
println(s"Public Key: ${pubKey.toHexString}")
println(s"Private Key: ${priKey.toHexString}")
println(s"Mnemonic: ${mnemonic.toLongString}")
```

These tools are essential for various tasks in the Alephium project, such as generating documentation, calculating inflation rates, validating patches, and generating wallets. They help developers understand and work with the Alephium protocol more effectively.
