package org.alephium.flow.io

import org.alephium.util.{AlephiumSpec, AVector, EnumerationMacros}

class RocksDBStorageSpec extends AlephiumSpec {
  import RocksDBStorage.ColumnFamily

  behavior of "RocksDBStorage"

  implicit val ordering: Ordering[ColumnFamily] = Ordering.by(_.name)

  it should "index all column family" in {
    val xs = EnumerationMacros.sealedInstancesOf[ColumnFamily]
    ColumnFamily.values is AVector.from(xs)
  }
}
