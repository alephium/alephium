package org.alephium.flow.storage

import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.Database
import org.alephium.util.AlephiumSpec
import org.scalatest.BeforeAndAfter

trait BlockFlowFixture extends AlephiumSpec with PlatformConfig.Default with BeforeAndAfter {
  after {
    TestUtils.clear(config.disk.blockFolder)
    Database.dESTROY(config.db)
  }
}
