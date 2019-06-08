package org.alephium.protocol.message

import org.alephium.protocol.config.{DiscoveryConfig, GroupConfig}
import org.scalacheck.Gen
import org.alephium.protocol.model.ModelGen
import org.alephium.util.AVector

object DiscoveryMessageGen {
  import DiscoveryMessage._

  val findNode: Gen[FindNode] = for {
    target <- ModelGen.cliqueId
  } yield FindNode(target)

  def ping(implicit config: GroupConfig): Gen[Ping] =
    ModelGen.cliqueInfo.map(Ping.apply)

  def pong(implicit config: GroupConfig): Gen[Pong] =
    ModelGen.cliqueInfo.map(Pong.apply)

  def neighbors(implicit config: GroupConfig): Gen[Neighbors] =
    for {
      infos <- Gen.listOf(ModelGen.cliqueInfo)
    } yield Neighbors(AVector.from(infos))

  def message(implicit config: DiscoveryConfig): Gen[DiscoveryMessage] =
    for {
      payload <- Gen.oneOf[Payload](findNode, ping, pong, neighbors)
    } yield DiscoveryMessage.from(payload)
}
