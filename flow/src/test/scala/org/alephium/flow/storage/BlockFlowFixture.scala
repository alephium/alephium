package org.alephium.flow.storage

import org.alephium.flow.PlatformConfig
import org.alephium.flow.io.HeaderDB
import org.alephium.util.AlephiumSpec
import org.scalatest.BeforeAndAfter

trait BlockFlowFixture extends AlephiumSpec with PlatformConfig.Default with BeforeAndAfter {
  after {
    TestUtils.clear(config.disk.blockFolder)
    HeaderDB.dESTROY(config.headerDB)
  }
}
