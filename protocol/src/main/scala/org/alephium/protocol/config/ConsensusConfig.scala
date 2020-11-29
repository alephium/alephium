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

import org.alephium.protocol.mining.Emission
import org.alephium.protocol.model.Target
import org.alephium.util.Duration

trait ConsensusConfig {

  def numZerosAtLeastInHash: Int
  def maxMiningTarget: Target

  // scalastyle:off magic.number
  def maxHeaderTimeStampDrift: Duration = Emission.blockTargetTime.timesUnsafe(16)
  // scalastyle:on magic.number

  def tipsPruneInterval: Int
  def tipsPruneDuration: Duration = Emission.blockTargetTime.timesUnsafe(tipsPruneInterval.toLong)
}
