package org.alephium.flow.io

import akka.util.ByteString
import org.rocksdb.{ColumnFamilyHandle, ReadOptions, RocksDB, WriteOptions}

import org.alephium.flow.core.BlockHashChain
import org.alephium.flow.io.RocksDBSource.{ColumnFamily, Settings}
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.protocol.util.RawKeyValueStorage
import org.alephium.serde._
import org.alephium.util.AVector

trait NodeStateStorage extends RawKeyValueStorage {

  def config: GroupConfig

  private val isInitializedKey = Hash.hash("isInitialized").bytes ++ ByteString(
    Storages.isInitializedPostfix)

  def isInitialized(): IOResult[Boolean] = IOUtils.tryExecute {
    existsRawUnsafe(isInitializedKey)
  }

  def setInitialized(): IOResult[Unit] = IOUtils.tryExecute {
    putRawUnsafe(isInitializedKey, ByteString(1))
  }

  private val chainStateKeys = AVector.tabulate(config.groups, config.groups) { (from, to) =>
    ByteString(from.toByte, to.toByte, Storages.chainStatePostfix)
  }

  def chainStateStorage(chainIndex: ChainIndex): ChainStateStorage = new ChainStateStorage {
    private val chainStateKey = chainStateKeys(chainIndex.from.value)(chainIndex.to.value)

    override def updateState(state: BlockHashChain.State): IOResult[Unit] = IOUtils.tryExecute {
      putRawUnsafe(chainStateKey, serialize(state))
    }

    override def loadState(): IOResult[BlockHashChain.State] = IOUtils.tryExecute {
      deserialize[BlockHashChain.State](getRawUnsafe(chainStateKey)) match {
        case Left(e)  => throw e
        case Right(v) => v
      }
    }

    override def clearState(): IOResult[Unit] = IOUtils.tryExecute {
      deleteRawUnsafe(chainStateKey)
    }
  }

  def heightIndexStorage(chainIndex: ChainIndex): HeightIndexStorage
}

object NodeStateRockDBStorage {
  def apply(storage: RocksDBSource, cf: ColumnFamily)(
      implicit config: GroupConfig): NodeStateRockDBStorage =
    apply(storage, cf, Settings.writeOptions, Settings.readOptions)

  def apply(storage: RocksDBSource, cf: ColumnFamily, writeOptions: WriteOptions)(
      implicit config: GroupConfig): NodeStateRockDBStorage =
    apply(storage, cf, writeOptions, Settings.readOptions)

  def apply(storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions)(implicit config: GroupConfig): NodeStateRockDBStorage = {
    new NodeStateRockDBStorage(storage, cf, writeOptions, readOptions)
  }
}

class NodeStateRockDBStorage(val storage: RocksDBSource,
                             val cf: ColumnFamily,
                             val writeOptions: WriteOptions,
                             val readOptions: ReadOptions)(implicit val config: GroupConfig)
    extends RocksDBColumn
    with NodeStateStorage {
  protected val db: RocksDB                = storage.db
  protected val handle: ColumnFamilyHandle = storage.handle(cf)

  def heightIndexStorage(chainIndex: ChainIndex): HeightIndexStorage =
    new HeightIndexStorage(chainIndex, storage, cf, writeOptions, readOptions)
}
