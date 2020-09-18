package org.alephium.flow.network.bootstrap

import org.scalacheck.Gen

import org.alephium.protocol.model.ModelGenerators

trait InfoFixture extends ModelGenerators {
  lazy val intraCliqueInfoGen: Gen[IntraCliqueInfo] = {
    for {
      info    <- cliqueInfoGen
      rpcPort <- Gen.posNum[Int]
      wsPort  <- Gen.posNum[Int]
    } yield {
      val peers = info.internalAddresses.mapWithIndex { (address, id) =>
        PeerInfo.unsafe(id, info.groupNumPerBroker, Some(address), address, rpcPort, wsPort)
      }
      IntraCliqueInfo.unsafe(info.id, peers, info.groupNumPerBroker)
    }
  }

  def genIntraCliqueInfo: IntraCliqueInfo = {
    intraCliqueInfoGen.sample.get
  }
}
