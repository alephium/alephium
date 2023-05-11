[View code on GitHub](https://github.com/alephium/alephium/protocol/src/main/scala/org/alephium/protocol/ALPH.scala)

The `ALPH` object in the `org.alephium.protocol` package contains constants and utility functions related to the Alephium cryptocurrency. 

The object defines several constants related to the Alephium currency, such as the number of coins in one ALPH, one cent, and one nanoALPH. It also defines the maximum value of an ALPH, the height, weight, and timestamp of the genesis block, and the launch timestamp of the Alephium network. Additionally, it defines several constants related to the difficulty bomb, such as the timestamp when the pre-Leman difficulty bomb is enabled, the duration of the exponential difficulty period, the timestamp when the difficulty bomb patch is enabled, and the height difference between the difficulty bomb patch and the pre-Leman difficulty bomb.

The object also defines several utility functions for converting between different units of the Alephium currency. The `alph` function takes an amount in ALPH and returns an `Option[U256]` representing the amount in wei (the smallest unit of the currency). The `alph` function also has an overload that takes a `Long` and returns the amount in wei. The `cent` and `nanoAlph` functions are similar to `alph`, but they convert to cents and nanoALPH, respectively. The object also defines constants for one ALPH and one nanoALPH, and a function `alphFromString` that takes a string in the format "x.x ALPH" and returns an `Option[U256]` representing the amount in wei.

Overall, the `ALPH` object provides a central location for constants and utility functions related to the Alephium cryptocurrency, making it easier to maintain and update the code as needed. It can be used throughout the project to perform currency conversions and access important constants related to the Alephium network.
## Questions: 
 1. What is the purpose of the `ALPH` object?
- The `ALPH` object contains constants and functions related to the Alephium protocol, such as conversion functions between different units of the protocol's currency and constants related to the protocol's genesis and launch.

2. What is the significance of the `GenesisTimestamp` and `LaunchTimestamp` constants?
- `GenesisTimestamp` represents the timestamp of the Bitcoin genesis block, which is used as a reference point for the Alephium protocol's timestamp. `LaunchTimestamp` represents the timestamp of the Alephium protocol's launch.

3. What is the purpose of the `alphFromString` function?
- The `alphFromString` function converts a string in the format "x.x ALPH" to a `U256` value representing the corresponding amount of Alephium currency.