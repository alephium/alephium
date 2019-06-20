package org.alephium.protocol.model

import org.alephium.protocol.config.{CliqueConfig, GroupConfig, GroupConfigFixture}
import org.alephium.util.AlephiumSpec
import org.scalacheck.Gen

class BrokerIdSpec extends AlephiumSpec {
  it should "check if group included" in {
    forAll(Gen.oneOf(2 to 1 << 4)) { _groups =>
      implicit val groupConfig = new GroupConfig { override def groups: Int = _groups }
      forAll(ModelGen.groupNumPerBrokerGen) { _groupNumPerBroker =>
        implicit val cliqueConfig = new CliqueConfig {
          override def brokerNum: Int         = groups / groupNumPerBroker
          override def groupNumPerBroker: Int = _groupNumPerBroker
          override def groups: Int            = _groups
        }
        forAll(ModelGen.brokerIdGen) { brokerId =>
          val count = (0 until _groups).count(brokerId.containsRaw)
          count is cliqueConfig.groupNumPerBroker
        }
      }
    }
  }

  it should "check if id is valid" in new GroupConfigFixture { self =>
    override def groups: Int = 4
    forAll(ModelGen.groupNumPerBrokerGen(groupConfig)) { _groupNumPerBroker =>
      val cliqueConfig = new CliqueConfig {
        override def brokerNum: Int         = groups / groupNumPerBroker
        override def groupNumPerBroker: Int = _groupNumPerBroker
        override def groups: Int            = self.groups
      }
      0 until cliqueConfig.brokerNum foreach { id =>
        BrokerId.validate(id)(cliqueConfig) is true
      }
      cliqueConfig.brokerNum until (2 * cliqueConfig.brokerNum) foreach { id =>
        BrokerId.validate(id)(cliqueConfig) is false
      }
      -cliqueConfig.brokerNum until 0 foreach { id =>
        BrokerId.validate(id)(cliqueConfig) is false
      }
    }
  }

  it should "check intersection" in {
    BrokerId.intersect(0, 1, 1, 2) is false
    BrokerId.intersect(0, 1, 0, 2) is true
    BrokerId.intersect(1, 2, 0, 2) is true
    BrokerId.intersect(1, 2, 0, 1) is false
  }
}
