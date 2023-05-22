[View code on GitHub](https://github.com/alephium/alephium/wallet/src/main/scala/org/alephium/wallet/service/WalletService.scala)

The `WalletService` is a core component of the Alephium project that provides functionalities for managing wallets, addresses, and transactions. It is designed to interact with the Alephium blockchain and perform various wallet-related operations.

The service allows users to create and restore wallets using mnemonics and passwords. It supports locking and unlocking wallets, deleting wallets, and listing all available wallets. Users can also retrieve wallet balances, addresses, and address information.

For transaction management, the service provides methods to transfer assets between addresses, sweep assets from active or all addresses, and sign data using wallet private keys. It also supports deriving new addresses and miner addresses, changing the active address, and revealing the mnemonic of a wallet.

The `WalletService` interacts with the `BlockFlowClient` to fetch balance and transaction-related information from the Alephium blockchain. It also uses the `SecretStorage` to securely store and manage wallet secrets, such as private keys and mnemonics.

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

Overall, the `WalletService` plays a crucial role in managing wallets and transactions in the Alephium project, providing a high-level interface for users to interact with the Alephium blockchain.
## Questions: 
 1. **Question**: What is the purpose of the `WalletService` trait and its methods?
   **Answer**: The `WalletService` trait defines the interface for a wallet service in the Alephium project. It provides methods for creating, restoring, locking, unlocking, and deleting wallets, as well as methods for managing wallet addresses, balances, transactions, and signing data.

2. **Question**: How does the wallet locking mechanism work in this implementation?
   **Answer**: The wallet locking mechanism is implemented using a `Timer` and `TimerTask`. When a wallet is unlocked, a timer task is scheduled to lock the wallet after a specified `lockingTimeout`. If the wallet is accessed before the timeout, the timer task is canceled and rescheduled, effectively resetting the timeout.

3. **Question**: What is the purpose of the `buildMinerAddresses` and `buildMinerPrivateKeys` methods in the `Impl` class?
   **Answer**: The `buildMinerAddresses` method takes a vector of `ExtendedPrivateKey` instances and groups them by their corresponding group index, creating a vector of address information for each group. The `buildMinerPrivateKeys` method uses the `buildMinerAddresses` method to create a flat vector of `ExtendedPrivateKey` instances, ordered by their group index. These methods are used to manage miner addresses and private keys in the wallet service.