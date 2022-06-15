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

package org.alephium.flow.handler

import akka.actor.ActorSystem
import akka.testkit.{EventFilter, TestActorRef, TestProbe}
import org.scalacheck.Gen
import org.scalatest.concurrent.Eventually.eventually

import org.alephium.flow.{AlephiumFlowActorSpec, FlowFixture}
import org.alephium.flow.core.BlockFlowState
import org.alephium.flow.model.{PersistedTxId, ReadyTxInfo}
import org.alephium.flow.network.InterCliqueManager
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.validation.NonExistInput
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.model._
import org.alephium.serde.serialize
import org.alephium.util.{ActorRefT, AlephiumActorSpec, AVector, Duration, Hex, TimeStamp}

class TxHandlerSpec extends AlephiumFlowActorSpec {

  it should "broadcast valid tx" in new Fixture {
    override val configValues = Map(
      ("alephium.mempool.batch-broadcast-txs-frequency", "500 ms"),
      ("alephium.broker.groups", 4),
      ("alephium.broker.broker-num", 1),
      ("alephium.broker.broker-id", 0)
    )

    def createTx(chainIndex: ChainIndex): Transaction = {
      transferTxs(blockFlow, chainIndex, ALPH.alph(1), 1, None, true, None).head
    }

    setSynced()
    val txs =
      AVector.tabulate(groupConfig.groups)(groupId => createTx(ChainIndex.unsafe(groupId, groupId)))
    txs.length is 4
    txHandler.underlyingActor.txsBuffer.isEmpty is true

    val txs0 = txs.take(2)
    txs0.foreach(txHandler ! addTx(_))
    txs0.foreach(tx => expectMsg(TxHandler.AddSucceeded(tx.id)))
    broadcastTxProbe.expectMsgPF() { case InterCliqueManager.BroadCastTx(hashes) =>
      hashes.length is 2
      hashes.contains((ChainIndex.unsafe(0, 0), AVector(txs0.head.id))) is true
      hashes.contains((ChainIndex.unsafe(1, 1), AVector(txs0.last.id))) is true
    }
    // use eventually here to avoid test failure on windows
    eventually(txHandler.underlyingActor.txsBuffer.isEmpty is true)

    val txs1 = txs.drop(2)
    txs1.foreach(txHandler ! addTx(_))
    txs1.foreach(tx => expectMsg(TxHandler.AddSucceeded(tx.id)))
    broadcastTxProbe.expectMsgPF() { case InterCliqueManager.BroadCastTx(hashes) =>
      hashes.length is 2
      hashes.contains((ChainIndex.unsafe(2, 2), AVector(txs1.head.id))) is true
      hashes.contains((ChainIndex.unsafe(3, 3), AVector(txs1.last.id))) is true
    }
    // use eventually here to avoid test failure on windows
    eventually(txHandler.underlyingActor.txsBuffer.isEmpty is true)

    // won't broadcast when there are no txs in buffer
    broadcastTxProbe.expectNoMessage()
  }

  it should "not broadcast invalid tx" in new Fixture {
    setSynced()
    val tx = transactionGen(chainIndexGen = Gen.const(chainIndex)).sample.get
    txHandler ! addTx(tx)
    expectMsg(
      TxHandler.AddFailed(
        tx.id,
        s"Failed in validating tx ${tx.id.toHexString} due to ${NonExistInput}: ${hex(tx)}"
      )
    )
    broadcastTxProbe.expectNoMessage()
  }

