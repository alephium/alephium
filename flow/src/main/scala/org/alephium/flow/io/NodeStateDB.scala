package org.alephium.flow.io

import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.flow.io.RocksDBStorage.{ColumnFamily, Settings}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.AVector

object NodeStateDB {
  def apply(storage: RocksDBStorage, cf: ColumnFamily)(implicit config: GroupConfig): NodeStateDB =
    apply(storage, cf, Settings.writeOptions, Settings.readOptions)

  def apply(storage: RocksDBStorage, cf: ColumnFamily, writeOptions: WriteOptions)(
      implicit config: GroupConfig): NodeStateDB =
    apply(storage, cf, writeOptions, Settings.readOptions)

  def apply(storage: RocksDBStorage,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions)(implicit config: GroupConfig): NodeStateDB = {
    new NodeStateDB(storage, cf, writeOptions, readOptions)
  }
}

class NodeStateDB(val storage: RocksDBStorage,
                  cf: ColumnFamily,
                  writeOptions: WriteOptions,
                  readOptions: ReadOptions)(implicit config: GroupConfig)
    extends RocksDBColumn(storage, cf, writeOptions, readOptions) {
  private val tipKeys = AVector.tabulate(config.groups, config.groups) { (from, to) =>
    Hash.hash(s"TipKeys-$from-$to").bytes
  }

  def hashTreeTipsDB(chainIndex: ChainIndex): HashTreeTipsDB = new HashTreeTipsDB {
    private val tipKey = tipKeys(chainIndex.from.value)(chainIndex.to.value)

    override def updateTips(tips: AVector[Hash]): IOResult[Unit] = put[AVector[Hash]](tipKey, tips)

    override def loadTips(): IOResult[AVector[Hash]] = get[AVector[Hash]](tipKey)

    override def clearTips(): IOResult[Unit] = delete(tipKey)
  }
}
