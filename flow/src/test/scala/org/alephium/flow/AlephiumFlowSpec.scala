package org.alephium.flow

import scala.language.implicitConversions

import akka.actor.ActorRef
import org.scalatest.BeforeAndAfterAll

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.util.{ActorRefT, AlephiumActorSpec, AlephiumSpec, NumericHelpers}

trait AlephiumFlowSpec
    extends AlephiumSpec
    with AlephiumConfigFixture
    with StoragesFixture
    with BeforeAndAfterAll
    with NumericHelpers {
  implicit def safeActor[T](ref: ActorRef): ActorRefT[T] = ActorRefT(ref)

  lazy val blockFlow: BlockFlow = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)

  def genesisBlockFlow(): BlockFlow = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
  def storageBlockFlow(): BlockFlow = BlockFlow.fromStorageUnsafe(storages, config.genesisBlocks)

  override def afterAll(): Unit = {
    cleanStorages()
  }
}

class AlephiumFlowActorSpec(name: String) extends AlephiumActorSpec(name) with AlephiumFlowSpec
