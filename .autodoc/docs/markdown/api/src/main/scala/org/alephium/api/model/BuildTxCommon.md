[View code on GitHub](https://github.com/alephium/alephium/api/src/main/scala/org/alephium/api/model/BuildTxCommon.scala)

This file contains code related to building transactions in the Alephium project. The code defines several traits and objects that are used to create transaction-related data structures and functions.

The `BuildTxCommon` trait defines common properties that are used in building transactions. It includes `gasAmount`, `gasPrice`, and `targetBlockHash`, which are all optional. The `FromPublicKey` trait is used to define a public key and its type, which can be either `Default` or `BIP340Schnorr`. The `getLockPair()` function returns a tuple of `LockupScript.Asset` and `UnlockScript` based on the public key type. The `p2pkhLockPair()` function returns a tuple of `LockupScript.Asset` and `UnlockScript` for the `Default` public key type, while the `schnorrLockPair()` function returns the same for the `BIP340Schnorr` public key type.

The `getAlphAndTokenAmounts()` function is used to calculate the amounts of ALPH and other tokens involved in a transaction. It takes in an optional `Amount` of ALPH and an optional list of `Token`s, and returns a tuple of optional `U256` and a vector of `(TokenId, U256)` pairs. The function checks for overflow errors and returns an error message if any occur.

The `GasInfo` trait defines properties related to gas, including `gasAmount` and `gasPrice`.

Overall, this file provides essential functionality for building transactions in the Alephium project. It defines common properties and functions that are used to create transaction-related data structures and calculate transaction amounts.
## Questions: 
 1. What is the purpose of the `BuildTxCommon` trait and its companion object?
- The `BuildTxCommon` trait defines common properties for building transactions, while its companion object provides utility functions for generating lockup and unlock scripts based on different types of public keys.

2. What is the `getAlphAndTokenAmounts` function used for?
- The `getAlphAndTokenAmounts` function is used to calculate the total amounts of ALPH and other tokens involved in a transaction, based on the specified inputs.

3. What is the `GasInfo` trait used for?
- The `GasInfo` trait defines properties related to gas usage in a transaction, including the amount of gas used and the gas price.