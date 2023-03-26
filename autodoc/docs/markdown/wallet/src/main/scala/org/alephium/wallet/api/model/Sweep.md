[View code on GitHub](https://github.com/alephium/alephium/blob/master/wallet/src/main/scala/org/alephium/wallet/api/model/Sweep.scala)

The `Sweep` class is a model in the `org.alephium.wallet.api.model` package of the Alephium project. It represents a request to sweep all available funds from a given address to another address. 

The class has six parameters: 
- `toAddress`: the address to which the funds will be swept.
- `lockTime`: an optional parameter that specifies the lock time for the transaction.
- `gasAmount`: an optional parameter that specifies the amount of gas to be used for the transaction.
- `gasPrice`: an optional parameter that specifies the gas price for the transaction.
- `utxosLimit`: an optional parameter that specifies the maximum number of unspent transaction outputs (UTXOs) to be used for the transaction.
- `targetBlockHash`: an optional parameter that specifies the target block hash for the transaction.

The `Sweep` class extends the `BuildTxCommon` trait from the `org.alephium.api.model` package, which provides common functionality for building transactions.

This class can be used in the larger project to facilitate the sweeping of funds from one address to another. For example, a user may want to sweep all available funds from a cold storage address to a hot wallet address. The `Sweep` class can be used to construct the transaction request with the desired parameters, which can then be sent to the Alephium network for processing. 

Here is an example of how the `Sweep` class can be used:

```scala
import org.alephium.wallet.api.model.Sweep
import org.alephium.protocol.model.Address.Asset

val fromAddress: Asset = ???
val toAddress: Asset = ???

val sweepRequest = Sweep(toAddress)

// Optional parameters can also be specified
sweepRequest.lockTime = Some(1234567890L)
sweepRequest.gasAmount = Some(GasBox(1000000L))
sweepRequest.gasPrice = Some(GasPrice(100L))
sweepRequest.utxosLimit = Some(10)
sweepRequest.targetBlockHash = Some(BlockHash("abcd1234"))

// Send the sweep request to the Alephium network for processing
val txHash = sendSweepRequest(fromAddress, sweepRequest)
``` 

In this example, a `Sweep` request is created with the `toAddress` parameter set to the desired destination address. Optional parameters are also set to specify the lock time, gas amount, gas price, UTXO limit, and target block hash. Finally, the `sendSweepRequest` function is called with the `fromAddress` and `sweepRequest` parameters to send the request to the Alephium network for processing.
## Questions: 
 1. What is the purpose of the `Sweep` case class?
- The `Sweep` case class is used to represent a request to sweep all UTXOs from a given address to a specified destination address.

2. What is the significance of the `BuildTxCommon` trait?
- The `BuildTxCommon` trait is a common interface for building transactions in the Alephium API.

3. What are the optional parameters in the `Sweep` case class?
- The optional parameters in the `Sweep` case class are `lockTime`, `gasAmount`, `gasPrice`, `utxosLimit`, and `targetBlockHash`. These parameters allow for customization of the sweep transaction.