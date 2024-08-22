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
import org.alephium.io.IOError
import org.alephium.protocol.Signature
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.util._

trait TxScriptGasEstimator {
  def estimate(inputs: AVector[TxInput], script: StatefulScript): Either[String, GasBox]
}

object TxScriptGasEstimator {

  // Is it because of not all inputs are picked for gas estimation?
  final case class Default(
      flow: BlockFlow
  )(implicit
      networkConfig: NetworkConfig,
      config: GroupConfig,
      logConfig: LogConfig
  ) extends TxScriptGasEstimator {
    def estimate(inputs: AVector[TxInput], script: StatefulScript): Either[String, GasBox] = {
      assume(inputs.nonEmpty)
      val groupIndex      = inputs.head.fromGroup
      val chainIndex      = ChainIndex(groupIndex, groupIndex)
      val maximalGasPerTx = getMaximalGasPerTx()

      def runScript(
          blockEnv: BlockEnv,
          groupView: BlockFlowGroupView[WorldState.Cached],
          preOutputs: AVector[AssetOutput]
      ): Either[String, TxScriptExecution] = {
        val txTemplate = TransactionTemplate(
          UnsignedTransaction(Some(script), inputs, AVector.empty),
          inputSignatures = AVector.fill(16)(Signature.generate),
          scriptSignatures = AVector.fill(16)(Signature.generate)
        )

        val result =
          VM.checkCodeSize(maximalGasPerTx, script.bytes, blockEnv.getHardFork()).flatMap {
            remainingGas =>
              StatefulVM.runTxScriptMockup(
                groupView.worldState.staging(),
                blockEnv,
                txTemplate,
                preOutputs,
                script.mockup(),
                remainingGas
              )
          }

        result.left.map {
          case Right(error) =>
            s"Execution error when estimating gas for tx script or contract: $error"
          case Left(error) =>
            s"IO error when estimating gas for tx script or contract: $error"
        }
      }

      for {
        blockEnv  <- flow.getDryrunBlockEnv(chainIndex).left.map(ioErrorMessage)
        groupView <- flow.getMutableGroupViewIncludePool(chainIndex.from).left.map(ioErrorMessage)
        preOutputsOpt <- groupView.getPreOutputs(inputs).left.map(ioErrorMessage)
        preOutputs    <- preOutputsOpt.toRight("Tx inputs do not exit")
        result        <- runScript(blockEnv, groupView, preOutputs)
      } yield {
        maximalGasPerTx.subUnsafe(result.gasBox)
      }
    }
  }

  private def ioErrorMessage(error: IOError): String = {
    s"IO error when estimating gas for tx script or contract: $error"
  }

  object Mock extends TxScriptGasEstimator {
    def estimate(inputs: AVector[TxInput], script: StatefulScript): Either[String, GasBox] = {
      Right(defaultGasPerInput)
    }
  }

  object NotImplemented extends TxScriptGasEstimator {
    def estimate(inputs: AVector[TxInput], script: StatefulScript): Either[String, GasBox] = {
      throw new NotImplementedError("TxScriptGasEstimator not implemented")
    }
  }
}
