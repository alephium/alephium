package org.alephium.flow.io

import java.nio.file.Path

import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.Block
import org.alephium.serde.{Serde, Serializer}
import org.alephium.util.LruCache

object BlockStorage {
  import IOUtils._

  def create(root: Path, cacheCapacity: Int): IOResult[BlockStorage] = tryExecute {
    createUnsafe(root, cacheCapacity)
  }

  def createUnsafe(root: Path, cacheCapacity: Int): BlockStorage = {
    createDirUnsafe(root)
    val storage = new BlockStorageInnerImpl(root)
    createDirUnsafe(storage.folder)
    new BlockStorage(storage, cacheCapacity)
  }
}

class BlockStorage(val storage: BlockStorageInner, val cacheCapacity: Int)(
    implicit val keySerializer: Serializer[Hash],
    val valueSerde: Serde[Block])
    extends AbstractKeyValueStorage[Hash, Block] {
  val folder: Path  = storage.folder
  private val cache = LruCache[Hash, Block, IOError](cacheCapacity)

  override def get(key: Hash): IOResult[Block] =
    cache.get(key)(storage.get(key))

  override def getUnsafe(key: Hash): Block =
    cache.getUnsafe(key)(storage.getUnsafe(key))

  override def getOpt(key: Hash): IOResult[Option[Block]] =
    cache.getOpt(key)(storage.getOpt(key))

  override def getOptUnsafe(key: Hash): Option[Block] =
    cache.getOptUnsafe(key)(storage.getOptUnsafe(key))

  override def put(key: Hash, value: Block): IOResult[Unit] =
    storage.put(key, value).map(_ => cache.putInCache(key, value))

  override def putUnsafe(key: Hash, value: Block): Unit = {
    storage.putUnsafe(key, value)
    cache.putInCache(key, value)
  }

  def put(block: Block): IOResult[Unit] = put(block.hash, block)

  def putUnsafe(block: Block): Unit = putUnsafe(block.hash, block)

  override def exists(key: Hash): IOResult[Boolean] =
    cache.exists(key)(storage.exists(key))

  override def existsUnsafe(key: Hash): Boolean =
    cache.existsUnsafe(key)(storage.existsUnsafe(key))

  override def delete(key: Hash): IOResult[Unit] = ???

  override def deleteUnsafe(key: Hash): Unit = ???

  def clear(): IOResult[Unit] = storage.clear()
}

trait BlockStorageInner extends KeyValueStorage[Hash, Block] with DiskSource {
  def root: Path
}
class BlockStorageInnerImpl(val root: Path)(implicit val keySerializer: Serializer[Hash],
                                            val valueSerde: Serde[Block])
    extends BlockStorageInner {
  val folder: Path = root.resolve("blocks")
}
