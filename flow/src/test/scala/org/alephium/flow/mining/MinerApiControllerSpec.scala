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

package org.alephium.flow.mining

import scala.util.Random

import akka.actor.ActorRef
import akka.io.{IO, Tcp}
import akka.testkit.{TestActor, TestProbe}
import akka.util.ByteString

import org.alephium.flow.FlowFixture
import org.alephium.flow.handler.{BlockChainHandler, TestUtils, ViewHandler}
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.flow.validation.InvalidBlockVersion
import org.alephium.protocol.Generators
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Block, ChainIndex, Target}
import org.alephium.serde.{avectorSerde, deserialize, serialize}
import org.alephium.util.{AlephiumActorSpec, AVector, Duration, SocketUtil, TimeStamp}

class MinerApiControllerSpec extends AlephiumActorSpec with SocketUtil {
  trait Fixture extends FlowFixture {
    val apiPort                         = generatePort()
    val (allHandlers, allHandlerProbes) = TestUtils.createAllHandlersProbe
    val minerApiController =
      newTestActorRef[MinerApiController](
        MinerApiController.props(blockFlow, allHandlers)(
          brokerConfig,
          networkConfig.copy(minerApiPort = apiPort),
          miningSetting
        )
      )
    val minerApiControllerActor = minerApiController.underlyingActor
    val bindAddress             = minerApiControllerActor.apiAddress

    def connectToServer(probe: TestProbe): ActorRef = {
      probe.send(IO(Tcp), Tcp.Connect(bindAddress))
      allHandlerProbes.viewHandler.expectMsg(ViewHandler.Subscribe)
      eventually(minerApiControllerActor.pendings.length is 0)
      probe.expectMsgType[Tcp.Connected]
      val connection = probe.lastSender
      probe.reply(Tcp.Register(probe.ref))
      connection
    }

    val minerAddresses =
      AVector.tabulate(groups0)(g => getGenesisLockupScript(ChainIndex.unsafe(g, 0)))
  }

