package org.alephium.protocol.message

import org.scalacheck.Gen

import org.alephium.protocol.config.{DiscoveryConfig, GroupConfig}
import org.alephium.protocol.model.{CliqueId, ModelGenerators}
import org.alephium.util.AVector

trait DiscoveryMessageGenerators extends ModelGenerators {
  import DiscoveryMessage._

  lazy val findNodeGen: Gen[FindNode] = for {
    target <- cliqueIdGen
  } yield FindNode(target)

  def pingGen(implicit config: GroupConfig): Gen[Ping] =
    cliqueInfoGen.map(Ping.apply)

  def pongGen(implicit config: GroupConfig): Gen[Pong] =
    cliqueInfoGen.map(Pong.apply)

  def neighborsGen(implicit config: GroupConfig): Gen[Neighbors] =
    for {
      infos <- Gen.listOf(cliqueInfoGen)
    } yield Neighbors(AVector.from(infos))

  def messageGen(implicit config: DiscoveryConfig): Gen[DiscoveryMessage] =
    for {
      cliqueId <- Gen.const(()).map(_ => CliqueId.generate)
      payload  <- Gen.oneOf[Payload](findNodeGen, pingGen, pongGen, neighborsGen)
    } yield DiscoveryMessage.from(cliqueId, payload)
}
