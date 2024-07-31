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
import org.alephium.flow.core.UtxoSelectionAlgo.TxInputWithAsset
import org.alephium.protocol.Signature
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.util._

trait TxScriptGasEstimator {
  def estimate(
      inputWithAssets: AVector[TxInputWithAsset],
      script: StatefulScript
  ): Either[String, GasBox]
}

object TxScriptGasEstimator {

  // scalastyle:off method.length
  final case class Default(
      flow: BlockFlow
  )(implicit networkConfig: NetworkConfig, config: GroupConfig, logConfig: LogConfig)
      extends TxScriptGasEstimator {

    def estimate(
        inputWithAssets: AVector[TxInputWithAsset],
        script: StatefulScript
    ): Either[String, GasBox] = {
      assume(inputWithAssets.nonEmpty)
      val groupIndex      = inputWithAssets.head.input.fromGroup
      val chainIndex      = ChainIndex(groupIndex, groupIndex)
      val maximalGasPerTx = getMaximalGasPerTx()

      def runScript(
          blockEnv: BlockEnv,
          groupView: BlockFlowGroupView[WorldState.Cached],
          preOutputs: AVector[AssetOutput]
      ): Either[String, TxScriptExecution] = {
        val txTemplate = TransactionTemplate(
          UnsignedTransaction(Some(script), inputWithAssets.map(_.input), AVector.empty),
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
        blockEnv  <- flow.getDryrunBlockEnv(chainIndex).left.map(_.toString())
        groupView <- flow.getMutableGroupViewIncludePool(chainIndex.from).left.map(_.toString())
        preOutputs = inputWithAssets.map(_.asset.output)
        result <- runScript(blockEnv, groupView, preOutputs)
      } yield {
        maximalGasPerTx.subUnsafe(result.gasBox)
      }
    }
  }
  // scalastyle:on method.length

  object Mock extends TxScriptGasEstimator {
    def estimate(
        inputWithAssets: AVector[TxInputWithAsset],
        script: StatefulScript
    ): Either[String, GasBox] = {
      Right(defaultGasPerInput)
    }
  }

  object NotImplemented extends TxScriptGasEstimator {
    def estimate(
        inputWithAssets: AVector[TxInputWithAsset],
        script: StatefulScript
    ): Either[String, GasBox] = {
      throw new NotImplementedError("TxScriptGasEstimator not implemented")
    }
  }
}
