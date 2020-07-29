package org.alephium.flow.validation

import org.alephium.flow.core._
import org.alephium.flow.platform.PlatformConfig
import org.alephium.io.IOResult
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, Forest}

abstract class Validation[T <: FlowData, S <: ValidationStatus] {
  def validate(data: T, flow: BlockFlow)(implicit config: PlatformConfig): IOResult[S]

  def validateUntilDependencies(data: T, flow: BlockFlow)(
      implicit config: ConsensusConfig): IOResult[S]

  def validateAfterDependencies(data: T, flow: BlockFlow)(
      implicit config: PlatformConfig): IOResult[S]
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
    data.chainIndex == index && HeaderValidation.checkWorkAmount(data).isRight
  }
}
// scalastyle:on number.of.methods
