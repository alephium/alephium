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
import org.alephium.protocol.config.{ConsensusConfig, ConsensusConfigs}
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.model.*
import org.alephium.util.{AVector, Duration}

class ValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  override val configValues = Map(
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
    }
    Validation.preValidate(AVector(block))(newConsensusConfigs) is false

    val invalidBlock = invalidNonceBlock(blockFlow, ChainIndex.unsafe(0, 0))
    invalidBlock.target is consensusConfigs.getConsensusConfig(block.timestamp).maxMiningTarget
    Validation.preValidate(AVector(invalidBlock)) is false
  }
}
