package org.alephium.flow.client

import akka.testkit.TestProbe

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.{AllHandlers, BlockFlow, FlowHandler}
import org.alephium.flow.network.CliqueManager
import org.alephium.util.ActorRefT

class FairMinerSpec extends AlephiumFlowActorSpec("FairMiner") {
  it should "initialize FairMiner" in {
    val cliqueManager        = TestProbe("cliqueManager")
    val flowHandler          = TestProbe("flowHandler")
    val blockFlow: BlockFlow = BlockFlow.fromGenesisUnsafe()
    val allHandlers: AllHandlers =
      AllHandlers.buildWithFlowHandler(system,
                                       ActorRefT(cliqueManager.ref),
                                       blockFlow,
                                       ActorRefT(flowHandler.ref))

    val miner = system.actorOf(FairMiner.props(blockFlow, allHandlers))

    miner ! Miner.Start

    cliqueManager.expectMsgType[CliqueManager.BroadCastBlock]

    flowHandler.expectMsgType[FlowHandler.Register]

    flowHandler.expectMsgType[FlowHandler.AddBlock]
    flowHandler.expectMsgType[FlowHandler.AddBlock]
    flowHandler.expectMsgType[FlowHandler.AddBlock]

    miner ! Miner.Stop

    flowHandler.expectMsgType[FlowHandler.UnRegister.type]
  }
}
