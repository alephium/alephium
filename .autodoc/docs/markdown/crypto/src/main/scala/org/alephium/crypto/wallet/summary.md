[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/crypto/src/main/scala/org/alephium/crypto/wallet)

The `org.alephium.crypto.wallet` package provides functionality for generating and manipulating hierarchical deterministic (HD) wallets using the BIP32 standard and mnemonic phrases. HD wallets allow for the generation of a large number of public/private key pairs from a single seed, which can be used to derive child keys in a deterministic manner. This is useful for applications such as cryptocurrency wallets, where a user may want to generate a new address for each transaction.

The `BIP32` object provides several methods for generating master keys from a seed, such as `masterKey`, `btcMasterKey`, and `alphMasterKey`. These methods take a `ByteString` seed as input and return an `ExtendedPrivateKey` object, which represents the root of the HD wallet. The `masterKey` method takes an additional `prefix` argument, which is used to generate a unique master key for different applications.

```scala
val seed: ByteString = ...
val masterKey = BIP32.alphMasterKey(seed)
```

The `BIP32` object also provides methods for deriving child keys from a parent key, including `derive` and `derivePath`. The `derive` method takes an integer index as input and returns an `Option[ExtendedPrivateKey]` or `Option[ExtendedPublicKey]` object, depending on whether the parent key is a private or public key.

```scala
val childKeyOpt = BIP32.derive(masterKey, 0)
```

The `Mnemonic` object and its accompanying `Mnemonic` case class provide functionality for generating and validating mnemonic phrases, which are used to generate cryptographic keys for wallets. The `Mnemonic` case class represents a mnemonic phrase as a vector of words and provides methods for converting the phrase to a seed, which can be used to generate cryptographic keys.

```scala
val mnemonicOpt = Mnemonic.generate(24)
mnemonicOpt.foreach { mnemonic =>
  val seed = mnemonic.toSeed(Some("passphrase"))
  // use seed to generate cryptographic keys
}
```

Overall, the `org.alephium.crypto.wallet` package provides a convenient and secure way to generate and manipulate HD wallets using the BIP32 standard and mnemonic phrases. It can be used in conjunction with other cryptocurrency libraries to implement wallet functionality in a larger project. For example, it could be used to generate a new address for each transaction in a cryptocurrency wallet application.
