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
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.StatelessVM.AssetScriptExecution
import org.alephium.util._

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
