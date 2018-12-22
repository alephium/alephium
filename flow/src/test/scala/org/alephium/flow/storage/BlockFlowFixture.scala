package org.alephium.flow.storage

import org.alephium.flow.PlatformConfig
import org.alephium.util.AlephiumSpec
import org.scalatest.BeforeAndAfter

trait BlockFlowFixture extends AlephiumSpec with PlatformConfig.Default with BeforeAndAfter {
  before {
    DiskIO.createDir(config.diskIO.blockFolder).isRight is true
  }

  after {
    TestUtils.cleanup(config.diskIO.blockFolder)
  }
}
