package org.alephium.flow.storage

import java.nio.file.{Files, Path, StandardOpenOption => Option}

import akka.util.ByteString
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.{Block, BlockHeader}
import org.alephium.serde._

object DiskIO {
  def create(root: Path): IOResult[DiskIO] = execute {
    createUnsafe(root)
  }

  def createUnsafe(root: Path): DiskIO = {
    createDirUnsafe(root)
    val diskIO = new DiskIO(root)
    createDirUnsafe(diskIO.blockFolder)
    diskIO
  }

  def createDir(path: Path): IOResult[Unit] = execute {
    createDirUnsafe(path)
  }

  def createDirUnsafe(path: Path): Unit = {
    if (!Files.exists(path)) {
      Files.createDirectory(path)
    }
    ()
  }

  @inline
  def execute[T](f: => T): IOResult[T] = {
    try Right(f)
    catch { case e: Exception => Left(IOError.from(e)) }
  }

  @inline
  def executeF[T](f: => IOResult[T]): IOResult[T] = {
    try f
    catch { case e: Exception => Left(IOError.from(e)) }
  }
}

class DiskIO private (root: Path) {
  import DiskIO.{execute, executeF}

  val blockFolder: Path = root.resolve("blocks")

  def getBlockPath(block: Block): Path = {
    getBlockPath(block.hash)
  }

  def getBlockPath(header: BlockHeader): Path = {
    getBlockPath(header.hash)
  }

  def getBlockPath(blockHash: Keccak256): Path = {
    blockFolder.resolve(blockHash.shortHex + ".dat")
  }

  def putBlock(block: Block): IOResult[Int] = executeF {
    val data    = serialize(block)
    val outPath = getBlockPath(block)
    val out     = Files.newByteChannel(outPath, Option.CREATE, Option.WRITE)
    try {
      val length = out.write(data.toByteBuffer)
      Right(length)
    } catch { case e: Exception => Left(IOError.from(e)) } finally {
      out.close()
    }
  }

  def getBlock(blockHash: Keccak256): IOResult[Block] = {
    val dataIOResult = execute {
      val inPath = getBlockPath(blockHash)
      val bytes  = Files.readAllBytes(inPath)
      ByteString.fromArrayUnsafe(bytes)
    }
    dataIOResult.flatMap { data =>
      deserialize[Block](data).left.map(IOError.from)
    }
  }

  def checkBlockFile(blockHash: Keccak256): Boolean = {
    val result = execute {
      val inPath = getBlockPath(blockHash)
      Files.isRegularFile(inPath)
    }
    result.fold(_ => false, identity)
  }
}
