[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/wallet/src/main/scala/org/alephium/wallet/api/model)

The code in this folder is part of the Alephium wallet API and provides various case classes and objects that represent different wallet-related operations and data structures. These classes are used to manage wallets, addresses, balances, and transactions in the Alephium project.

For example, the `AddressInfo` case class represents information about an address, such as its public key, group index, and derivation path. The `Addresses` class manages a collection of Bitcoin addresses, and its companion object provides a convenient way to create an instance of the `Addresses` class given a user's private keys.

The `Balances` case class represents the total balance of all addresses and a vector of `AddressBalance` objects that represent the balances of individual addresses. The `ChangeActiveAddress` class is used to change the active address for a particular asset.

The `MinerAddressesInfo` case class is used to provide information about the addresses controlled by a miner. The `Sign` and `SignResult` case classes are used to sign transactions or messages in the wallet API.

The `Transfer` model represents a transfer of funds from one or more source addresses to one or more destination addresses. The `WalletCreation` and `WalletCreationResult` case classes are used to create new wallets and return the resulting wallet information.

Here is an example of how the `Transfer` class might be used:

```scala
import org.alephium.wallet.api.model.Transfer
import org.alephium.api.model.Destination
import org.alephium.protocol.vm.GasBox
import org.alephium.protocol.vm.GasPrice
import org.alephium.util.AVector

// Create a transfer request with two destinations and gas and gas price specified
val destinations = AVector(Destination("address1", 100), Destination("address2", 200))
val gasBox = GasBox(1000, 10000)
val gasPrice = GasPrice(100)
val transfer = Transfer(destinations, Some(gasBox), Some(gasPrice))

// Send the transfer request to the Alephium network for processing
val transferResult = alephiumApi.sendTransfer(transfer)

// Retrieve the transaction ID and group indices from the transfer result
val txId = transferResult.txId
val fromGroup = transferResult.fromGroup
val toGroup = transferResult.toGroup
```

In this example, a new `Transfer` object is created with two destination addresses, a gas amount, and a gas price. The object is then sent to the Alephium network for processing using the `sendTransfer` method of the `alephiumApi` object. The resulting `transferResult` object contains the transaction ID and group indices for the transfer.

Overall, the code in this folder plays a crucial role in managing wallets, addresses, balances, and transactions in the Alephium project.
