[View code on GitHub](https://github.com/alephium/alephium/blob/master/api/src/main/scala/org/alephium/api/model/BuildTxCommon.scala)

This file contains code related to building transactions in the Alephium project. The code defines several traits and objects that are used to create transaction-related data structures and functions. 

The `BuildTxCommon` trait defines common properties that are used in building transactions, such as the gas amount, gas price, and target block hash. It also defines a sealed trait called `PublicKeyType` and two objects that extend it: `Default` and `BIP340Schnorr`. These objects are used to specify the type of public key used in a transaction. The `FromPublicKey` trait defines functions that convert a public key to a lockup script and an unlock script. The `p2pkhLockPair` function creates a lockup script and an unlock script using a SecP256K1 public key, while the `schnorrLockPair` function creates them using a BIP340Schnorr public key. 

The `getAlphAndTokenAmounts` function takes an optional `Amount` object and an optional vector of `Token` objects as input and returns a tuple of `U256` objects representing the ALPH and token amounts. If the `tokensAmount` parameter is `None`, the function returns the `attoAlphAmount` parameter as the ALPH amount and an empty vector as the token amount. If the `tokensAmount` parameter is not `None`, the function calculates the total amount of ALPH and each token in the vector and returns them as a tuple. 

The `GasInfo` trait defines properties related to gas in a transaction, such as the gas amount and gas price. 

Overall, this code provides a set of tools and functions that are used to build transactions in the Alephium project. Developers can use these functions to create transaction-related data structures and perform various operations related to transactions. For example, the `getAlphAndTokenAmounts` function can be used to calculate the total amount of ALPH and tokens in a transaction, while the `FromPublicKey` trait can be used to convert a public key to a lockup script and an unlock script.
## Questions: 
 1. What is the purpose of the `BuildTxCommon` trait and its companion object?
- The `BuildTxCommon` trait defines common properties for building transactions, while its companion object provides utility functions for working with public keys and getting amounts of ALPH and tokens.

2. What is the difference between `LockupScript.Asset` and `UnlockScript`?
- `LockupScript.Asset` represents the locking script for an asset, while `UnlockScript` represents the unlocking script for a transaction input.

3. What is the purpose of the `GasInfo` trait?
- The `GasInfo` trait defines properties related to gas for a transaction, including the amount of gas and the gas price.