package org.alephium.flow.io

import org.alephium.util.{AVector, AlephiumSpec, EnumerationMacros}

import RocksDBStorage.ColumnFamily

class RocksDBStorageSpec extends AlephiumSpec {
  behavior of "RocksDBStorage"

  implicit val ordering: Ordering[ColumnFamily] = Ordering.by(_.name)

  it should "index all column family" in {
    val xs = EnumerationMacros.sealedInstancesOf[ColumnFamily]
    ColumnFamily.values is AVector.from(xs)
  }
}
