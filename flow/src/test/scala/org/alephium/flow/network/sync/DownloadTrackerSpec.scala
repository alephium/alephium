package org.alephium.flow.network.sync

import akka.actor.Props
import akka.testkit.TestActorRef

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.protocol.Hash
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.AVector

class DownloadTrackerSpec extends AlephiumFlowActorSpec("DownloadTracker") {
  trait Fixture { F =>
    val blockflow = genesisBlockFlow()
    val hashes = AVector.from((1 to 5).map { k =>
      val block = mine(blockflow, ChainIndex.unsafe(0, 0))
      addAndCheck(blockflow, block, k)
      block.hash
    })

    object TestDownloadTracker {
      def props(): Props = Props(new TestDownloadTracker())
    }

    class TestDownloadTracker extends DownloadTracker {
      override def blockflow: BlockFlow = F.blockflow

      override def receive: Receive = {
        case BlockFlowSynchronizer.SyncInventories(hashes) =>
          download(hashes)
        case BlockFlowSynchronizer.Downloaded(hashes) =>
          downloaded(hashes)
      }
    }

    val downloadTrack = TestActorRef[TestDownloadTracker](TestDownloadTracker.props())

    val randomHashes = AVector.fill(5)(Hash.generate)
  }

  it should "track downloading" in new Fixture {
    hashes.foreach(hash => blockFlow.contains(hash) isE true)

    downloadTrack ! BlockFlowSynchronizer.SyncInventories(AVector(hashes))
    expectMsg(BrokerHandler.DownloadBlocks(AVector.empty[Hash]))
    downloadTrack.underlyingActor.downloading.isEmpty is true

    downloadTrack ! BlockFlowSynchronizer.SyncInventories(AVector(hashes ++ randomHashes))
    expectMsg(BrokerHandler.DownloadBlocks(randomHashes))
    downloadTrack.underlyingActor.downloading.toSet is randomHashes.toSet

    downloadTrack ! BlockFlowSynchronizer.SyncInventories(AVector(hashes ++ randomHashes))
    expectMsg(BrokerHandler.DownloadBlocks(AVector.empty[Hash]))
    downloadTrack.underlyingActor.downloading.toSet is randomHashes.toSet

    downloadTrack ! BlockFlowSynchronizer.Downloaded(hashes)
    downloadTrack.underlyingActor.downloading.toSet is randomHashes.toSet

    downloadTrack ! BlockFlowSynchronizer.Downloaded(hashes ++ randomHashes)
    downloadTrack.underlyingActor.downloading.isEmpty is true
  }
}
