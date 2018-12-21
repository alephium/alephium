package org.alephium.flow.storage

import java.nio.file.{Files, Path, StandardOpenOption => Option}

import akka.util.ByteString
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.{Block, BlockHeader}
import org.alephium.serde._

object DiskIO {
  def create(root: Path): Either[DiskIOError, DiskIO] = {
    for {
      _ <- createDirIfNot(root)
      diskIO = new DiskIO(root)
      _ <- createDirIfNot(diskIO.blockFolder)
    } yield diskIO
  }

  def createDirIfNot(path: Path): Either[DiskIOError, Unit] = executeIO {
    if (!Files.exists(path)) {
      Files.createDirectory(path)
    }
    ()
  }

  @inline
  def executeIO[T](f: => T): Either[DiskIOError, T] = {
    try Right(f)
    catch { case e: Exception => Left(DiskIOError.from(e)) }
  }
}

class DiskIO private (root: Path) {
  import DiskIO.executeIO

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

  def putBlock(block: Block): Either[DiskIOError, Int] = {
    val data = serialize(block)
    executeIO {
      val outPath = getBlockPath(block)
      val out     = Files.newByteChannel(outPath, Option.CREATE, Option.WRITE)
      out.write(data.toByteBuffer)
    }
  }

  def getBlock(blockHash: Keccak256): Either[DiskIOError, Block] = {
    val dataEither = executeIO {
      val inPath = getBlockPath(blockHash)
      val bytes  = Files.readAllBytes(inPath)
      ByteString.fromArrayUnsafe(bytes)
    }
    dataEither.flatMap { data =>
      deserialize[Block](data).left.map(DiskIOError.from)
    }
  }

  def checkBlockFile(blockHash: Keccak256): Boolean = {
    val resultEither = executeIO {
      val inPath = getBlockPath(blockHash)
      Files.isRegularFile(inPath)
    }
    resultEither.fold(_ => false, identity)
  }
}
