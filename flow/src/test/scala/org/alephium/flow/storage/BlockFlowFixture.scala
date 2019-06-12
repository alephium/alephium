package org.alephium.flow.storage

import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.RocksDBStorage
import org.alephium.util.AlephiumSpec
import org.scalatest.BeforeAndAfter

trait BlockFlowFixture extends AlephiumSpec with PlatformConfig.Default with BeforeAndAfter {
  after {
    TestUtils.clear(config.disk.blockFolder)
    RocksDBStorage.dESTROY(config.headerDB.storage)
  }
}
