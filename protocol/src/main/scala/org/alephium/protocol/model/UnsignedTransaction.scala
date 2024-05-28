// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.model

import scala.collection.immutable.ListMap

import akka.util.ByteString

import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.vm._
import org.alephium.serde._
import org.alephium.util.{AVector, EitherF, Math, TimeStamp, U256}

// scalastyle:off number.of.methods
/** Up to one new token might be issued in each transaction exception for the coinbase transaction
  * The id of the new token will be hash of the first input
  *
  * @param version
  *   the version of the tx
  * @param networkId
  *   the id of the chain which can accept the tx
  * @param scriptOpt
  *   optional script for invoking stateful contracts
  * @param gasAmount
  *   the amount of gas can be used for tx execution
  * @param inputs
  *   a vector of TxInput
  * @param fixedOutputs
  *   a vector of TxOutput. ContractOutput are put in front of AssetOutput
  */
final case class UnsignedTransaction(
    version: Byte,
    networkId: NetworkId,
    scriptOpt: Option[StatefulScript],
    gasAmount: GasBox,
    gasPrice: GasPrice,
    inputs: AVector[TxInput],
    fixedOutputs: AVector[AssetOutput]
) extends AnyRef {
  lazy val id: TransactionId = TransactionId.hash(serialize(this))

  // this might only works for validated tx
  def fromGroup(implicit config: GroupConfig): GroupIndex = {
    inputs.head.fromGroup
  }

  // this might only works for validated tx
  def toGroup(implicit config: GroupConfig): GroupIndex = {
    val from    = fromGroup
    val outputs = fixedOutputs
    if (outputs.isEmpty) {
      from
    } else {
      val index = outputs.indexWhere(_.toGroup != from)
      if (index == -1) {
        from
      } else {
        outputs(index).toGroup
      }
    }
  }

  // this might only works for validated tx
  def chainIndex(implicit config: GroupConfig): ChainIndex = ChainIndex(fromGroup, toGroup)

  lazy val fixedOutputRefs: AVector[AssetOutputRef] = fixedOutputs.mapWithIndex {
    case (output, outputIndex) =>
      AssetOutputRef.from(output, TxOutputRef.key(id, outputIndex))
  }
}

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] = Serde.forProduct7(
    UnsignedTransaction.apply,
    t => (t.version, t.networkId, t.scriptOpt, t.gasAmount, t.gasPrice, t.inputs, t.fixedOutputs)
  )

  def apply(
      scriptOpt: Option[StatefulScript],
      startGas: GasBox,
      gasPrice: GasPrice,
      inputs: AVector[TxInput],
      fixedOutputs: AVector[AssetOutput]
  )(implicit networkConfig: NetworkConfig): UnsignedTransaction = {
    new UnsignedTransaction(
      DefaultTxVersion,
      networkConfig.networkId,
      scriptOpt,
      startGas,
      gasPrice,
      inputs,
      fixedOutputs
    )
  }

  def apply(
      txScriptOpt: Option[StatefulScript],
      inputs: AVector[TxInput],
      fixedOutputs: AVector[AssetOutput]
  )(implicit networkConfig: NetworkConfig): UnsignedTransaction = {
    UnsignedTransaction(
      DefaultTxVersion,
      networkConfig.networkId,
      txScriptOpt,
      minimalGas,
      nonCoinbaseMinGasPrice,
      inputs,
      fixedOutputs
    )
  }

  def apply(inputs: AVector[TxInput], fixedOutputs: AVector[AssetOutput])(implicit
      networkConfig: NetworkConfig
  ): UnsignedTransaction = {
    UnsignedTransaction(
      DefaultTxVersion,
      networkConfig.networkId,
      None,
      minimalGas,
      nonCoinbaseMinGasPrice,
      inputs,
      fixedOutputs
    )
  }

  // scalastyle:off parameter.number
  def buildScriptTx(
      script: StatefulScript,
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      inputs: AVector[(AssetOutputRef, AssetOutput)],
      approvedAttoAlphAmount: U256,
      approvedTokens: AVector[(TokenId, U256)],
      gasAmount: GasBox,
      gasPrice: GasPrice
  )(implicit networkConfig: NetworkConfig): Either[String, UnsignedTransaction] = {
    val approved =
      TxOutputInfo(fromLockupScript, approvedAttoAlphAmount, approvedTokens, None, None)
    val approvedAsOutput = buildOutputs(approved)
    for {
      gasFee <- preCheckBuildTx(inputs, gasAmount, gasPrice)
      fixedOutputs <- calculateChangeOutputs(
        fromLockupScript,
        inputs,
        approvedAsOutput,
        gasFee
      )
    } yield {
      UnsignedTransaction(
        Some(script),
        gasAmount,
        gasPrice,
        inputs.map { case (ref, _) =>
          TxInput(ref, fromUnlockScript)
        },
        fixedOutputs
      )
    }
  }
  // scalastyle:on parameter.number

  def coinbase(inputs: AVector[TxInput], fixedOutputs: AVector[AssetOutput])(implicit
      networkConfig: NetworkConfig
  ): UnsignedTransaction = {
    UnsignedTransaction(
      DefaultTxVersion,
      networkConfig.networkId,
      None,
      minimalGas,
      coinbaseGasPrice,
      inputs,
      fixedOutputs
    )
  }

  @inline private def calculateChangeOutputs(
      fromLockupScript: LockupScript.Asset,
      inputs: AVector[(AssetOutputRef, AssetOutput)],
      txOutputs: AVector[AssetOutput],
      gasFee: U256
  ): Either[String, AVector[AssetOutput]] = {
    val inputUTXOView = inputs.map(_._2)
    for {
      alphRemainder   <- calculateAlphRemainder(inputUTXOView, txOutputs, gasFee)
      tokensRemainder <- calculateTokensRemainder(inputUTXOView, txOutputs)
      changeOutputs   <- calculateChangeOutputs(alphRemainder, tokensRemainder, fromLockupScript)
    } yield changeOutputs
  }

  @inline private def preCheckBuildTx(
      inputs: AVector[(AssetOutputRef, AssetOutput)],
      gas: GasBox,
      gasPrice: GasPrice
  ): Either[String, U256] = {
    assume(gas >= minimalGas)
    assume(gasPrice.value <= ALPH.MaxALPHValue)
    for {
      _ <- checkWithMaxTxInputNum(inputs)
      _ <- checkUniqueInputs(inputs)
    } yield gasPrice * gas
  }

  def buildTransferTx(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      inputs: AVector[(AssetOutputRef, AssetOutput)],
      outputInfos: AVector[TxOutputInfo],
      gas: GasBox,
      gasPrice: GasPrice
  )(implicit networkConfig: NetworkConfig): Either[String, UnsignedTransaction] = {
    for {
      gasFee <- preCheckBuildTx(inputs, gas, gasPrice)
      _      <- checkMinimalAlphPerOutput(outputInfos)
      _      <- checkTokenValuesNonZero(outputInfos)
      txOutputs = buildOutputs(outputInfos)
      changeOutputs <- calculateChangeOutputs(fromLockupScript, inputs, txOutputs, gasFee)
    } yield {
      UnsignedTransaction(
        None,
        gas,
        gasPrice,
        inputs.map { case (ref, _) =>
          TxInput(ref, fromUnlockScript)
        },
        txOutputs ++ changeOutputs
      )
    }
  }

  def buildOutputs(outputInfos: AVector[TxOutputInfo]): AVector[AssetOutput] = {
    outputInfos.flatMap(buildOutputs)
  }

  def buildOutputs(outputInfo: TxOutputInfo): AVector[AssetOutput] = {
    val TxOutputInfo(toLockupScript, attoAlphAmount, tokens, lockTimeOpt, additionalDataOpt) =
      outputInfo
    val tokenOutputs = tokens.map { token =>
      AssetOutput(
        dustUtxoAmount,
        toLockupScript,
        lockTimeOpt.getOrElse(TimeStamp.zero),
        AVector(token),
        additionalDataOpt.getOrElse(ByteString.empty)
      )
    }
    val alphRemaining = attoAlphAmount
      .sub(dustUtxoAmount.mulUnsafe(U256.unsafe(tokens.length)))
      .getOrElse(U256.Zero)
    if (alphRemaining == U256.Zero) {
      tokenOutputs
    } else {
      val alphOutput = AssetOutput(
        Math.max(alphRemaining, dustUtxoAmount),
        toLockupScript,
        lockTimeOpt.getOrElse(TimeStamp.zero),
        AVector.empty,
        additionalDataOpt.getOrElse(ByteString.empty)
      )
      tokenOutputs :+ alphOutput
    }
  }

  def buildGeneric(
      from: AVector[UnlockScriptWithAssets],
      outputInfos: AVector[TxOutputInfo],
      gas: GasBox,
      gasPrice: GasPrice
  )(implicit networkConfig: NetworkConfig): Either[String, UnsignedTransaction] = {
    assume(gas >= minimalGas)
    assume(gasPrice.value <= ALPH.MaxALPHValue)
    val gasFee    = gasPrice * gas
    val inputs    = from.flatMap(_.assets)
    val inputRefs = from.flatMap { _.assets.map { case (_, o) => o } }

    for {
      _ <- checkWithMaxTxInputNum(inputs)
      _ <- checkUniqueInputs(inputs)
      outputs = buildOutputs(outputInfos)
      _               <- checkMinimalAlphPerOutput(outputInfos)
      _               <- checkTokenValuesNonZero(outputInfos)
      alphRemainder   <- calculateAlphRemainder(inputRefs, outputs, gasFee)
      _               <- checkNoAlphRemainder(alphRemainder)
      tokensRemainder <- calculateTokensRemainder(inputRefs, outputs)
      _               <- checkNoTokensRemainder(tokensRemainder)
    } yield {
      UnsignedTransaction(
        DefaultTxVersion,
        networkConfig.networkId,
        scriptOpt = None,
        gas,
        gasPrice,
        from.flatMap { in =>
          in.assets.map { case (ref, _) =>
            TxInput(ref, in.fromUnlockScript)
          }
        },
        outputs
      )
    }
  }

  def checkNoAlphRemainder(alphRemainder: U256): Either[String, Unit] = {
    if (alphRemainder != U256.Zero) {
      Left("Inputs' Alph don't sum up to outputs and gas fee")
    } else {
      Right(())
    }
  }

  def checkNoTokensRemainder(tokensRemainder: AVector[(TokenId, U256)]): Either[String, Unit] = {
    if (tokensRemainder.exists { case (_, value) => value != U256.Zero }) {
      Left("Inputs' tokens don't sum up to outputs' tokens")
    } else {
      Right(())
    }
  }
  def checkUniqueInputs(
      assets: AVector[(AssetOutputRef, AssetOutput)]
  ): Either[String, Unit] = {
    check(
      failCondition = assets.length > assets.map(_._1).toSet.size,
      "Inputs not unique"
    )
  }

  def checkWithMaxTxInputNum(
      assets: AVector[(AssetOutputRef, AssetOutput)]
  ): Either[String, Unit] = {
    check(
      failCondition = assets.length > ALPH.MaxTxInputNum,
      "Too many inputs for the transfer, consider to reduce the amount to send, or use the `sweep-address` endpoint to consolidate the inputs first"
    )
  }

  private def calculateAlphRemainder(
      inputs: AVector[AssetOutput],
      outputs: AVector[AssetOutput],
      gasFee: U256
  ): Either[String, U256] = {
    calculateAlphRemainder(
      inputs.map(_.amount),
      outputs.map(_.amount),
      gasFee
    )
  }

  def calculateAlphRemainder(
      inputs: AVector[U256],
      outputs: AVector[U256],
      gasFee: U256
  ): Either[String, U256] = {
    for {
      inputSum <- EitherF.foldTry(inputs, U256.Zero)(_ add _ toRight "Input amount overflow")
      outputAmount <- outputs.foldE(U256.Zero)(
        _ add _ toRight "Output amount overflow"
      )
      remainder0 <- inputSum.sub(outputAmount).toRight("Not enough balance")
      remainder  <- remainder0.sub(gasFee).toRight("Not enough balance for gas fee")
    } yield remainder
  }

  private def calculateTokensRemainder(
      inputs: AVector[AssetOutput],
      outputs: AVector[AssetOutput]
  ): Either[String, AVector[(TokenId, U256)]] = {
    calculateTokensRemainder(
      inputs.flatMap(_.tokens),
      outputs.flatMap(_.tokens)
    )
  }

  def calculateTokensRemainder(
      inputs: AVector[(TokenId, U256)],
      outputs: AVector[(TokenId, U256)]
  ): Either[String, AVector[(TokenId, U256)]] = {
    for {
      inputs    <- calculateTotalAmountPerToken(inputs)
      outputs   <- calculateTotalAmountPerToken(outputs)
      _         <- checkNoNewTokensInOutputs(inputs, outputs)
      remainder <- calculateRemainingTokens(inputs, outputs)
    } yield {
      remainder.filterNot(_._2 == U256.Zero)
    }
  }

  def calculateChangeOutputs(
      alphRemainder: U256,
      tokensRemainder: AVector[(TokenId, U256)],
      fromLockupScript: LockupScript.Asset
  ): Either[String, AVector[AssetOutput]] = {
    if (alphRemainder == U256.Zero && tokensRemainder.isEmpty) {
      Right(AVector.empty)
    } else {
      val tokenDustAmount = dustUtxoAmount.mulUnsafe(U256.unsafe(tokensRemainder.length))
      val totalDustAmount = tokenDustAmount.addUnsafe(dustUtxoAmount)

      if ((alphRemainder == tokenDustAmount) || (alphRemainder >= totalDustAmount)) {
        Right(
          buildOutputs(TxOutputInfo(fromLockupScript, alphRemainder, tokensRemainder, None, None))
        )
      } else if (tokensRemainder.isEmpty) {
        Left(
          s"Not enough ALPH for ALPH change output, expected $dustUtxoAmount, got $alphRemainder"
        )
      } else if (alphRemainder < tokenDustAmount) {
        Left(
          s"Not enough ALPH for token change output, expected $tokenDustAmount, got $alphRemainder"
        )
      } else {
        Left(
          s"Not enough ALPH for ALPH and token change output, expected $totalDustAmount, got $alphRemainder"
        )
      }
    }
  }

  private def checkMinimalAlphPerOutput(
      outputs: AVector[TxOutputInfo]
  ): Either[String, Unit] = {
    check(
      failCondition = outputs.exists { output =>
        output.attoAlphAmount < dustUtxoAmount
      },
      "Not enough ALPH for transaction output"
    )
  }

  private def checkTokenValuesNonZero(
      outputs: AVector[TxOutputInfo]
  ): Either[String, Unit] = {
    check(
      failCondition = outputs.exists(_.tokens.exists(_._2.isZero)),
      "Value is Zero for one or many tokens in the transaction output"
    )
  }

  // Note: this would calculate excess dustAmount to cover the complicated cases
  def calculateTotalAmountNeeded(
      outputInfos: AVector[TxOutputInfo]
  ): Either[String, (U256, AVector[(TokenId, U256)], Int)] = {
    outputInfos
      .foldE((U256.Zero, ListMap.empty[TokenId, U256], 0)) {
        case ((totalAlphAmount, totalTokens, totalOutputLength), outputInfo) =>
          val tokenDustAmount = dustUtxoAmount.mulUnsafe(U256.unsafe(outputInfo.tokens.length))
          val outputLength = outputInfo.tokens.length + // UTXOs for token
            (if (outputInfo.attoAlphAmount <= tokenDustAmount) 0 else 1) // UTXO for ALPH
          val alphAmount =
            Math.max(outputInfo.attoAlphAmount, dustUtxoAmount.mulUnsafe(U256.unsafe(outputLength)))
          for {
            newAlphAmount  <- totalAlphAmount.add(alphAmount).toRight("ALPH amount overflow")
            newTotalTokens <- updateTokens(totalTokens, outputInfo.tokens)
          } yield (newAlphAmount, newTotalTokens, totalOutputLength + outputLength)
      }
      .flatMap { case ((totalAlphAmount, totalTokens, totalOutputLength)) =>
        val outputLengthSender = totalTokens.size + 1
        val alphAmountSender   = dustUtxoAmount.mulUnsafe(U256.unsafe(outputLengthSender))
        totalAlphAmount.add(alphAmountSender).toRight("ALPH amount overflow").map {
          finalAlphAmount =>
            (
              finalAlphAmount,
              AVector.from(totalTokens.iterator),
              totalOutputLength + outputLengthSender
            )
        }
      }
  }

  private def updateTokens(
      totalTokens: ListMap[TokenId, U256],
      newTokens: AVector[(TokenId, U256)]
  ): Either[String, ListMap[TokenId, U256]] = {
    newTokens.foldE(totalTokens) { case (acc, (tokenId, amount)) =>
      acc.get(tokenId) match {
        case Some(totalAmount) =>
          totalAmount.add(amount) match {
            case Some(newAmount) => Right(acc + (tokenId -> newAmount))
            case None            => Left(s"Amount overflow for token $tokenId")
          }
        case None => Right(acc + (tokenId -> amount))
      }
    }
  }

  def calculateTotalAmountPerToken(
      tokens: AVector[(TokenId, U256)]
  ): Either[String, AVector[(TokenId, U256)]] = {
    tokens.foldE(AVector.empty[(TokenId, U256)]) { case (acc, (id, amount)) =>
      val index = acc.indexWhere(_._1 == id)
      if (index == -1) {
        Right(acc :+ (id -> amount))
      } else {
        acc(index)._2.add(amount).toRight(s"Amount overflow for token $id").map { amt =>
          acc.replace(index, (id, amt))
        }
      }
    }
  }

  private def checkNoNewTokensInOutputs(
      inputs: AVector[(TokenId, U256)],
      outputs: AVector[(TokenId, U256)]
  ): Either[String, Unit] = {
    val newTokens = outputs.map(_._1).toSet -- inputs.map(_._1).toSet
    check(
      failCondition = newTokens.nonEmpty,
      s"New tokens found in outputs: $newTokens"
    )
  }

  private def calculateRemainingTokens(
      inputTokens: AVector[(TokenId, U256)],
      outputTokens: AVector[(TokenId, U256)]
  ): Either[String, AVector[(TokenId, U256)]] = {
    inputTokens.foldE(AVector.empty[(TokenId, U256)]) { case (acc, (inputId, inputAmount)) =>
      val outputAmount = outputTokens.find(_._1 == inputId).fold(U256.Zero)(_._2)
      inputAmount.sub(outputAmount).toRight(s"Not enough balance for token $inputId").map {
        remainder =>
          acc :+ (inputId -> remainder)
      }
    }
  }

  @inline private def check(failCondition: Boolean, errorMessage: String): Either[String, Unit] = {
    Either.cond(!failCondition, (), errorMessage)
  }

  final case class TxOutputInfo(
      lockupScript: LockupScript.Asset,
      attoAlphAmount: U256,
      tokens: AVector[(TokenId, U256)],
      lockTime: Option[TimeStamp],
      additionalDataOpt: Option[ByteString]
  )

  object TxOutputInfo {
    def apply(
        lockupScript: LockupScript.Asset,
        attoAlphAmount: U256,
        tokens: AVector[(TokenId, U256)],
        lockTime: Option[TimeStamp]
    ): TxOutputInfo = {
      TxOutputInfo(lockupScript, attoAlphAmount, tokens, lockTime, None)
    }
  }

  final case class UnlockScriptWithAssets(
      fromUnlockScript: UnlockScript,
      assets: AVector[(AssetOutputRef, AssetOutput)]
  )
}
