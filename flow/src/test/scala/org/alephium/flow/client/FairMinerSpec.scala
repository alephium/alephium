package org.alephium.flow.client

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.network.clique.BrokerHandler

class FairMinerSpec extends AlephiumFlowActorSpec("FairMiner") {
  it should "initialize FairMiner" in {
    val builder = new BrokerHandler.Builder {}
    val node    = Node(builder, "FairMinerSpec")
    val miner   = system.actorOf(FairMiner.props(node))
    miner ! Miner.Start
    miner ! Miner.Stop
  }
}
