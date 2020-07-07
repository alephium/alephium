package org.alephium.flow.io

import java.nio.file.{Files, Path, StandardOpenOption => OOption}

import akka.util.ByteString

import org.alephium.io.{IOResult, IOUtils, KeyValueSource, RawKeyValueStorage}
import org.alephium.util.Hex

trait DiskSource extends RawKeyValueStorage with KeyValueSource {
  def folder: Path

  def getPath(key: ByteString): Path = {
    folder.resolve(Hex.toHexString(key))
  }

  override def getRawUnsafe(key: ByteString): ByteString = {
    val inPath = getPath(key)
    val bytes  = Files.readAllBytes(inPath)
    ByteString.fromArrayUnsafe(bytes)
  }

  override def getOptRawUnsafe(key: ByteString): Option[ByteString] = {
    val inPath = getPath(key)
    if (Files.isRegularFile(inPath)) {
      val bytes = Files.readAllBytes(inPath)
      Some(ByteString.fromArrayUnsafe(bytes))
    } else None
  }

  override def putRawUnsafe(key: ByteString, value: ByteString): Unit = {
    val outPath = getPath(key)
    val out     = Files.newByteChannel(outPath, OOption.CREATE, OOption.WRITE)
    out.write(value.toByteBuffer)
    ()
  }

  override def existsRawUnsafe(key: ByteString): Boolean = {
    Files.isRegularFile(getPath(key))
  }

  override def deleteRawUnsafe(key: ByteString): Unit = {
    val path = getPath(key)
    if (Files.exists(path)) Files.delete(path)
  }

  def close(): IOResult[Unit] = Right(())

  def closeUnsafe(): Unit = ()

  def dESTROY(): IOResult[Unit] = IOUtils.tryExecute {
    dESTROYUnsafe()
  }

  def dESTROYUnsafe(): Unit = {
    IOUtils.clearUnsafe(folder)
  }
}
