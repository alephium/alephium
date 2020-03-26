package org.alephium.flow

import org.scalatest.BeforeAndAfter

import org.alephium.flow.core.TestUtils
import org.alephium.flow.io.RocksDBStorage
import org.alephium.flow.platform.PlatformConfigFixture
import org.alephium.util.{AlephiumActorSpec, AlephiumSpec}

trait AlephiumFlowSpec extends AlephiumSpec with PlatformConfigFixture with BeforeAndAfter {
  after {
    TestUtils.clear(config.disk.blockFolder)
    RocksDBStorage.dESTROY(config.headerDB.storage)
  }
}

class AlephiumFlowActorSpec(name: String) extends AlephiumActorSpec(name) with AlephiumFlowSpec
