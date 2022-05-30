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
import java.nio.file.Path

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.core.BlockFlow
import org.alephium.io.{IOError, IOResult, IOUtils}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.serde.serialize
import org.alephium.util.{AVector, EitherF, Hex}

class BlocksExporter(blockflow: BlockFlow, rootPath: Path)(implicit groupConfig: GroupConfig)
    extends StrictLogging {

  private lazy val chainIndexes: AVector[ChainIndex] =
    AVector.from(
      for {
        i <- 0 until groupConfig.groups
        j <- 0 until groupConfig.groups
      } yield ChainIndex.unsafe(i, j)
    )

  def `export`(filename: String): IOResult[Unit] = {
    for {
      file   <- validateFilename(filename)
      blocks <- chainIndexes.flatMapE(chainIndex => fetchChain(chainIndex))
      _      <- exportBlocks(blocks, file)
    } yield ()
  }

  def validateFilename(filename: String): IOResult[File] = {
    val regex = "^[a-zA-Z0-9_-[.]]*$".r
    Either.cond(
      regex.matches(filename),
      rootPath.resolve(filename).toFile,
      IOError.Other(new Throwable(s"Invalid filename: $filename"))
    )
  }
  private def exportBlocks(blocks: AVector[Block], file: File): IOResult[Unit] = {
    IOUtils.tryExecute {
      val writer = new PrintWriter(file)

      blocks.sortBy(_.header.timestamp).foreach { block =>
        writer.write(Hex.toHexString(serialize(block)) ++ "\n")
      }
      writer.close()
    }
  }

  private def fetchChain(chainIndex: ChainIndex): IOResult[AVector[Block]] = {
    for {
      maxHeight <- blockflow.getMaxHeight(chainIndex)
      blocks <- EitherF.foldTry(0 to maxHeight, AVector.empty[Block]) { case (blocks, height) =>
        fetchBlocksAt(chainIndex, height).map(newBlocks => blocks ++ newBlocks)
      }
    } yield blocks
  }

  private def fetchBlocksAt(
      chainIndex: ChainIndex,
      height: Int
  ): IOResult[AVector[Block]] = {
    for {
      hashes <- blockflow.getHashes(chainIndex, height)
      blocks <- hashes.mapE(hash => blockflow.getBlock(hash))
    } yield blocks
  }
}
