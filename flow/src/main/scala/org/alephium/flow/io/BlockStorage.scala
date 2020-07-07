package org.alephium.flow.io

import java.nio.file.Path

import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.Block
import org.alephium.protocol.util.{AbstractKeyValueStorage, KeyValueStorage}
import org.alephium.serde.{Serde, Serializer}
import org.alephium.util.LruCache

object BlockStorage {
  import org.alephium.io.IOUtils._

  def create(root: Path, blocksFolder: String, cacheCapacity: Int): IOResult[BlockStorage] =
    tryExecute {
      createUnsafe(root, blocksFolder, cacheCapacity)
    }

  def createUnsafe(root: Path, blocksFolder: String, cacheCapacity: Int): BlockStorage = {
    createDirUnsafe(root)
    val path = root.resolve(blocksFolder)
    createDirUnsafe(path)
    val storage = new BlockStorageInnerImpl(path)
    new BlockStorage(storage, cacheCapacity)
  }
}

class BlockStorage(val source: BlockStorageInner, val cacheCapacity: Int)(
    implicit val keySerializer: Serializer[Hash],
    val valueSerde: Serde[Block])
    extends AbstractKeyValueStorage[Hash, Block] {
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

  def put(block: Block): IOResult[Unit] = put(block.hash, block)

  def putUnsafe(block: Block): Unit = putUnsafe(block.hash, block)

  override def exists(key: Hash): IOResult[Boolean] =
    cache.exists(key)(source.exists(key))

  override def existsUnsafe(key: Hash): Boolean =
    cache.existsUnsafe(key)(source.existsUnsafe(key))

  override def delete(key: Hash): IOResult[Unit] = ???

  override def deleteUnsafe(key: Hash): Unit = ???
}

trait BlockStorageInner extends KeyValueStorage[Hash, Block] with DiskSource

class BlockStorageInnerImpl(val folder: Path)(
    implicit val keySerializer: Serializer[Hash],
    val valueSerde: Serde[Block]
) extends BlockStorageInner
