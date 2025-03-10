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

package org.alephium.flow

import org.alephium.flow.core.BlockFlow
import org.alephium.io.IOResult
import org.alephium.protocol.model.{Block, BlockHeader}
import org.alephium.protocol.vm.WorldState

trait RichBlockFlowT {
  implicit class RichBlockFlow(blockFlow: BlockFlow) {
    def addAndUpdateView(block: Block, worldStateOpt: Option[WorldState.Cached]): IOResult[Unit] = {
      val hardFork = blockFlow.networkConfig.getHardFork(block.timestamp)
      for {
        _ <- blockFlow.add(block, worldStateOpt)
        _ <-
          if (hardFork.isDanubeEnabled()) blockFlow.updateBestFlowSkeleton()
          else blockFlow.updateBestDeps()
      } yield ()
    }

    def addAndUpdateView(header: BlockHeader): IOResult[Unit] = {
      val hardFork = blockFlow.networkConfig.getHardFork(header.timestamp)
      for {
        _ <- blockFlow.add(header)
        _ <-
          if (hardFork.isDanubeEnabled()) blockFlow.updateBestFlowSkeleton()
          else blockFlow.updateBestDeps()
      } yield ()
    }
  }
}
