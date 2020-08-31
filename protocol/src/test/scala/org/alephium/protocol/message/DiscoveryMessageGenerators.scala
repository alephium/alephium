package org.alephium.protocol.message

import org.scalacheck.Gen

import org.alephium.protocol.Generators
import org.alephium.protocol.config.{DiscoveryConfig, GroupConfig}
import org.alephium.protocol.model.CliqueId
import org.alephium.util.AVector

trait DiscoveryMessageGenerators extends Generators {
  import DiscoveryMessage._

  lazy val findNodeGen: Gen[FindNode] = for {
    target <- cliqueIdGen
  } yield FindNode(target)

  def pingGen(implicit config: GroupConfig): Gen[Ping] =
    interCliqueInfoGen.map(info => Ping.apply(Some(info)))

  def pongGen(implicit config: GroupConfig): Gen[Pong] =
    interCliqueInfoGen.map(Pong.apply)

  def neighborsGen(implicit config: GroupConfig): Gen[Neighbors] =
    for {
      infos <- Gen.listOf(interCliqueInfoGen)
    } yield Neighbors(AVector.from(infos))

  def messageGen(implicit discoveryConfig: DiscoveryConfig,
                 groupConfig: GroupConfig): Gen[DiscoveryMessage] =
    for {
      cliqueId <- Gen.const(()).map(_ => CliqueId.generate)
      payload  <- Gen.oneOf[Payload](findNodeGen, pingGen, pongGen, neighborsGen)
    } yield DiscoveryMessage.from(cliqueId, payload)
}
