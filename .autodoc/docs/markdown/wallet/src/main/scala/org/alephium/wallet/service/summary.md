[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/wallet/src/main/scala/org/alephium/wallet/service)

The `WalletService.scala` file is a crucial part of the Alephium project, providing a high-level interface for users to manage wallets, addresses, and transactions on the Alephium blockchain. It interacts with the `BlockFlowClient` to fetch balance and transaction-related information and uses the `SecretStorage` to securely store and manage wallet secrets, such as private keys and mnemonics.

The `WalletService` allows users to create and restore wallets using mnemonics and passwords. It supports various wallet-related operations, such as locking and unlocking wallets, deleting wallets, and listing all available wallets. Users can also retrieve wallet balances, addresses, and address information.

For transaction management, the service provides methods to transfer assets between addresses, sweep assets from active or all addresses, and sign data using wallet private keys. It also supports deriving new addresses and miner addresses, changing the active address, and revealing the mnemonic of a wallet.

Here's an example of creating a new wallet:

```scala
val walletService: WalletService = ...
val (walletName, mnemonic) = walletService.createWallet(
  password = "password123",
  mnemonicSize = Mnemonic.Size._12,
  isMiner = false,
  walletName = "myWallet",
  mnemonicPassphrase = None
).getOrElse(throw new Exception("Failed to create wallet"))
```

And an example of transferring assets between addresses:

```scala
val transferResult = walletService.transfer(
  wallet = walletName,
  destinations = AVector(Destination(address, amount)),
  gas = None,
  gasPrice = None,
  utxosLimit = None
).getOrElse(throw new Exception("Failed to transfer assets"))
```

In summary, the `WalletService.scala` file plays a vital role in the Alephium project by providing a user-friendly interface for managing wallets and transactions. It interacts with other components of the project, such as the `BlockFlowClient` and `SecretStorage`, to ensure secure and efficient operations on the Alephium blockchain.
