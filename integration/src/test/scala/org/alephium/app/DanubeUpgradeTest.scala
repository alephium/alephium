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

package org.alephium.app

import org.alephium.api.model.BlocksPerTimeStampRange
import org.alephium.util.{AlephiumActorSpec, TimeStamp}

class DanubeUpgradeTest extends AlephiumActorSpec {
  it should "test danube upgrade" in new CliqueFixture {
    val from = TimeStamp.now()
    val danubeHardForkTimeStamp = from.plusSecondsUnsafe(20).millis
    val clique = bootClique(
      1,
      configOverrides = Map(("alephium.network.danube-hard-fork-timestamp", danubeHardForkTimeStamp))
    )

    clique.start()
    clique.startMining()
    Thread.sleep(40000)
    clique.stopMining()

    val blocks = clique.selfClique().nodes.flatMap { peer =>
      request[BlocksPerTimeStampRange](
        blockflowFetch(from, TimeStamp.now()),
        peer.restPort
      ).blocks
    }.flatMap(identity)

    blocks.exists(_.timestamp.millis < danubeHardForkTimeStamp) is true
    blocks.exists(_.timestamp.millis > danubeHardForkTimeStamp) is true
    clique.stop()
  }
}
