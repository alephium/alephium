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

package org.alephium.flow.gasestimation

import org.alephium.flow.core._
import org.alephium.protocol.Signature
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.StatelessVM.AssetScriptExecution
import org.alephium.util._

trait AssetScriptGasEstimator {
  var txInputsOpt: Option[AVector[TxInput]] = None

  def estimate(script: UnlockScript.P2SH): Either[String, GasBox]
  def setInputs(inputs: AVector[TxInput]): AssetScriptGasEstimator = {
    txInputsOpt = Some(inputs)
    this
  }

  protected def getUnsignedTx()(implicit
      networkConfig: NetworkConfig
  ): Either[String, UnsignedTransaction] = {
    txInputsOpt.toRight("Error estimating gas for P2SH script").map { txInputs =>
      UnsignedTransaction(None, txInputs, AVector.empty)
    }
  }
}

object AssetScriptGasEstimator {

  final case class Default(
      flow: BlockFlow
  )(implicit networkConfig: NetworkConfig, config: GroupConfig)
      extends AssetScriptGasEstimator {
    def estimate(
        p2sh: UnlockScript.P2SH
    ): Either[String, GasBox] = {
      def runScript(
          blockEnv: BlockEnv,
          unsignedTx: UnsignedTransaction,
          preOutputs: Option[AVector[AssetOutput]]
      ): Either[String, AssetScriptExecution] = {
        val txTemplate = TransactionTemplate(unsignedTx, AVector.empty, AVector.empty)

        val txEnv = TxEnv(
          txTemplate,
          preOutputs.getOrElse(AVector.empty),
          Stack.popOnly(AVector.empty[Signature])
        )

        val result = for {
          remaining0 <- VM.checkCodeSize(maximalGasPerTx, p2sh.script.bytes)
          remaining1 <- remaining0.use(GasHash.gas(p2sh.script.bytes.length))
          exeResult <- StatelessVM.runAssetScript(
            blockEnv,
            txEnv,
            remaining1,
            p2sh.script,
            p2sh.params
          )
        } yield exeResult

        result.left.map {
          case Right(InvalidPublicKey) =>
            "Please use binary search to set the gas manually as signature is required in P2SH script"
          case Right(error) =>
            s"Execution error when estimating gas for P2SH script: $error"
          case Left(error) =>
            s"IO error when estimating gas for P2SH script: $error"
        }
      }

      for {
        unsignedTx <- getUnsignedTx()
        chainIndex <- getChainIndex(unsignedTx)
        blockEnv   <- flow.getDryrunBlockEnv(chainIndex).left.map(_.toString())
        groupView  <- flow.getMutableGroupView(chainIndex.from).left.map(_.toString())
        preOutputs <- groupView.getPreOutputs(unsignedTx.inputs).left.map(_.toString())
        result     <- runScript(blockEnv, unsignedTx, preOutputs)
      } yield {
        maximalGasPerTx.subUnsafe(result.gasRemaining)
      }
    }
  }

  object Mock extends AssetScriptGasEstimator {
    def estimate(script: UnlockScript.P2SH): Either[String, GasBox] = {
      Right(defaultGasPerInput)
    }
  }

  object NotImplemented extends AssetScriptGasEstimator {
    def estimate(script: UnlockScript.P2SH): Either[String, GasBox] = {
      throw new NotImplementedError("AssetScriptGasEstimator not implemented")
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  private def getChainIndex(
      tx: UnsignedTransaction
  )(implicit config: GroupConfig): Either[String, ChainIndex] = {
    val inputIndexes = tx.inputs.map(_.fromGroup).toSet
    if (inputIndexes.size != 1) {
      Left("Invalid group index for inputs")
    } else {
      val fromIndex = inputIndexes.head
      val outputIndexes =
        (0 until tx.fixedOutputs.length).view
          .map(index => tx.fixedOutputs(index).toGroup)
          .filter(_ != fromIndex)
          .toSet
      outputIndexes.size match {
        case 0 => Right(ChainIndex(fromIndex, fromIndex))
        case 1 => Right(ChainIndex(fromIndex, outputIndexes.head))
        case _ => Left("Invalid group index for outputs")
      }
    }
  }
}
