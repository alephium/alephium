package org.alephium.flow

import org.alephium.flow.io.RocksDBStorage
import org.alephium.flow.storage.TestUtils
import org.alephium.util.{AlephiumActorSpec, AlephiumSpec}
import org.scalatest.BeforeAndAfter

trait AlephiumFlowSpec extends AlephiumSpec with BeforeAndAfter {
  import PlatformConfig.{env, rootPath}

  val newPath         = rootPath.resolve(this.getClass.getSimpleName)
  implicit val config = new PlatformConfig(env, newPath)

  after {
    TestUtils.clear(config.disk.blockFolder)
    RocksDBStorage.dESTROY(config.headerDB.storage)
  }
}

class AlephiumFlowActorSpec(name: String) extends AlephiumActorSpec(name) with AlephiumFlowSpec
