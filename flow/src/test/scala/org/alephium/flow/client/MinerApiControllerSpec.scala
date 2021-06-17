// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.client

import scala.util.Random

import akka.actor.{ActorRef, ActorSystem}
import akka.io.{IO, Tcp}
import akka.testkit.{EventFilter, TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.handler.{BlockChainHandler, TestUtils, ViewHandler}
import org.alephium.protocol.model.ChainIndex
import org.alephium.serde.serialize
import org.alephium.util.{AlephiumActorSpec, AVector, SocketUtil}

class MinerApiControllerSpec extends AlephiumFlowActorSpec("MinerApi") with SocketUtil {
  implicit override lazy val system: ActorSystem =
    ActorSystem(name, ConfigFactory.parseString(AlephiumActorSpec.infoConfig))

  trait Fixture {
    val apiPort                         = generatePort()
    val (allHandlers, allHandlerProbes) = TestUtils.createAllHandlersProbe
    val minerApiController = EventFilter.info(start = "Miner API server bound").intercept {
      TestActorRef[MinerApiController](
        MinerApiController.props(allHandlers)(
          brokerConfig,
          networkSetting.copy(minerApiPort = apiPort),
          miningSetting
        )
      )
    }
    val bindAddress = minerApiController.underlyingActor.apiAddress

    def connectToServer(probe: TestProbe): ActorRef = {
      probe.send(IO(Tcp), Tcp.Connect(bindAddress))
      allHandlerProbes.viewHandler.expectMsg(ViewHandler.Subscribe)
      probe.expectMsgType[Tcp.Connected]
      val connection = probe.lastSender
      probe.reply(Tcp.Register(probe.ref))
      connection
    }

    val minerAddresses =
      AVector.tabulate(groups0)(g => getGenesisLockupScript(ChainIndex.unsafe(g, 0)))
  }

  it should "accept new connections" in new Fixture {
    connectToServer(TestProbe())
    minerApiController.underlyingActor.connections.length is 1
    connectToServer(TestProbe())
    minerApiController.underlyingActor.connections.length is 2
  }

  it should "broadcast new template" in new Fixture {
    val probe0 = TestProbe()
    val probe1 = TestProbe()
    connectToServer(probe0)
    connectToServer(probe1)

    minerApiController ! ViewHandler.NewTemplates(
      ViewHandler.prepareTemplates(blockFlow, minerAddresses).rightValue
    )
    probe0.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value is a[Jobs]
    }
    probe1.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value is a[Jobs]
    }
  }

  it should "handle submission" in new Fixture with Eventually {
    val probe0      = TestProbe()
    val connection0 = connectToServer(probe0)

    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = emptyBlock(blockFlow, chainIndex)
    val blockBlob  = serialize(block)
    connection0 ! Tcp.Write(ClientMessage.serialize(SubmitBlock(blockBlob)))

    eventually(minerApiController.underlyingActor.submittingBlocks.contains(block.hash))
    allHandlerProbes.blockHandlers(chainIndex).expectMsgType[BlockChainHandler.Validate]

    val succeeded = Random.nextBoolean()
    val feedback = if (succeeded) {
      BlockChainHandler.BlockAdded(block.hash)
    } else {
      BlockChainHandler.InvalidBlock(block.hash)
    }
    minerApiController ! feedback
    probe0.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value is SubmitResult(0, 0, succeeded)
    }
  }
}
