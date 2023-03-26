[View code on GitHub](https://github.com/alephium/alephium/blob/master/flow/src/main/scala/org/alephium/flow/gasestimation/GasEstimation.scala)

The `GasEstimation` object in the `org.alephium.flow.gasestimation` package provides functions for estimating the amount of gas required to execute various types of scripts in the Alephium blockchain. Gas is a measure of computational effort required to execute a script, and is used to determine the fee paid by the sender of a transaction.

The `GasEstimation` object provides several functions for estimating gas usage:

- `sweepAddress`: estimates the gas required to sweep an address with P2PKH inputs.
- `estimateWithP2PKHInputs`: estimates the gas required for a transaction with P2PKH inputs.
- `estimateWithInputScript`: estimates the gas required for a transaction with a given unlock script.
- `estimate`: estimates the gas required for a transaction with a vector of unlock scripts and a given number of outputs.
- `estimate`: estimates the gas required to execute a given stateful script.

The `GasEstimation` object depends on several other classes in the Alephium project, including `GasSchedule`, `UnlockScript`, `AssetScriptGasEstimator`, `StatefulScript`, and `TxScriptGasEstimator`. These classes provide information about gas costs for various types of scripts and operations.

The `GasEstimation` object is used in the larger Alephium project to determine the fee paid by the sender of a transaction. The fee is calculated as the product of the gas used and the gas price, which is set by the network. By accurately estimating the gas required for a transaction, the sender can ensure that they pay a fair fee and that their transaction is processed in a timely manner.

Example usage:

```scala
import org.alephium.flow.gasestimation._

val numInputs = 2
val numOutputs = 1
val gasBox = GasEstimation.estimateWithP2PKHInputs(numInputs, numOutputs)
println(s"Gas required: ${gasBox.gas}")
```
## Questions: 
 1. What is the purpose of the `GasEstimation` object?
- The `GasEstimation` object is used to estimate gas consumption based on execution of various scripts in the Alephium project, including `UnlockScript` and `TxScript`.

2. What is the `sweepAddress` method used for?
- The `sweepAddress` method is used to estimate gas consumption for a P2PKH input.

3. What is the purpose of the `estimateInputGas` method?
- The `estimateInputGas` method is used to estimate gas consumption for a given `UnlockScript`, including P2PKH, P2MPKH, and P2SH scripts. It also takes into account any previous gas consumption.