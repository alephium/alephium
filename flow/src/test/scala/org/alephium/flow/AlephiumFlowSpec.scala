package org.alephium.flow

import org.scalatest.BeforeAndAfter

import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.platform.PlatformConfigFixture
import org.alephium.util.{AlephiumActorSpec, AlephiumSpec}

trait AlephiumFlowSpec
    extends AlephiumSpec
    with PlatformConfigFixture
    with StoragesFixture
    with BeforeAndAfter {
  after {
    cleanStorages()
  }
}

class AlephiumFlowActorSpec(name: String) extends AlephiumActorSpec(name) with AlephiumFlowSpec
