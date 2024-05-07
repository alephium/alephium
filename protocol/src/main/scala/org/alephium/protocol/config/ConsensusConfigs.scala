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

package org.alephium.protocol.config

import org.alephium.protocol.model.{HardFork, Target}
import org.alephium.util.{Duration, Math, TimeStamp}

trait ConsensusConfigs {
  def mainnet: ConsensusConfig
  def rhone: ConsensusConfig

  lazy val maxAllowedMiningTarget: Target = Math.max(mainnet.maxMiningTarget, rhone.maxMiningTarget)

  def getConsensusConfig(hardFork: HardFork): ConsensusConfig = {
    if (hardFork.isRhoneEnabled()) rhone else mainnet
  }

  def getConsensusConfig(ts: TimeStamp)(implicit networkConfig: NetworkConfig): ConsensusConfig = {
    getConsensusConfig(networkConfig.getHardFork(ts))
  }

  // scalastyle:off magic.number
  val maxHeaderTimeStampDrift: Duration = Duration.ofSecondsUnsafe(15) // same as geth
  // scalastyle:on magic.number
}
