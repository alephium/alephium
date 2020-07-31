package org.alephium.flow.client

import akka.testkit.TestProbe

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, FlowHandler, TestUtils}
import org.alephium.flow.network.CliqueManager
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.ActorRefT

class MinerSpec extends AlephiumFlowActorSpec("FairMiner") {
  it should "initialize FairMiner" in {
    val cliqueManager        = TestProbe("cliqueManager")
    val flowHandler          = TestProbe("flowHandler")
    val blockFlow: BlockFlow = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
    val allHandlers: AllHandlers =
      AllHandlers.buildWithFlowHandler(system,
                                       ActorRefT(cliqueManager.ref),
                                       blockFlow,
                                       ActorRefT(flowHandler.ref))

    val miner = system.actorOf(Miner.props(blockFlow, allHandlers))

    miner ! Miner.Start

    cliqueManager.expectMsgType[CliqueManager.BroadCastBlock]

    flowHandler.expectMsgType[FlowHandler.Register]

    flowHandler.expectMsgType[FlowHandler.AddBlock]
    flowHandler.expectMsgType[FlowHandler.AddBlock]
    flowHandler.expectMsgType[FlowHandler.AddBlock]

    miner ! Miner.Stop

    flowHandler.expectMsgType[FlowHandler.UnRegister.type]
  }

  it should "ignore handled mining result when it's stopped" in {
    val blockFlow        = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
    val (allHandlers, _) = TestUtils.createBlockHandlersProbe
    val miner            = system.actorOf(Miner.props(blockFlow, allHandlers))

    miner ! Miner.MiningResult(None, ChainIndex.unsafe(0, 0), 0)
  }
}
