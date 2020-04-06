package org.alephium.flow

import org.scalatest.BeforeAndAfter

import org.alephium.flow.core.TestUtils
import org.alephium.flow.io.RocksDBSource
import org.alephium.flow.platform.PlatformConfigFixture
import org.alephium.util.{AlephiumActorSpec, AlephiumSpec}

trait AlephiumFlowSpec extends AlephiumSpec with PlatformConfigFixture with BeforeAndAfter {
  after {
    TestUtils.clear(config.storages.blockStorage.folder)
    RocksDBSource.dESTROY(config.storages.headerStorage.storage)
  }
}

class AlephiumFlowActorSpec(name: String) extends AlephiumActorSpec(name) with AlephiumFlowSpec
