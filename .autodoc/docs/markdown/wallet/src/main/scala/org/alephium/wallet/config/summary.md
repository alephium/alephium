[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/wallet/src/main/scala/org/alephium/wallet/config)

The `WalletConfig.scala` file in the `org.alephium.wallet.config` package defines the configuration settings for the Alephium wallet. It allows developers to customize the wallet's behavior, connect it to the blockflow service, and authenticate API requests.

The `WalletConfig` case class contains various configurable parameters, such as:

- `port`: The port number for the wallet to listen on.
- `secretDir`: The directory to store secret information.
- `lockingTimeout`: The timeout for locking the wallet.
- `apiKey`: The API key for authenticating requests to the wallet API.
- `blockflow`: The `BlockFlow` configuration containing information about the blockflow service.

The `BlockFlow` case class includes the following fields:

- `host`: The blockflow service's hostname.
- `port`: The blockflow service's port number.
- `groups`: The number of groups in the blockflow service.
- `blockflowFetchMaxAge`: The maximum age of cached blocks in the blockflow service.
- `apiKey`: The API key for authenticating requests to the blockflow service.

The `WalletConfig` object also provides implicit `ValueReader` instances for reading `WalletConfig` and `ApiKey` objects from configuration files.

Here's an example of creating a `WalletConfig` object with custom parameters:

```scala
import org.alephium.wallet.config.WalletConfig

val config = WalletConfig(
  port = Some(8080),
  secretDir = Paths.get("/path/to/secret/dir"),
  lockingTimeout = Duration.ofSeconds(30),
  apiKey = Some(ApiKey.from("my-api-key")),
  blockflow = WalletConfig.BlockFlow(
    host = "blockflow.example.com",
    port = 443,
    groups = 4,
    blockflowFetchMaxAge = Duration.ofMinutes(5),
    apiKey = Some(ApiKey.from("my-blockflow-api-key"))
  )
)
```

In this example, a `WalletConfig` object is created with a specified port, secret directory, locking timeout, and API key. The `BlockFlow` object contains information about the blockflow service, including its host, port, number of groups, and maximum age of cached blocks. The `uri` field is automatically constructed from the `host` and `port` fields.

This configuration can be used to customize the Alephium wallet's behavior, connect it to the blockflow service, and authenticate requests to both the wallet API and blockflow service.
