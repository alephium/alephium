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

package org.alephium.flow.validation

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.io.IOError
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{ConsensusConfig, ConsensusConfigs}
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.model.*
import org.alephium.protocol.vm.StackOverflow
import org.alephium.util.{AVector, Duration}

class ValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  override val configValues: Map[String, Any] = Map(
    ("alephium.consensus.num-zeros-at-least-in-hash", 1)
  )

  it should "pre-validate blocks" in {
    val block = mineFromMemPool(
      blockFlow,
      ChainIndex.unsafe(brokerConfig.groupRange.head, brokerConfig.groupRange.head)
    )
    Validation.preValidate(AVector(block)) is true

    val consensusConfig = new ConsensusConfig {
      override def maxMiningTarget: Target          = Target.unsafe(block.target.value.divide(4))
      override def blockTargetTime: Duration        = ???
      override def uncleDependencyGapTime: Duration = ???
      override def emission: Emission               = ???
    }
    val newConsensusConfigs = new ConsensusConfigs {
      override def mainnet: ConsensusConfig = consensusConfig
      override def rhone: ConsensusConfig   = consensusConfig
      override def danube: ConsensusConfig  = consensusConfig
    }
    Validation.preValidate(AVector(block))(newConsensusConfigs) is false

    val invalidBlock = invalidNonceBlock(blockFlow, ChainIndex.unsafe(0, 0))
    invalidBlock.target is consensusConfigs.getConsensusConfig(block.timestamp).maxMiningTarget
    Validation.preValidate(AVector(invalidBlock)) is false
  }

  it should "convert tx validation status to block validation status" in {
    val tx0 = transactionGen().sample.get
    tx0.contractInputs.isEmpty is true
    val ioError = IOError.keyNotFound("Input A")
    ValidationStatus.convert(tx0, Left(Left(ioError))) is Left(
      Right(ExistInvalidTx(tx0, InvalidTxDueToIOError(ioError)))
    )
    ValidationStatus.convert(tx0, Left(Right(TxScriptExeFailed(StackOverflow)))) is Right(())
    ValidationStatus.convert(tx0, Left(Right(OutOfGas))) is Left(
      Right(ExistInvalidTx(tx0, OutOfGas))
    )
    ValidationStatus.convert(tx0, Right(())) is Right(())

    val tx1 = tx0.copy(contractInputs =
      AVector(ContractOutputRef(Hint.unsafe(0), TxOutputRef.unsafeKey(Hash.random)))
    )
    ValidationStatus.convert(tx1, Left(Right(TxScriptExeFailed(StackOverflow)))) is Left(
      Right(ExistInvalidTx(tx1, ContractInputsShouldBeEmptyForFailedTxScripts))
    )
  }
}
