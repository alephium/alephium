package org.alephium.flow.io

import org.rocksdb.{ColumnFamilyHandle, ReadOptions, RocksDB, WriteOptions}

import org.alephium.flow.io.RocksDBSource.{ColumnFamily, Settings}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.serde._
import org.alephium.util.{AVector, Bits}

object NodeStateStorage {
  def apply(storage: RocksDBSource, cf: ColumnFamily)(
      implicit config: GroupConfig): NodeStateStorage =
    apply(storage, cf, Settings.writeOptions, Settings.readOptions)

  def apply(storage: RocksDBSource, cf: ColumnFamily, writeOptions: WriteOptions)(
      implicit config: GroupConfig): NodeStateStorage =
    apply(storage, cf, writeOptions, Settings.readOptions)

  def apply(storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions)(implicit config: GroupConfig): NodeStateStorage = {
    new NodeStateStorage(storage, cf, writeOptions, readOptions)
  }

  private val tipsSerde: Serde[AVector[Hash]] = avectorSerde[Hash]
}

class NodeStateStorage(val storage: RocksDBSource,
                       cf: ColumnFamily,
                       val writeOptions: WriteOptions,
                       val readOptions: ReadOptions)(implicit config: GroupConfig)
    extends RocksDBColumn {
  import NodeStateStorage.tipsSerde

  protected val db: RocksDB                = storage.db
  protected val handle: ColumnFamilyHandle = storage.handle(cf)

  private val tipKeys = AVector.tabulate(config.groups, config.groups) { (from, to) =>
    (Bits.toBytes(from) ++ Bits.toBytes(to)) :+ Storages.tipsPostfix
  }

  def hashTreeTipsDB(chainIndex: ChainIndex): HashTreeTipsDB = new HashTreeTipsDB {
    private val tipKey = tipKeys(chainIndex.from.value)(chainIndex.to.value)

    override def updateTips(tips: AVector[Hash]): IOResult[Unit] = IOUtils.tryExecute {
      putRawUnsafe(tipKey, serialize(tips)(tipsSerde))
    }

    override def loadTips(): IOResult[AVector[Hash]] = IOUtils.tryExecute {
      deserialize(getRawUnsafe(tipKey))(tipsSerde) match {
        case Left(e)  => throw e
        case Right(v) => v
      }
    }

    override def clearTips(): IOResult[Unit] = IOUtils.tryExecute {
      deleteRawUnsafe(tipKey)
    }
  }
}