  it should "delay txs broadcast if the dependent outputs persisted a short time ago" in new Fixture {
    override val configValues = Map(
      ("alephium.network.txs-broadcast-delay", "1 s"),
      ("alephium.mempool.batch-broadcast-txs-frequency", "200 ms")
    )

    val (privKey0, pubKey0)    = GroupIndex.unsafe(0).generateKey
    val (privKey1, pubKey1, _) = genesisKeys(0)
    val block0                 = transfer(blockFlow, privKey1, pubKey0, ALPH.alph(3))
    val tx0                    = block0.nonCoinbase.head.toTemplate
    val mempool                = blockFlow.getMemPool(tx0.chainIndex)

    setSynced()
    txHandler ! TxHandler.AddToSharedPool(AVector(tx0))
    expectMsg(TxHandler.AddSucceeded(tx0.id))
    mempool.getSharedPool(tx0.chainIndex).contains(tx0.id) is true
    txHandler.underlyingActor.txsBuffer.contains(tx0) is true
    txHandler.underlyingActor.delayedTxs.contains(tx0) is false

    addAndCheck(blockFlow, block0)
    val block1     = transfer(blockFlow, privKey0, pubKey1, ALPH.alph(1))
    val tx1        = block1.nonCoinbase.head.toTemplate
    val worldState = blockFlow.getBestPersistedWorldState(tx1.chainIndex.from).rightValue

    worldState.existOutput(tx1.unsigned.inputs.head.outputRef) isE true
    txHandler ! TxHandler.AddToSharedPool(AVector(tx1))
    expectMsg(TxHandler.AddSucceeded(tx1.id))
    mempool.getSharedPool(tx1.chainIndex).contains(tx1.id) is true
    txHandler.underlyingActor.txsBuffer.contains(tx1) is false
    txHandler.underlyingActor.delayedTxs.contains(tx1) is true

    broadcastTxProbe.expectMsg(
      InterCliqueManager.BroadCastTx(AVector(tx0.chainIndex -> AVector(tx0.id)))
    )
    txHandler.underlyingActor.txsBuffer.isEmpty is true
    txHandler.underlyingActor.delayedTxs.contains(tx1) is true
    broadcastTxProbe.expectMsg(
      InterCliqueManager.BroadCastTx(AVector(tx1.chainIndex -> AVector(tx1.id)))
    )
    txHandler.underlyingActor.delayedTxs.isEmpty is true
  }