  trait SyncedFixture extends Fixture {
    allHandlerProbes.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case ViewHandler.Subscribe =>
          sender ! ViewHandler.SubscribeResult(succeeded = true)
          TestActor.KeepRunning
      }
    )
  }

  it should "accept new connections" in new SyncedFixture {
    connectToServer(TestProbe())
    eventually(minerApiController.underlyingActor.connections.length is 1)
    connectToServer(TestProbe())
    eventually(minerApiController.underlyingActor.connections.length is 2)
  }

  it should "broadcast new template" in new SyncedFixture {
    val probe0 = TestProbe()
    val probe1 = TestProbe()
    connectToServer(probe0)
    connectToServer(probe1)

    minerApiController ! ViewHandler.NewTemplates(
      ViewHandler.prepareTemplates(blockFlow, minerAddresses).rightValue
    )
    probe0.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value.payload is a[Jobs]
    }
    probe1.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value.payload is a[Jobs]
    }
  }

  trait SubmissionFixture extends SyncedFixture {
    val probe0      = TestProbe()
    val connection0 = connectToServer(probe0)

    val chainIndex  = ChainIndex.unsafe(0, 0)
    val block       = emptyBlock(blockFlow, chainIndex)
    val parentHash  = block.blockDeps.parentHash(chainIndex)
    val blockHeight = blockFlow.getHeightUnsafe(parentHash) + 1

    val blockFlowTemplate = BlockFlowTemplate.from(block, blockHeight)
    val headerBlob        = Job.from(blockFlowTemplate).headerBlob

    def blockRejected(block: Block, blockBlob: ByteString, errorMessage: String) = {
      expectErrorMsg(errorMessage) {
        connection0 ! Tcp.Write(
          ClientMessage.serialize(ClientMessage.from(SubmitBlock(blockBlob)))
        )
      }

      probe0.expectMsgPF() { case Tcp.Received(data) =>
        val chainIndex = block.chainIndex
        ServerMessage.deserialize(data).rightValue.value is ServerMessage.from(
          SubmitResult(chainIndex.from.value, chainIndex.to.value, block.hash, false)
        )
      }
    }

    def blockAccepted(block: Block, blockBlob: ByteString) = {
      connection0 ! Tcp.Write(
        ClientMessage.serialize(ClientMessage.from(SubmitBlock(blockBlob)))
      )

      eventually(minerApiController.underlyingActor.submittingBlocks.contains(block.hash))
      allHandlerProbes.blockHandlers(chainIndex).expectMsgType[BlockChainHandler.ValidateMinedBlock]
    }
  }

  it should "error when the job is not in the cache" in new SubmissionFixture {
    val newBlock  = block.copy(transactions = AVector.empty)
    val blockBlob = serialize(newBlock)

    blockRejected(newBlock, blockBlob, "The job for the block is expired")
  }
  it should "error when the mined block has invalid chain index" in new SubmissionFixture {
    val newBlock      = block.copy(header = block.header.copy(target = Target.Zero))
    val newBlockBlob  = serialize(newBlock.copy(transactions = AVector.empty))
    val newChainIndex = deserialize[Block](newBlockBlob).rightValue.chainIndex
    val invalidChainIndex =
      ChainIndex.unsafe((newChainIndex.flattenIndex + 1) % brokerConfig.chainNum)
    val newTemplate =
      BlockFlowTemplate.from(newBlock, blockHeight).copy(index = invalidChainIndex)
    val newHeaderBlob = Job.fromWithoutTxs(newTemplate).headerBlob
    val cacheKey      = MinerApiController.getCacheKey(newHeaderBlob)
    minerApiController.underlyingActor.jobCache
      .put(cacheKey, newTemplate -> serialize(newTemplate.transactions))

    blockRejected(newBlock, newBlockBlob, "The mined block has invalid chainindex:")
    minerApiController.underlyingActor.jobCache.contains(cacheKey) is true
  }

  it should "error when the mined block has invalid work" in new SubmissionFixture {
    val newBlock      = block.copy(header = block.header.copy(target = Target.Zero))
    val newBlockBlob  = serialize(newBlock.copy(transactions = AVector.empty))
    val newTemplate   = BlockFlowTemplate.from(newBlock, blockHeight)
    val newHeaderBlob = Job.fromWithoutTxs(newTemplate).headerBlob
    val cacheKey      = MinerApiController.getCacheKey(newHeaderBlob)
    minerApiController.underlyingActor.jobCache
      .put(cacheKey, newTemplate -> serialize(newTemplate.transactions))

    blockRejected(newBlock, newBlockBlob, "The mined block has invalid work:")
    minerApiController.underlyingActor.jobCache.contains(cacheKey) is true
  }

  it should "error when the protocol version is invalid" in new SubmissionFixture {
    val blockBlob = serialize(block)

    expectErrorMsg("Invalid mining protocol version: got 2, expect 1") {
      val message = ClientMessage(MiningProtocolVersion(2), SubmitBlock(blockBlob))
      connection0 ! Tcp.Write(ClientMessage.serialize(message))
    }
  }

  it should "submit block when the job is cached" in new SubmissionFixture {
    val cacheKey = MinerApiController.getCacheKey(headerBlob)
    minerApiController.underlyingActor.jobCache
      .put(cacheKey, blockFlowTemplate -> serialize(blockFlowTemplate.transactions))

    val blockBlob = serialize(block.copy(transactions = AVector.empty))
    blockAccepted(block, blockBlob)

    val succeeded = Random.nextBoolean()
    val feedback = if (succeeded) {
      BlockChainHandler.BlockAdded(block.hash)
    } else {
      BlockChainHandler.InvalidBlock(block.hash, InvalidBlockVersion)
    }
    minerApiController ! feedback
    probe0.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value is ServerMessage.from(
        SubmitResult(0, 0, block.hash, succeeded)
      )
    }
  }

  it should "get cache key from header blob" in new SubmissionFixture {
    import org.alephium.serde.byteSerde
    val cacheKey = MinerApiController.getCacheKey(headerBlob)
    cacheKey isnot headerBlob

    val header = blockFlowTemplate.dummyHeader()
    serialize(header.version) ++
      serialize(header.blockDeps) ++
      serialize(header.depStateHash) ++
      serialize(header.txsHash) ++
      serialize(header.target) is cacheKey
  }

  it should "accept the block if the timestamp is changed" in new SubmissionFixture {
    val cacheKey = MinerApiController.getCacheKey(headerBlob)
    minerApiController.underlyingActor.jobCache
      .put(cacheKey, blockFlowTemplate -> serialize(blockFlowTemplate.transactions))

    val newHeader    = block.header.copy(timestamp = block.header.timestamp.plusMillisUnsafe(1))
    val newBlock     = block.copy(header = newHeader)
    val reMinedBlock = reMine(blockFlow, chainIndex, newBlock)
    val blockBlob    = serialize(reMinedBlock.copy(transactions = AVector.empty))
    blockAccepted(reMinedBlock, blockBlob)
  }

  it should "remove the job from cache once the block has been submitted" in new SubmissionFixture {
    val cacheKey = MinerApiController.getCacheKey(headerBlob)
    minerApiController.underlyingActor.jobCache
      .put(cacheKey, blockFlowTemplate -> serialize(blockFlowTemplate.transactions))

    val blockBlob = serialize(block.copy(transactions = AVector.empty))
    blockAccepted(block, blockBlob)
    eventually(minerApiController.underlyingActor.jobCache.contains(cacheKey) is false)

    val reMinedBlock     = reMine(blockFlow, chainIndex, block)
    val reMinedBlockBlob = serialize(reMinedBlock.copy(transactions = AVector.empty))
    blockRejected(reMinedBlock, reMinedBlockBlob, "The job for the block is expired")
  }

  trait ConnectionFixture extends Fixture {
    val probe = TestProbe()
    watch(probe.ref)

    probe.send(IO(Tcp), Tcp.Connect(bindAddress))
    eventually(probe.expectMsgType[Tcp.Connected])
    eventually(minerApiController.underlyingActor.pendings.length is 1)
    probe.reply(Tcp.Register(probe.ref))
  }

  it should "close the connection if view handler is not ready" in new ConnectionFixture {
    minerApiController ! ViewHandler.SubscribeResult(false)
    eventually(minerApiController.underlyingActor.pendings.length is 0)
    probe.expectMsgType[Tcp.ErrorClosed]
  }

  it should "close the connection if view handler is not ready after a while" in new ConnectionFixture {
    minerApiController ! ViewHandler.SubscribeResult(true)
    eventually(minerApiController.underlyingActor.pendings.length is 0)
    eventually(minerApiController.underlyingActor.connections.length is 1)

    minerApiController ! ViewHandler.SubscribeResult(false)
    probe.expectMsgType[Tcp.ErrorClosed]
    eventually(minerApiController.underlyingActor.connections.length is 0)
  }

  it should "calculate job index" in {
    Seq((0, 2, 4), (1, 2, 4), (0, 4, 4), (0, 1, 4)).foreach {
      case (_brokerId, _brokerNum, _groups) =>
        val config = new BrokerConfig {
          def brokerId: Int  = _brokerId
          def brokerNum: Int = _brokerNum
          def groups: Int    = _groups
        }
        config.chainIndexes.zipWithIndex.foreach { case (chainIndex, index) =>
          MinerApiController.calcJobIndex(chainIndex)(config) is index
        }
    }
  }

  it should "publish jobs once the new template is received" in new SyncedFixture with Generators {
    import MinerApiController.CachedTemplate
    minerApiControllerActor.latestJobs.isEmpty is true
    val templates = ViewHandler.prepareTemplates(blockFlow, minerAddresses).rightValue
    minerApiController ! ViewHandler.NewTemplates(templates)
    val templates0 = AVector.from(templates.flatten).map(CachedTemplate.apply)
    eventually {
      minerApiControllerActor.latestJobs.isEmpty is false
      minerApiControllerActor.templates.value is templates0
    }

    val probe = TestProbe()
    connectToServer(probe)

    val chainIndex  = chainIndexGenForBroker(brokerConfig).sample.get
    val block       = emptyBlock(blockFlow, chainIndex)
    val newTemplate = BlockFlowTemplate.from(block, 1)
    minerApiController ! ViewHandler.NewTemplate(newTemplate, false)
    probe.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value.payload is a[Jobs]
    }
    eventually(minerApiControllerActor.buildJobsState.isUpdating is false)
    eventually {
      val index        = MinerApiController.calcJobIndex(chainIndex)
      val newTemplates = templates0.replace(index, CachedTemplate(newTemplate))
      minerApiControllerActor.latestJobs.isEmpty is false
      minerApiControllerActor.templates.value is newTemplates
    }
  }

  it should "not publish jobs if using lazy broadcast" in new SyncedFixture {
    import MinerApiController.CachedTemplate
    val templates = ViewHandler.prepareTemplates(blockFlow, minerAddresses).rightValue
    minerApiController ! ViewHandler.NewTemplates(templates)
    val jobs = AVector.from(templates.flatten).map(CachedTemplate.apply)
    eventually(minerApiControllerActor.templates.value is jobs)
    val probe = TestProbe()
    connectToServer(probe)
    probe.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value.payload is a[Jobs]
    }

    val chainIndex0  = ChainIndex.unsafe(0, 0)
    val block0       = emptyBlock(blockFlow, chainIndex0)
    val newTemplate0 = BlockFlowTemplate.from(block0, 1)
    minerApiController ! ViewHandler.NewTemplate(newTemplate0, true)
    eventually {
      val index   = MinerApiController.calcJobIndex(chainIndex0)
      val newJobs = jobs.replace(index, CachedTemplate(newTemplate0))
      minerApiControllerActor.templates.value is newJobs
    }
    probe.expectNoMessage()

    val chainIndex1  = ChainIndex.unsafe(0, 1)
    val block1       = emptyBlock(blockFlow, chainIndex1)
    val newTemplate1 = BlockFlowTemplate.from(block1, 1)
    minerApiController ! ViewHandler.NewTemplate(newTemplate1, false)
    eventually(minerApiControllerActor.buildJobsState.isUpdating is false)
    eventually {
      val index0 = MinerApiController.calcJobIndex(chainIndex0)
      val index1 = MinerApiController.calcJobIndex(chainIndex1)
      val newJobs = jobs
        .replace(index0, CachedTemplate(newTemplate0))
        .replace(index1, CachedTemplate(newTemplate1))
      minerApiControllerActor.templates.value is newJobs
    }
    probe.expectMsgPF() { case Tcp.Received(data) =>
      val jobs = ServerMessage.deserialize(data).rightValue.value.payload.asInstanceOf[Jobs]
      jobs is Jobs(minerApiControllerActor.latestJobs.value)
    }
  }

  trait PublishJobsFixture extends SyncedFixture {
    def createJobsMessage(): ByteString = {
      val templates = ViewHandler.prepareTemplates(blockFlow, minerAddresses).rightValue
      val jobs      = AVector.from(templates.flatten).map(Job.fromWithoutTxs)
      ServerMessage.serialize(ServerMessage.from(Jobs(jobs)))
    }

    val jobsMessage = createJobsMessage()
    val probe       = TestProbe()
    connectToServer(probe)
  }

  it should "publish mining jobs immediately" in new PublishJobsFixture {
    minerApiControllerActor.lastPublishTimestamp.isEmpty is true
    minerApiControllerActor.publishJobs(jobsMessage)
    eventually(probe.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value.payload is a[Jobs]
    })
    eventually(minerApiControllerActor.lastPublishTimestamp.isDefined is true)
  }

  it should "delay publishing mining jobs" in new PublishJobsFixture {
    minerApiControllerActor.lastPublishTimestamp = Some(TimeStamp.now())
    minerApiControllerActor.publishJobs(jobsMessage)
    eventually(minerApiControllerActor.publishJobsTask.isDefined is true)
    eventually(probe.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value.payload is a[Jobs]
    })
    eventually {
      minerApiControllerActor.publishJobsTask.isDefined is false
      minerApiControllerActor.lastPublishTimestamp.isDefined is true
    }
  }

  it should "skip publishing stale mining jobs" in new PublishJobsFixture {
    val newJobsMessage = createJobsMessage()
    newJobsMessage isnot jobsMessage
    minerApiControllerActor.lastPublishTimestamp = Some(TimeStamp.now())
    minerApiControllerActor.publishJobs(jobsMessage)
    minerApiControllerActor.publishJobs(newJobsMessage)
    eventually {
      probe.expectMsgPF() { case Tcp.Received(data) =>
        data is newJobsMessage
      }
      minerApiControllerActor.publishJobsTask.isDefined is false
      minerApiControllerActor.lastPublishTimestamp.isDefined is true
    }
  }

  it should "test calcPublishDelay" in {
    val now = TimeStamp.now()
    MinerApiController.calcPublishDelay(now, now.plusMillisUnsafe(1)) is Some(
      MinerApiController.publishJobsDelay
    )
    MinerApiController.calcPublishDelay(now, now) is Some(MinerApiController.publishJobsDelay)
    MinerApiController.calcPublishDelay(now, now.minusUnsafe(Duration.unsafe(1))) is
      MinerApiController.publishJobsDelay - Duration.unsafe(1)
    MinerApiController.calcPublishDelay(now, now.minusUnsafe(Duration.unsafe(9))) is
      MinerApiController.publishJobsDelay - Duration.unsafe(9)
    MinerApiController.calcPublishDelay(
      now,
      now.minusUnsafe(MinerApiController.publishJobsDelay)
    ) is None
    MinerApiController.calcPublishDelay(
      now,
      now.minusUnsafe(MinerApiController.publishJobsDelay + Duration.unsafe(1))
    ) is None
    MinerApiController.calcPublishDelay(
      now,
      now.minusUnsafe(MinerApiController.publishJobsDelay + Duration.unsafe(2))
    ) is None
    MinerApiController.calcPublishDelay(
      now,
      now.minusUnsafe(MinerApiController.publishJobsDelay.timesUnsafe(2))
    ) is None
  }

  it should "ignore duplicate blocks" in new SubmissionFixture {
    val cacheKey = MinerApiController.getCacheKey(headerBlob)
    minerApiControllerActor.jobCache.put(
      cacheKey,
      blockFlowTemplate -> serialize(blockFlowTemplate.transactions)
    )
    val blockBlob = serialize(block.copy(transactions = AVector.empty))
    blockAccepted(block, blockBlob)
    eventually(minerApiControllerActor.jobCache.contains(cacheKey) is false)

    val newBlock             = emptyBlock(blockFlow, chainIndex)
    val newBlockFlowTemplate = BlockFlowTemplate.from(newBlock, blockHeight)
    val newHeaderBlob        = Job.from(newBlockFlowTemplate).headerBlob
    val newBlockBlob         = serialize(newBlock.copy(transactions = AVector.empty))

    addAndCheck(blockFlow, block)
    val newCacheKey = MinerApiController.getCacheKey(newHeaderBlob)
    newCacheKey isnot cacheKey
    minerApiControllerActor.jobCache.put(
      newCacheKey,
      newBlockFlowTemplate -> serialize(newBlockFlowTemplate.transactions)
    )
    blockRejected(
      newBlock,
      newBlockBlob,
      s"Ignore block ${newBlock.hash.toHexString} because another block from the same mining job is already mined, there will be no mining rewards"
    )
    eventually(minerApiControllerActor.jobCache.contains(newCacheKey) is false)
  }
}
