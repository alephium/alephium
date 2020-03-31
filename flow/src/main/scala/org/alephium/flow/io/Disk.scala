package org.alephium.flow.io

import java.nio.file.{Files, Path, StandardOpenOption => Option}

import akka.util.ByteString

import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.{Block, BlockHeader}
import org.alephium.serde._

object Disk {
  import IOUtils._

  def create(root: Path): IOResult[Disk] = tryExecute {
    createUnsafe(root)
  }

  def createUnsafe(root: Path): Disk = {
    createDirUnsafe(root)
    val disk = new Disk(root)
    createDirUnsafe(disk.blockFolder)
    disk
  }
}

class Disk private (root: Path) {
  import IOUtils.{tryExecute, tryExecuteF}

  val blockFolder: Path = root.resolve("blocks")

  def getBlockPath(block: Block): Path = {
    getBlockPath(block.hash)
  }

  def getBlockPath(header: BlockHeader): Path = {
    getBlockPath(header.hash)
  }

  def getBlockPath(blockHash: Hash): Path = {
    blockFolder.resolve(blockHash.shortHex + ".dat")
  }

  def putBlock(block: Block): IOResult[Int] = tryExecuteF {
    val data    = serialize(block)
    val outPath = getBlockPath(block)
    val out     = Files.newByteChannel(outPath, Option.CREATE, Option.WRITE)
    try {
      val length = out.write(data.toByteBuffer)
      Right(length)
    } catch { IOUtils.error } finally {
      out.close()
    }
  }

  def putBlockUnsafe(block: Block): Int = {
    val data    = serialize(block)
    val outPath = getBlockPath(block)
    val out     = Files.newByteChannel(outPath, Option.CREATE, Option.WRITE)
    try {
      val length = out.write(data.toByteBuffer)
      length
    } finally {
      out.close()
    }
  }

  def getBlock(blockHash: Hash): IOResult[Block] = {
    val dataIOResult = tryExecute {
      val inPath = getBlockPath(blockHash)
      val bytes  = Files.readAllBytes(inPath)
      ByteString.fromArrayUnsafe(bytes)
    }
    dataIOResult.flatMap { data =>
      deserialize[Block](data).left.map(IOError.Serde)
    }
  }

  def getBlockUnsafe(blockHash: Hash): Block = {
    val inPath = getBlockPath(blockHash)
    val bytes  = Files.readAllBytes(inPath)
    val data   = ByteString.fromArrayUnsafe(bytes)
    deserialize[Block](data) match {
      case Left(error)  => throw error
      case Right(block) => block
    }
  }

  def checkBlockFile(blockHash: Hash): Boolean = {
    val result = tryExecute {
      val inPath = getBlockPath(blockHash)
      Files.isRegularFile(inPath)
    }
    result.fold(_ => false, identity)
  }

  def clear(): IOResult[Unit] = tryExecute {
    IOUtils.clearUnsafe(root)
  }
}
