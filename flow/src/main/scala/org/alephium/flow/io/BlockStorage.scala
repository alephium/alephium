package org.alephium.flow.io

import java.nio.file.Path

import akka.util.ByteString

import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.Block
import org.alephium.serde.{Serde, Serializer}
import org.alephium.util.LruCache

trait BlockStorage extends KeyValueStorage[Hash, Block] {
  def put(block: Block): IOResult[Unit] = put(block.hash, block)

  def putUnsafe(block: Block): Unit = putUnsafe(block.hash, block)
}

class BlockDiskStorage(val source: BlockDiskStorageInner, val cacheCapacity: Int)(
    implicit val keySerializer: Serializer[Hash],
    val valueSerde: Serde[Block])
    extends BlockStorage {
  private val cache = LruCache[Hash, Block, IOError](cacheCapacity)

  def folder: Path = source.folder

  override def get(key: Hash): IOResult[Block] =
    cache.get(key)(source.get(key))

  override def getUnsafe(key: Hash): Block =
    cache.getUnsafe(key)(source.getUnsafe(key))

  override def getOpt(key: Hash): IOResult[Option[Block]] =
    cache.getOpt(key)(source.getOpt(key))

  override def getOptUnsafe(key: Hash): Option[Block] =
    cache.getOptUnsafe(key)(source.getOptUnsafe(key))

  override def put(key: Hash, value: Block): IOResult[Unit] =
    source.put(key, value).map(_ => cache.putInCache(key, value))

  override def putUnsafe(key: Hash, value: Block): Unit = {
    source.putUnsafe(key, value)
    cache.putInCache(key, value)
  }

  override def exists(key: Hash): IOResult[Boolean] =
    cache.exists(key)(source.exists(key))

  override def existsUnsafe(key: Hash): Boolean =
    cache.existsUnsafe(key)(source.existsUnsafe(key))

  override def existsRawUnsafe(key: ByteString): Boolean = source.existsRawUnsafe(key)

  override def getOptRawUnsafe(key: ByteString): Option[ByteString] = source.getOptRawUnsafe(key)

  override def getRawUnsafe(key: ByteString): ByteString = source.getRawUnsafe(key)

  override def putRawUnsafe(key: ByteString, value: ByteString): Unit =
    source.putRawUnsafe(key, value)

  override def delete(key: Hash): IOResult[Unit]      = ???
  override def deleteUnsafe(key: Hash): Unit          = ???
  override def deleteRawUnsafe(key: ByteString): Unit = ???
}

object BlockDiskStorage {
  import org.alephium.io.IOUtils._

  def create(root: Path, blocksFolder: String, cacheCapacity: Int): IOResult[BlockDiskStorage] =
    tryExecute {
      createUnsafe(root, blocksFolder, cacheCapacity)
    }

  def createUnsafe(root: Path, blocksFolder: String, cacheCapacity: Int): BlockDiskStorage = {
    createDirUnsafe(root)
    val path = root.resolve(blocksFolder)
    createDirUnsafe(path)
    val storage = new BlockDiskStorageInnerImpl(path)
    new BlockDiskStorage(storage, cacheCapacity)
  }
}

trait BlockDiskStorageInner extends KeyValueStorage[Hash, Block] with DiskSource

class BlockDiskStorageInnerImpl(val folder: Path)(
    implicit val keySerializer: Serializer[Hash],
    val valueSerde: Serde[Block]
) extends BlockDiskStorageInner
