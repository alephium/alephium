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

import java.io.{File, PrintWriter}

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.client.Node
import org.alephium.flow.core.BlockFlow
import org.alephium.io.IOResult
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.serde.serialize
import org.alephium.util.{AVector, Hex}

class BlocksExporter(node: Node)(implicit groupConfig: GroupConfig) extends StrictLogging {

  private val blockflow: BlockFlow = node.blockFlow
  private val groupNum             = groupConfig.groups

  private val chainIndexes: Seq[ChainIndex] = for {
    i <- 0 to groupNum - 1
    j <- 0 to groupNum - 1
  } yield (ChainIndex.unsafe(i, j))

  var acc: AVector[Block] = AVector.empty
  def export(file: File): Unit = {
    chainIndexes.foreach { chainIndex =>
      exportChain(chainIndex)
    }

    val writer = new PrintWriter(file)

    acc.sortBy(_.header.timestamp).foreach { block =>
      writer.write(Hex.toHexString(serialize(block)) ++ "\n")
    }
    acc = AVector.empty
    writer.close()
  }

  private def exportChain(chainIndex: ChainIndex): IOResult[Unit] = {
    for {
      maxHeight <- blockflow.getMaxHeight(chainIndex)
      _         <- AVector.from(0 to maxHeight).mapE(height => exportAt(chainIndex, height))
    } yield ()
  }

  private def exportAt(
      chainIndex: ChainIndex,
      height: Int
  ): IOResult[Unit] = {
    for {
      hashes <- blockflow.getHashes(chainIndex, height)
      blocks <- hashes.mapE(hash => blockflow.getBlock(hash))
    } yield {
      exportBlocks(blocks)
    }
  }

  private def exportBlocks(blocks: AVector[Block]): Unit =
    blocks.foreach { block =>
      acc = acc :+ block
    }
}
