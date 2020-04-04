package org.alephium.flow.io

import java.nio.file.Path

import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.Block

object BlockStorage {
  import IOUtils._

  def create(root: Path): IOResult[BlockStorage] = tryExecute {
    createUnsafe(root)
  }

  def createUnsafe(root: Path): BlockStorage = {
    createDirUnsafe(root)
    val storage = new BlockStorage(root)
    createDirUnsafe(storage.folder)
    storage
  }
}

class BlockStorage private (root: Path) extends KeyValueStorage[Hash, Block] with DiskSource {

  val folder: Path = root.resolve("blocks")

  def put(block: Block): IOResult[Unit] = put(block.hash, block)

  def putUnsafe(block: Block): Unit = putUnsafe(block.hash, block)
}