  it should "delay txs broadcast if the dependent outputs in block cache" in new Fixture {
    override val configValues = Map(
      ("alephium.broker.broker-num", 1),
      ("alephium.network.txs-broadcast-delay", "1 s"),
      ("alephium.mempool.batch-broadcast-txs-frequency", "200 ms")
    )

    val (privKey0, pubKey0, _) = genesisKeys(0)
    val (privKey1, pubKey1)    = GroupIndex.unsafe(1).generateKey
    val block0                 = transfer(blockFlow, privKey0, pubKey1, ALPH.alph(3))
    addAndCheck(blockFlow, block0)
    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))

    val block1     = transfer(blockFlow, privKey1, pubKey0, ALPH.alph(2))
    val tx         = block1.nonCoinbase.head.toTemplate
    val worldState = blockFlow.getBestPersistedWorldState(block0.chainIndex.from).rightValue

    setSynced()
    worldState.existOutput(tx.unsigned.inputs.head.outputRef) isE false
    txHandler ! TxHandler.AddToSharedPool(AVector(tx))
    expectMsg(TxHandler.AddSucceeded(tx.id))
    txHandler.underlyingActor.txsBuffer.contains(tx) is false
    txHandler.underlyingActor.delayedTxs.contains(tx) is true
    broadcastTxProbe.expectMsg(
      InterCliqueManager.BroadCastTx(AVector(tx.chainIndex -> AVector(tx.id)))
    )
    txHandler.underlyingActor.delayedTxs.isEmpty is true
  }

  it should "load persisted pending txs only once when node synced" in new FlowFixture {
    implicit lazy val system = createSystem(Some(AlephiumActorSpec.infoConfig))
    val txHandler = TestActorRef[TxHandler](
      TxHandler.props(blockFlow, storages.pendingTxStorage, storages.readyTxStorage)
    )

    EventFilter.info(start = "Start to load", occurrences = 0).intercept {
      txHandler ! InterCliqueManager.SyncedResult(false)
    }

    EventFilter.info(start = "Start to load", occurrences = 1).intercept {
      txHandler ! InterCliqueManager.SyncedResult(true)
    }

    EventFilter.info(start = "Start to load", occurrences = 0).intercept {
      txHandler ! InterCliqueManager.SyncedResult(true)
      txHandler ! InterCliqueManager.SyncedResult(true)
    }
  }

  trait PersistenceFixture extends Fixture {
    val (privKey0, pubKey0, _) = genesisKeys(0)
    val (privKey1, pubKey1)    = chainIndex.from.generateKey
    val mempool                = blockFlow.getMemPool(chainIndex)
    val sharedPool             = mempool.getSharedPool(chainIndex)
    val pendingPool            = mempool.pendingPool
    val pendingTxStorage       = storages.pendingTxStorage
    val readyTxStorage         = storages.readyTxStorage

    lazy val block0 = transfer(blockFlow, privKey0, pubKey1, ALPH.alph(4))
    lazy val tx0    = block0.firstTx
    lazy val block1 = transfer(blockFlow, privKey1, pubKey0, ALPH.alph(1))
    lazy val tx1    = block1.firstTx
    lazy val block2 = transfer(blockFlow, privKey1, pubKey0, ALPH.alph(1))
    lazy val tx2    = block2.firstTx

    implicit class FirstTxOfBlock(block: Block) {
      def firstTx: TransactionTemplate = block.nonCoinbase.head.toTemplate
    }

    def addReadyTx(tx: TransactionTemplate) = {
      txHandler ! TxHandler.AddToSharedPool(AVector(tx))
      sharedPool.contains(tx.id) is true
      txHandler.underlyingActor.txsBuffer.contains(tx) is true
      broadcastTxProbe.expectMsg(
        InterCliqueManager.BroadCastTx(AVector(chainIndex -> AVector(tx.id)))
      )
    }

    def addPendingTx(tx: TransactionTemplate): PersistedTxId = {
      txHandler ! TxHandler.AddToGrandPool(AVector(tx))
      pendingPool.contains(tx.id) is true
      val timestamp     = pendingPool.timestamps.unsafe(tx.id)
      val persistedTxId = PersistedTxId(timestamp, tx.id)
      pendingTxStorage.get(persistedTxId) isE tx
      persistedTxId
    }

    def removeReadyTxs(txs: AVector[TransactionTemplate]) = {
      sharedPool.remove(txs) is txs.length
      txs.foreach(tx => sharedPool.contains(tx.id) is false)
    }

    def removePendingTxs(txs: AVector[TransactionTemplate]) = {
      pendingPool.remove(txs)
      txs.foreach(tx => pendingPool.contains(tx.id) is false)
    }

    setSynced()
  }

  it should "persist pending txs" in new PersistenceFixture {
    addReadyTx(tx0)
    val persistedTxId = addPendingTx(tx1)
    info("Pending tx becomes ready")
    readyTxStorage.exists(tx1.id) isE false
    txHandler ! TxHandler.Broadcast(AVector(tx1 -> persistedTxId.timestamp))
    txHandler.underlyingActor.delayedTxs.contains(tx1) is true
    pendingTxStorage.get(persistedTxId) isE tx1
    readyTxStorage.get(tx1.id) isE ReadyTxInfo(tx1.chainIndex, persistedTxId.timestamp)

    info("Remove pending tx from storage when confirmed")
    addAndCheck(blockFlow, block0)
    blockFlow.isTxConfirmed(tx1.id, tx1.chainIndex) isE false
    val newBlock = mineWithoutCoinbase(blockFlow, chainIndex, block1.nonCoinbase, block1.timestamp)
    addAndCheck(blockFlow, newBlock)
    blockFlow.isTxConfirmed(tx1.id, tx1.chainIndex) isE true
    txHandler ! TxHandler.CleanPendingPool
    pendingTxStorage.exists(persistedTxId) isE false
    readyTxStorage.exists(tx1.id) isE false
  }

  it should "load persisted pending txs" in new PersistenceFixture {
    addReadyTx(tx0)
    val persistedTxId1 = addPendingTx(tx1)
    val persistedTxId2 = addPendingTx(tx2)
    removeReadyTxs(AVector(tx0))
    removePendingTxs(AVector(tx1, tx2))
    sharedPool.txs.isEmpty is true
    pendingPool.txs.isEmpty is true

    info("Remove invalid persisted pending txs from storage")
    txHandler.underlyingActor.clearStorageAndLoadTxs()
    sharedPool.txs.isEmpty is true
    pendingPool.txs.isEmpty is true
    pendingTxStorage.exists(persistedTxId1) isE false
    pendingTxStorage.exists(persistedTxId2) isE false

    pendingTxStorage.put(persistedTxId1, tx1) isE ()
    pendingTxStorage.put(persistedTxId2, tx2) isE ()
    addAndCheck(blockFlow, block0)
    txHandler.underlyingActor.clearStorageAndLoadTxs()

    info("Load persisted pending tx to shared pool")
    sharedPool.contains(tx1.id) is true
    pendingPool.contains(tx1.id) is false
    txHandler.underlyingActor.delayedTxs.contains(tx1) is true
    pendingTxStorage.get(persistedTxId1) isE tx1
    readyTxStorage.get(tx1.id) isE ReadyTxInfo(tx1.chainIndex, persistedTxId1.timestamp)

    info("Load persisted pending tx to pending pool")
    sharedPool.contains(tx2.id) is false
    pendingPool.contains(tx2.id) is true
    val timestamp         = pendingPool.timestamps.unsafe(tx2.id)
    val newPersistedTxId2 = PersistedTxId(timestamp, tx2.id)
    pendingTxStorage.get(newPersistedTxId2) isE tx2
    pendingTxStorage.exists(persistedTxId2) isE false
    readyTxStorage.exists(tx2.id) isE false
  }

  it should "cleanup storages if ready tx is invalid" in new PersistenceFixture {
    addReadyTx(tx0)

    // create a forked chain
    val blockFlow0   = isolatedBlockFlow()
    val forkedBlock0 = emptyBlock(blockFlow0, chainIndex)
    addAndCheck(blockFlow0, forkedBlock0)
    val forkedBlock1 = emptyBlock(blockFlow0, chainIndex)
    addAndCheck(blockFlow0, forkedBlock1)

    val persistedTxId1 = addPendingTx(tx1)
    addAndCheck(blockFlow, block0)
    sharedPool.contains(tx1.id) is true
    pendingPool.contains(tx1.id) is false

    // update timestamp because shared pool only check old txs
    val expiredTs = TimeStamp.now().minusUnsafe(memPoolSetting.cleanSharedPoolFrequency)
    sharedPool.timestamps.put(tx1.id, expiredTs)
    txHandler ! TxHandler.Broadcast(AVector(tx1 -> persistedTxId1.timestamp))
    readyTxStorage.get(tx1.id) isE ReadyTxInfo(tx1.chainIndex, persistedTxId1.timestamp)
    pendingTxStorage.get(persistedTxId1) isE tx1

    // tx1 is invalid because of reorg
    addAndCheck(blockFlow, forkedBlock0)
    addAndCheck(blockFlow, forkedBlock1)
    txHandler ! TxHandler.CleanSharedPool
    readyTxStorage.exists(tx1.id) isE false
    pendingTxStorage.exists(persistedTxId1) isE false
  }

  it should "remove invalid txs from storage when clean up pending pool" in new PersistenceFixture {
    addReadyTx(tx0)

    {
      info("Pending txs are valid")
      val persistedTxId1 = addPendingTx(tx1)
      val persistedTxId2 = addPendingTx(tx2)
      txHandler ! TxHandler.CleanPendingPool
      eventually {
        pendingTxStorage.get(persistedTxId1) isE tx1
        pendingTxStorage.get(persistedTxId2) isE tx2
        pendingPool.contains(tx1.id) is true
        pendingPool.contains(tx2.id) is true
      }

      info("Remove invalid tx(tx2)")
      removePendingTxs(AVector(tx1))
      txHandler ! TxHandler.CleanPendingPool
      eventually {
        pendingTxStorage.exists(persistedTxId2) isE false
        pendingPool.contains(tx2.id) is false
      }
    }

    {
      info("Remove invalid txs(tx1 & tx2)")
      val persistedTxId1 = addPendingTx(tx1)
      val persistedTxId2 = addPendingTx(tx2)
      removeReadyTxs(AVector(tx0))
      txHandler ! TxHandler.CleanPendingPool
      eventually {
        pendingTxStorage.exists(persistedTxId1) isE false
        pendingTxStorage.exists(persistedTxId2) isE false
        pendingPool.contains(tx1.id) is false
        pendingPool.contains(tx2.id) is false
      }
    }
  }

  it should "fail in case of duplicate txs" in new Fixture {
    override val configValues = Map(("alephium.mempool.batch-broadcast-txs-frequency", "200 ms"))

    val tx = transferTxs(blockFlow, chainIndex, ALPH.alph(1), 1, None, true, None).head

    setSynced()
    txHandler ! addTx(tx)
    expectMsg(TxHandler.AddSucceeded(tx.id))
    broadcastTxProbe.expectMsg(
      InterCliqueManager.BroadCastTx(AVector((chainIndex, AVector(tx.id))))
    )

    EventFilter.warning(pattern = ".*already existed.*").intercept {
      txHandler ! addTx(tx)
      expectMsg(TxHandler.AddFailed(tx.id, s"tx ${tx.id.toHexString} is already included"))
      broadcastTxProbe.expectNoMessage()
    }
  }

  it should "fail in double-spending" in new Fixture {
    override val configValues = Map(("alephium.mempool.batch-broadcast-txs-frequency", "200 ms"))

    val tx0 = transferTxs(blockFlow, chainIndex, ALPH.alph(1), 1, None, true, None).head
    val tx1 = transferTxs(blockFlow, chainIndex, ALPH.alph(2), 1, None, true, None).head

    setSynced()
    txHandler ! addTx(tx0)
    expectMsg(TxHandler.AddSucceeded(tx0.id))
    broadcastTxProbe.expectMsg(
      InterCliqueManager.BroadCastTx(AVector((chainIndex, AVector(tx0.id))))
    )

    EventFilter.warning(pattern = ".*double spending.*").intercept {
      txHandler ! addTx(tx1)
      expectMsg(
        TxHandler.AddFailed(tx1.id, s"tx ${tx1.id.shortHex} is double spending: ${hex(tx1)}")
      )
      broadcastTxProbe.expectNoMessage()
    }
  }

  it should "download txs" in new Fixture {
    override val configValues = Map(
      ("alephium.mempool.batch-broadcast-txs-frequency", "200 ms"),
      ("alephium.broker.groups", 4),
      ("alephium.broker.broker-num", 1),
      ("alephium.broker.broker-id", 0)
    )

    def sendAnnouncement(
        chainIndex: ChainIndex,
        hash: Hash,
        haveAnnouncement: Boolean
    ): (TestProbe, AVector[(ChainIndex, AVector[Hash])]) = {
      val brokerHandler = TestProbe()
      val announcement  = TxHandler.Announcement(ActorRefT(brokerHandler.ref), chainIndex, hash)
      brokerHandler.send(txHandler, TxHandler.TxAnnouncements(AVector((chainIndex, AVector(hash)))))
      txHandler.underlyingActor.fetching.states.contains(hash) is true
      txHandler.underlyingActor.announcements.contains(announcement) is haveAnnouncement
      brokerHandler -> AVector(chainIndex -> AVector(hash))
    }

    val chain01     = ChainIndex.unsafe(0, 1)
    val txHash1     = Hash.generate
    val maxCapacity = (brokerConfig.groupNumPerBroker * brokerConfig.groups * 10) * 32

    setSynced()
    txHandler.underlyingActor.maxCapacity is maxCapacity
    txHandler.underlyingActor.announcements.isEmpty is true

    (0 until TxHandler.MaxDownloadTimes)
      .map(_ => sendAnnouncement(chain01, txHash1, true))
      .foreach { case (brokerHandler, hashes) =>
        brokerHandler.expectMsg(BrokerHandler.DownloadTxs(hashes))
      }
    eventually(txHandler.underlyingActor.announcements.isEmpty is true)

    val (brokerHandler, _) = sendAnnouncement(chain01, txHash1, false)
    brokerHandler.expectNoMessage()
    txHandler.underlyingActor.announcements.isEmpty is true

    val chain02 = ChainIndex.unsafe(0, 2)
    val chain03 = ChainIndex.unsafe(0, 3)
    val tx2     = transactionGen(chainIndexGen = Gen.const(chain02)).sample.get.toTemplate
    val txHash3 = Hash.generate
    val txHash4 = Hash.generate
    val mempool = blockFlow.getMemPool(chain02)
    mempool.contains(chain02, tx2.id) is false
    mempool.addNewTx(chain02, tx2, TimeStamp.now())
    mempool.contains(chain02, tx2.id) is true

    txHandler ! TxHandler.TxAnnouncements(
      AVector(
        (chain01, AVector(txHash1, txHash3)),
        (chain02, AVector(tx2.id))
      )
    )
    txHandler ! TxHandler.TxAnnouncements(
      AVector(
        (chain03, AVector(txHash4))
      )
    )
    expectMsg(
      BrokerHandler.DownloadTxs(
        AVector(
          (chain01, AVector(txHash3)),
          (chain03, AVector(txHash4))
        )
      )
    )
    eventually(txHandler.underlyingActor.announcements.isEmpty is true)
  }

  trait PeriodicTaskFixture extends FlowFixture {
    implicit lazy val system: ActorSystem = createSystem(Some(AlephiumActorSpec.debugConfig))

    def test(message: String) = {
      EventFilter.debug(message, occurrences = 5).intercept {
        val txHandler = system.actorOf(
          TxHandler.props(blockFlow, storages.pendingTxStorage, storages.readyTxStorage)
        )
        txHandler ! InterCliqueManager.SyncedResult(true)
      }
    }
  }

  it should "broadcast txs regularly" in new PeriodicTaskFixture {
    override val configValues = Map(("alephium.mempool.batch-broadcast-txs-frequency", "300 ms"))

    test("Start to broadcast txs")
  }

  it should "download txs regularly" in new PeriodicTaskFixture {
    override val configValues = Map(("alephium.mempool.batch-download-txs-frequency", "300 ms"))

    test("Start to download txs")
  }

  it should "clean shared pools regularly" in new PeriodicTaskFixture {
    override val configValues = Map(("alephium.mempool.clean-shared-pool-frequency", "300 ms"))

    test("Start to clean shared pools")
  }

  it should "clean pending pools regularly" in new PeriodicTaskFixture {
    override val configValues = Map(("alephium.mempool.clean-pending-pool-frequency", "300 ms"))

    test("Start to clean pending pools")
  }

  it should "reject tx with low gas price" in new Fixture {
    val tx            = transactionGen().sample.get
    val lowGasPriceTx = tx.copy(unsigned = tx.unsigned.copy(gasPrice = minimalGasPrice))

    txHandler ! addTx(lowGasPriceTx)
    expectMsg(
      TxHandler.AddFailed(lowGasPriceTx.id, s"tx has lower gas price than ${defaultGasPrice}")
    )
  }

  it should "check gas price" in new Fixture {
    val tx            = transactionGen().sample.get.toTemplate
    val lowGasPriceTx = tx.copy(unsigned = tx.unsigned.copy(gasPrice = minimalGasPrice))
    TxHandler.checkHighGasPrice(tx) is true
    TxHandler.checkHighGasPrice(lowGasPriceTx) is false
    TxHandler.checkHighGasPrice(
      ALPH.LaunchTimestamp.plusUnsafe(Duration.ofDaysUnsafe(366 + 365 / 2)),
      lowGasPriceTx
    ) is true
  }

  it should "mine new block if auto-mine is enabled" in new Fixture {
    override val configValues = Map(("alephium.mempool.auto-mine-for-dev", true))
    config.mempool.autoMineForDev is true

    val block = transfer(blockFlow, chainIndex)
    val tx    = block.transactions.head
    txHandler ! addTx(tx)
    expectMsg(TxHandler.AddSucceeded(tx.id))
    blockFlow.getMemPool(chainIndex).size is 0

    val status = blockFlow.getTxStatus(tx.id, chainIndex).rightValue.get
    status is a[BlockFlowState.Confirmed]
    val confirmed = status.asInstanceOf[BlockFlowState.Confirmed]
    confirmed.chainConfirmations is 1
    confirmed.fromGroupConfirmations is 1
    confirmed.toGroupConfirmations is 1
    val blockHash = confirmed.index.hash
    blockFlow.getBestDeps(chainIndex.from).deps.contains(blockHash) is true
  }

  it should "mine new block for inter-group chain if auto-mine is enabled" in new Fixture {
    override val configValues =
      Map(("alephium.broker.broker-num", 1), ("alephium.mempool.auto-mine-for-dev", true))
    config.mempool.autoMineForDev is true

    val index            = ChainIndex.unsafe(0, 1)
    val (privKey0, _, _) = genesisKeys(0)
    val (_, pubKey1, _)  = genesisKeys(1)
    val genesisAddress0  = getGenesisLockupScript(index.from)
    val genesisAddress1  = getGenesisLockupScript(index.to)
    val balance0         = blockFlow.getBalance(genesisAddress0, Int.MaxValue).rightValue._1
    val balance1         = blockFlow.getBalance(genesisAddress1, Int.MaxValue).rightValue._1

    val block = transfer(blockFlow, privKey0, pubKey1, ALPH.oneAlph)
    val tx    = block.transactions.head
    txHandler ! addTx(tx)
    expectMsg(TxHandler.AddSucceeded(tx.id))
    blockFlow.getMemPool(index).size is 0

    val status = blockFlow.getTxStatus(tx.id, index).rightValue.get
    status is a[BlockFlowState.Confirmed]
    val confirmed = status.asInstanceOf[BlockFlowState.Confirmed]
    confirmed.chainConfirmations is 1
    confirmed.fromGroupConfirmations is 1
    confirmed.toGroupConfirmations is 0
    val blockHash = confirmed.index.hash
    blockFlow.getBestDeps(index.from).deps.contains(blockHash) is true

    val balance01 = blockFlow.getBalance(genesisAddress0, Int.MaxValue).rightValue._1
    val balance11 = blockFlow.getBalance(genesisAddress1, Int.MaxValue).rightValue._1
    (balance01 < balance0.subUnsafe(ALPH.oneAlph)) is true // due to gas fee
    balance11 is balance1.addUnsafe(ALPH.oneAlph)

    val block0 = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    val block1 = transfer(blockFlow, ChainIndex.unsafe(1, 1))
    addAndCheck(blockFlow, block0)
    addAndCheck(blockFlow, block1)
    val balance02 = blockFlow.getBalance(genesisAddress0, Int.MaxValue).rightValue._1
    val balance12 = blockFlow.getBalance(genesisAddress1, Int.MaxValue).rightValue._1
    balance02 is balance01.subUnsafe(ALPH.oneAlph)
    balance12 is balance11.subUnsafe(ALPH.oneAlph)
  }

  trait Fixture extends FlowFixture with TxGenerators {
    // use lazy here because we want to override config values
    lazy val chainIndex = ChainIndex.unsafe(0, 0)
    lazy val txHandler =
      TestActorRef[TxHandler](
        TxHandler.props(blockFlow, storages.pendingTxStorage, storages.readyTxStorage)
      )

    def addTx(tx: Transaction) = TxHandler.AddToSharedPool(AVector(tx.toTemplate))
    def hex(tx: Transaction)   = Hex.toHexString(serialize(tx.toTemplate))
    def setSynced()            = txHandler ! InterCliqueManager.SyncedResult(true)

    val broadcastTxProbe = TestProbe()
    system.eventStream.subscribe(broadcastTxProbe.ref, classOf[InterCliqueManager.BroadCastTx])
  }
}
