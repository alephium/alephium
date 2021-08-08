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

import org.alephium.macros.HashSerde
import org.alephium.protocol.ALF
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.vm._
import org.alephium.serde._
import org.alephium.util.{AVector, TimeStamp, U256}

/** Upto one new token might be issued in each transaction exception for the coinbase transaction
  * The id of the new token will be hash of the first input
  *
  * @param chainId the id of the chain which can accept the tx
  * @param scriptOpt optional script for invoking stateful contracts
  * @param startGas the amount of gas can be used for tx execution
  * @param inputs a vector of TxInput
  * @param fixedOutputs a vector of TxOutput. ContractOutput are put in front of AssetOutput
  */
@HashSerde
final case class UnsignedTransaction(
    chainId: ChainId,
    scriptOpt: Option[StatefulScript],
    startGas: GasBox,
    gasPrice: GasPrice,
    inputs: AVector[TxInput],
    fixedOutputs: AVector[AssetOutput]
) extends AnyRef {
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
}

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] = {
    val serde: Serde[UnsignedTransaction] = Serde.forProduct6(
      UnsignedTransaction.apply,
      t => (t.chainId, t.scriptOpt, t.startGas, t.gasPrice, t.inputs, t.fixedOutputs)
    )
    serde.validate(tx => if (GasBox.validate(tx.startGas)) Right(()) else Left("Invalid Gas"))
  }

  def apply(
      scriptOpt: Option[StatefulScript],
      startGas: GasBox,
      gasPrice: GasPrice,
      inputs: AVector[TxInput],
      fixedOutputs: AVector[AssetOutput]
  )(implicit networkConfig: NetworkConfig): UnsignedTransaction = {
    new UnsignedTransaction(
      networkConfig.chainId,
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
      networkConfig.chainId,
      txScriptOpt,
      minimalGas,
      defaultGasPrice,
      inputs,
      fixedOutputs
    )
  }

  def apply(inputs: AVector[TxInput], fixedOutputs: AVector[AssetOutput])(implicit
      networkConfig: NetworkConfig
  ): UnsignedTransaction = {
    UnsignedTransaction(
      networkConfig.chainId,
      None,
      minimalGas,
      defaultGasPrice,
      inputs,
      fixedOutputs
    )
  }

  def coinbase(inputs: AVector[TxInput], fixedOutputs: AVector[AssetOutput])(implicit
      networkConfig: NetworkConfig
  ): UnsignedTransaction = {
    UnsignedTransaction(
      networkConfig.chainId,
      None,
      minimalGas,
      defaultGasPrice,
      inputs,
      fixedOutputs
    )
  }

  def transfer(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      inputs: AVector[(AssetOutputRef, AssetOutput)],
      outputInfos: AVector[TxOutputInfo],
      gas: GasBox,
      gasPrice: GasPrice
  )(implicit networkConfig: NetworkConfig): Either[String, UnsignedTransaction] = {
    assume(gas >= minimalGas)
    assume(gasPrice.value <= ALF.MaxALFValue)
    val gasFee = gasPrice * gas
    for {
      inputSum <- inputs.foldE(U256.Zero)(_ add _._2.amount toRight "Input amount overflow")
      outputAmount <- outputInfos.foldE(U256.Zero)(
        _ add _.alfAmount toRight "Output amount overflow"
      )
      remainder0      <- inputSum.sub(outputAmount).toRight("Not enough balance")
      remainder       <- remainder0.sub(gasFee).toRight("Not enough balance for gas fee")
      remainingTokens <- checkTokens(inputs, outputInfos)
    } yield {
      var outputs = outputInfos.map {
        case TxOutputInfo(toLockupScript, amount, tokens, lockTimeOpt) =>
          TxOutput.asset(amount, toLockupScript, tokens, lockTimeOpt)
      }
      if (remainder > U256.Zero) {
        outputs = outputs :+ TxOutput.asset(remainder, remainingTokens, fromLockupScript)
      }
      UnsignedTransaction(
        networkConfig.chainId,
        scriptOpt = None,
        gas,
        gasPrice,
        inputs.map { case (ref, _) =>
          TxInput(ref, fromUnlockScript)
        },
        outputs
      )
    }
  }

  def checkTokens(
      inputs: AVector[(AssetOutputRef, AssetOutput)],
      outputInfos: AVector[TxOutputInfo]
  ): Either[String, AVector[(TokenId, U256)]] = {
    for {
      inputs    <- calculateTotalAmountPerToken(inputs.flatMap(_._2.tokens))
      outputs   <- calculateTotalAmountPerToken(outputInfos.flatMap(_.tokens))
      _         <- checkNoNewTokensInOutputs(inputs, outputs)
      remaining <- calculateRemainingTokens(inputs, outputs)
    } yield remaining
  }

  private def calculateTotalAmountPerToken(
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
    if (newTokens.nonEmpty) {
      Left(s"New tokens found in outputs: $newTokens")
    } else {
      Right(())
    }
  }

  private def calculateRemainingTokens(
      inputTokens: AVector[(TokenId, U256)],
      outputTokens: AVector[(TokenId, U256)]
  ): Either[String, AVector[(TokenId, U256)]] = {
    inputTokens.foldE(AVector.empty[(TokenId, U256)]) { case (acc, inputToken) =>
      val outputAmount = outputTokens.find(_._1 == inputToken._1).map(_._2).getOrElse(U256.Zero)
      inputToken._2.sub(outputAmount).toRight("Not enough balance for token $id").map { remaining =>
        acc :+ (inputToken._1 -> remaining)
      }
    }
  }

  final case class TxOutputInfo(
      lockupScript: LockupScript.Asset,
      alfAmount: U256,
      tokens: AVector[(TokenId, U256)],
      lockTime: Option[TimeStamp]
  )
}
