package org.alephium.protocol.model

import org.scalacheck.Gen

import org.alephium.protocol.config.{CliqueConfig, GroupConfig, GroupConfigFixture}
import org.alephium.util.AlephiumSpec

class BrokerInfoSpec extends AlephiumSpec with ModelGenerators {
  it should "check equality properly" in {
    forAll { (id: Int, groupNumPerBroker: Int) =>
      val address = socketAddressGen.sample.get
      val info0   = BrokerInfo.unsafe(id, groupNumPerBroker, address)
      val info1   = BrokerInfo.unsafe(id, groupNumPerBroker, address)
      info0 is info1
    }
  }

  it should "check if group included" in {
    forAll(Gen.oneOf(2 to 1 << 4)) { _groups =>
      implicit val groupConfig = new GroupConfig { override def groups: Int = _groups }
      forAll(groupNumPerBrokerGen) { _groupNumPerBroker =>
        implicit val cliqueConfig = new CliqueConfig {
          override def brokerNum: Int         = groups / groupNumPerBroker
          override def groupNumPerBroker: Int = _groupNumPerBroker
          override def groups: Int            = _groups
        }
        forAll(brokerInfoGen) { brokerInfo =>
          val count = (0 until _groups).count(brokerInfo.containsRaw)
          count is cliqueConfig.groupNumPerBroker
        }
      }
    }
  }

  it should "check if id is valid" in new GroupConfigFixture { self =>
    override def groups: Int = 4
    forAll(groupNumPerBrokerGen(groupConfig)) { _groupNumPerBroker =>
      val cliqueConfig = new CliqueConfig {
        override def brokerNum: Int         = groups / groupNumPerBroker
        override def groupNumPerBroker: Int = _groupNumPerBroker
        override def groups: Int            = self.groups
      }
      0 until cliqueConfig.brokerNum foreach { id =>
        BrokerInfo.validate(id, _groupNumPerBroker).isRight is true
      }
      cliqueConfig.brokerNum until (2 * cliqueConfig.brokerNum) foreach { id =>
        BrokerInfo.validate(id, _groupNumPerBroker)(cliqueConfig).isRight is false
      }
      -cliqueConfig.brokerNum until 0 foreach { id =>
        BrokerInfo.validate(id, _groupNumPerBroker)(cliqueConfig).isRight is false
      }
    }
  }

  it should "check intersection" in {
    BrokerInfo.intersect(0, 1, 1, 2) is false
    BrokerInfo.intersect(0, 1, 0, 2) is true
    BrokerInfo.intersect(1, 2, 0, 2) is true
    BrokerInfo.intersect(1, 2, 0, 1) is false
  }
}
