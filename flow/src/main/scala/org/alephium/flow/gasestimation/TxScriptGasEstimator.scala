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
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.util._

trait TxScriptGasEstimator {
  def estimate(script: StatefulScript): Either[String, GasBox]
}

object TxScriptGasEstimator {

  final case class Default(
      inputs: AVector[TxInput],
      flow: BlockFlow
  )(implicit networkConfig: NetworkConfig, config: GroupConfig, logConfig: LogConfig)
      extends TxScriptGasEstimator {
    def estimate(script: StatefulScript): Either[String, GasBox] = {
      val chainIndexOpt =
        inputs.headOption.map(input => ChainIndex(input.fromGroup, input.fromGroup))

      def runScript(
          blockEnv: BlockEnv,
          groupView: BlockFlowGroupView[WorldState.Cached],
          preOutputs: AVector[AssetOutput]
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

        result.left.map {
          case Right(InvalidPublicKey) =>
            "Please use binary search to set the gas manually as signature is required in tx script or contract"
          case Right(error) =>
            s"Execution error when estimating gas for tx script or contract: $error"
          case Left(error) =>
            s"IO error when estimating gas for tx script or contract: $error"
        }
      }

      for {
        chainIndex    <- chainIndexOpt.toRight("No UTXO found.")
        blockEnv      <- flow.getDryrunBlockEnv(chainIndex).left.map(_.toString())
        groupView     <- flow.getMutableGroupView(chainIndex.from).left.map(_.toString())
        preOutputsOpt <- groupView.getPreOutputs(inputs).left.map(_.toString())
        preOutputs    <- preOutputsOpt.toRight("Tx inputs do not exit")
        result        <- runScript(blockEnv, groupView, preOutputs)
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

  object NotImplemented extends TxScriptGasEstimator {
    def estimate(script: StatefulScript): Either[String, GasBox] = {
      throw new NotImplementedError("TxScriptGasEstimator not implemented")
    }
  }
}
