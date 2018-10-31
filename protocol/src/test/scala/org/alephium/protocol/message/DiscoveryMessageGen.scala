package org.alephium.protocol.message

import org.alephium.protocol.config.GroupConfig
import org.scalacheck.Gen
import org.alephium.protocol.model.{GroupIndex, ModelGen}
import org.alephium.util.AVector

import scala.collection.JavaConverters._

object DiscoveryMessageGen {
  import DiscoveryMessage._

  val callId: Gen[CallId] = Gen.resultOf[Unit, CallId](_ => CallId.generate)

  val findNode: Gen[FindNode] = for {
    cid    <- callId
    source <- ModelGen.peerId
  } yield FindNode(cid, source)

  val ping: Gen[Ping] =
    for {
      cid    <- callId
      source <- ModelGen.peerId
    } yield Ping(cid, source)

  val pong: Gen[Pong] =
    for {
      cid <- callId
    } yield Pong(cid)

  def neighbors: Gen[Neighbors] =
    for {
      _callId <- callId
      _groups <- Gen.choose(2, 8)
      config = new GroupConfig { override def groups: Int = _groups }
      source <- Gen.sequence(
        (0 until _groups).map(i => Gen.listOf(ModelGen.peerAddress(GroupIndex(i)(config))(config))))
    } yield Neighbors(_callId, AVector.from(source.asScala.map(AVector.from)))

  val message: Gen[DiscoveryMessage] =
    Gen.oneOf(findNode, ping, pong, neighbors)
}
