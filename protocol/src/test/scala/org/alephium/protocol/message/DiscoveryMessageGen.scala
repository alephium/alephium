package org.alephium.protocol.message

import org.alephium.protocol.config.DiscoveryConfig
import org.scalacheck.Gen
import org.alephium.protocol.model.{GroupIndex, ModelGen}
import org.alephium.util.AVector

import scala.collection.JavaConverters._

object DiscoveryMessageGen {
  import DiscoveryMessage._

  val findNode: Gen[FindNode] = for {
    target <- ModelGen.peerId
  } yield FindNode(target)

  val ping: Gen[Ping] =
    for {
      source <- ModelGen.socketAddress
    } yield Ping(source)

  val pong: Gen[Pong] = Gen.map(_ => Pong())

  def neighbors(implicit config: DiscoveryConfig): Gen[Neighbors] =
    for {
      source <- Gen.sequence((0 until config.groups).map(i =>
        Gen.listOf(ModelGen.peerInfo(GroupIndex(i)(config))(config))))
    } yield Neighbors(AVector.from(source.asScala.map(AVector.from)))

  def message(implicit config: DiscoveryConfig): Gen[DiscoveryMessage] =
    for {
      payload <- Gen.oneOf[Payload](findNode, ping, pong, neighbors)
    } yield DiscoveryMessage.from(payload)
}
