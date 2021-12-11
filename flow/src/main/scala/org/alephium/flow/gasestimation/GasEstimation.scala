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

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.core._
import org.alephium.protocol.Signature
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.protocol.vm.StatelessVM.AssetScriptExecution
import org.alephium.util._

// Some gas for the output and some gas for the input
// Does it make sense to estimate the gas when we unlock?
// Gas per output is charged ahead of time, depending on the type of the UnlockScript
// Output is fixed, it is only when we do input we execute
object GasEstimation extends StrictLogging {
  def sweepAll: (Int, Int) => GasBox = estimateWithP2PKHInputs _

  def estimateWithP2PKHInputs(numInputs: Int, numOutputs: Int): GasBox = {
    val inputGas = GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.p2pkUnlockGas)
    estimate(inputGas.mulUnsafe(numInputs), numOutputs)
  }

  def estimateWithInputScript(script: UnlockScript, numInputs: Int, numOutputs: Int): GasBox = {
    val inputs = AVector.fill(numInputs)(script)
    estimate(inputs, numOutputs)
  }

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def estimate(
      inputs: AVector[UnlockScript],
      numOutputs: Int,
      assetScriptGasEstimator: AssetScriptGasEstimator = AssetScriptGasEstimator.Mock
  ): GasBox = {
    val inputGas =
      inputs.fold(GasBox.zero)(_ addUnsafe estimateInputGas(_, assetScriptGasEstimator))
    estimate(inputGas, numOutputs)
  }

  def estimate(inputGas: GasBox, numOutputs: Int): GasBox = {
    val gas = GasSchedule.txBaseGas
      .addUnsafe(GasSchedule.txOutputBaseGas.mulUnsafe(numOutputs))
      .addUnsafe(inputGas)

    Math.max(gas, minimalGas)
  }

  def estimate(
      script: StatefulScript,
      txScriptGasEstimator: TxScriptGasEstimator
  ): GasBox = {
    txScriptGasEstimator.estimate(script) match {
      case Left(error) =>
        logger.info(
          s"Estimating gas for TxScript with error $error, fall back to $defaultGasPerInput"
        )
        defaultGasPerInput
      case Right(value) =>
        value
    }
  }

  private[gasestimation] def estimateInputGas(
      unlockScript: UnlockScript,
      assetScriptGasEstimator: AssetScriptGasEstimator
  ): GasBox = {
    unlockScript match {
      case _: UnlockScript.P2PKH =>
        GasSchedule.txInputBaseGas.addUnsafe(GasSchedule.p2pkUnlockGas)
      case p2mpkh: UnlockScript.P2MPKH =>
        GasSchedule.txInputBaseGas.addUnsafe(
          GasSchedule.p2mpkUnlockGas(p2mpkh.indexedPublicKeys.length)
        )
      case p2sh: UnlockScript.P2SH =>
        assetScriptGasEstimator.estimate(p2sh) match {
          case Left(error) =>
            logger.info(
              s"Estimating gas for AssetScript with error $error, fall back to $defaultGasPerInput"
            )
            defaultGasPerInput
          case Right(value) =>
            value
        }
    }
  }
}

trait AssetScriptGasEstimator {
  def estimate(script: UnlockScript.P2SH): Either[String, GasBox]
}

object AssetScriptGasEstimator {
  class Default(
      chainIndex: ChainIndex,
      unsignedTx: UnsignedTransaction,
      flow: BlockFlow
  ) extends AssetScriptGasEstimator {
    def estimate(
        p2sh: UnlockScript.P2SH
    ): Either[String, GasBox] = {
      val txTemplate = TransactionTemplate(
        unsignedTx,
        inputSignatures = AVector.empty,
        scriptSignatures = AVector.empty
      )

      def runScript(
          blockEnv: BlockEnv,
          txEnv: TxEnv
      ): Either[String, AssetScriptExecution] = {
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

        result match {
          case Right(value)       => Right(value)
          case Left(Right(error)) => Left(error.name)
          case Left(Left(error))  => Left(error.name)
        }
      }

      for {
        blockEnv   <- flow.getDryrunBlockEnv(chainIndex).left.map(_.toString())
        groupView  <- flow.getMutableGroupView(chainIndex.from).left.map(_.toString())
        preOutputs <- groupView.getPreOutputs(unsignedTx.inputs).left.map(_.toString())
        txEnv = TxEnv(
          txTemplate,
          preOutputs.getOrElse(AVector.empty),
          Stack.popOnly(AVector.empty[Signature])
        )
        result <- runScript(blockEnv, txEnv)
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
}

trait TxScriptGasEstimator {
  def estimate(script: StatefulScript): Either[String, GasBox]
}

object TxScriptGasEstimator {
  class Default(
      inputs: AVector[TxInput],
      flow: BlockFlow
  )(implicit networkConfig: NetworkConfig, config: GroupConfig)
      extends TxScriptGasEstimator {
    def estimate(script: StatefulScript): Either[String, GasBox] = {
      val chainIndexOpt =
        inputs.headOption.map(input => ChainIndex(input.fromGroup, input.fromGroup))

      def runScript(
          blockEnv: BlockEnv,
          groupView: BlockFlowGroupView[WorldState.Cached],
          preOutputs: Option[AVector[AssetOutput]]
      ): Either[String, TxScriptExecution] = {
        val txTemplate = TransactionTemplate(
          UnsignedTransaction(Some(script), inputs, AVector.empty),
          inputSignatures = AVector.empty,
          scriptSignatures = AVector.empty
        )

        val result = VM.checkCodeSize(maximalGasPerTx, script.bytes).flatMap { remainingGas =>
          StatefulVM.runTxScript(
            groupView.worldState.staging(),
            blockEnv,
            txTemplate,
            preOutputs,
            script,
            remainingGas
          )
        }

        result match {
          case Right(value)       => Right(value)
          case Left(Right(error)) => Left(error.name)
          case Left(Left(error))  => Left(error.name)
        }
      }

      for {
        chainIndex <- chainIndexOpt.toRight("No UTXO found.")
        blockEnv   <- flow.getDryrunBlockEnv(chainIndex).left.map(_.toString())
        groupView  <- flow.getMutableGroupView(chainIndex.from).left.map(_.toString())
        preOutputs <- groupView.getPreOutputs(inputs).left.map(_.toString())
        result     <- runScript(blockEnv, groupView, preOutputs)
      } yield {
        maximalGasPerTx.subUnsafe(result.gasBox)
      }
    }
  }

  object Mock extends TxScriptGasEstimator {
    def estimate(script: StatefulScript): Either[String, GasBox] = {
      Right(defaultGasPerInput)
    }
  }
}
