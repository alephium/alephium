package org.alephium.flow.validation

import org.alephium.flow.core._
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, Forest}

abstract class Validation[T <: FlowData, S <: ValidationStatus] {
  implicit def brokerConfig: BrokerConfig
  implicit def consensusConfig: ConsensusConfig

  def validate(data: T, flow: BlockFlow): IOResult[S]

  def validateUntilDependencies(data: T, flow: BlockFlow): IOResult[S]

  def validateAfterDependencies(data: T, flow: BlockFlow): IOResult[S]
}

// scalastyle:off number.of.methods
object Validation {
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def validateFlowDAG[T <: FlowData](datas: AVector[T])(
      implicit config: GroupConfig): Option[AVector[Forest[Hash, T]]] = {
    val splits = datas.splitBy(_.chainIndex)
    val builds = splits.map(ds => Forest.tryBuild[Hash, T](ds, _.hash, _.parentHash))
    if (builds.forall(_.nonEmpty)) Some(builds.map(_.get)) else None
  }

  def validateMined[T <: FlowData](data: T, index: ChainIndex)(
      implicit config: GroupConfig): Boolean = {
    data.chainIndex == index && checkWorkAmount(data)
  }

  protected[validation] def checkWorkAmount[T <: FlowData](data: T): Boolean = {
    val current = BigInt(1, data.hash.bytes.toArray)
    assume(current >= 0)
    current <= data.target
  }
}
// scalastyle:on number.of.methods
