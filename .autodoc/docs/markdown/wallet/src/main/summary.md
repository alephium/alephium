[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/wallet/src/main)

The code in the `wallet` folder is crucial for managing wallets, addresses, balances, and transactions in the Alephium project. It provides a convenient and type-safe way for users to interact with their wallets and the Alephium blockchain.

For instance, `Constants.scala` defines the BIP32 derivation path and wallet file version, ensuring correct address generation and consistent wallet file format. `WalletApp.scala` offers a wallet service that listens for HTTP requests on a specified port, providing a RESTful API for interacting with the Alephium blockchain.

The `api` subfolder contains code for defining wallet API endpoints and examples for various wallet-related operations. `WalletEndpoints.scala` defines the wallet API endpoints using the Tapir library, while `WalletExamples.scala` provides examples of various API requests and responses.

The `config` subfolder contains the `WalletConfig.scala` file, which defines the configuration settings for the Alephium wallet, allowing developers to customize the wallet's behavior, connect it to the blockflow service, and authenticate API requests.

The `json` subfolder provides JSON codecs for various models used in the wallet, enabling easy serialization and deserialization of the models to and from JSON format, facilitating communication between the Alephium wallet and the Alephium API.

The `service` subfolder contains the `WalletService.scala` file, which provides a high-level interface for users to manage wallets, addresses, and transactions on the Alephium blockchain. It interacts with the `BlockFlowClient` to fetch balance and transaction-related information and uses the `SecretStorage` to securely store and manage wallet secrets.

The `storage` subfolder provides a secure way to store and retrieve private keys and other sensitive information through the `SecretStorage.scala` file, which is essential for a cryptocurrency wallet application like Alephium.

The `web` subfolder contains code for wallet-related functionality, such as the `BlockFlowClient`, `WalletEndpointsLogic`, and `WalletServer`. These components allow users to interact with the Alephium blockchain through various wallet operations.

Example usage:

```scala
// Create a new wallet
val (walletName, mnemonic) = walletService.createWallet(
  password = "password123",
  mnemonicSize = Mnemonic.Size._12,
  isMiner = false,
  walletName = "myWallet",
  mnemonicPassphrase = None
).getOrElse(throw new Exception("Failed to create wallet"))

// Transfer assets between addresses
val transferResult = walletService.transfer(
  wallet = walletName,
  destinations = AVector(Destination(address, amount)),
  gas = None,
  gasPrice = None,
  utxosLimit = None
).getOrElse(throw new Exception("Failed to transfer assets"))
```

In summary, the code in this folder plays a crucial role in managing wallets, addresses, balances, and transactions in the Alephium project, providing a convenient and type-safe way for users to interact with their wallets.
