package org.alephium.protocol.message

import org.scalacheck.Gen
import org.alephium.protocol.model.ModelGen
import org.alephium.util.AVector

object DiscoveryMessageGen {
  import DiscoveryMessage._

  val callId: Gen[CallId] = Gen.resultOf[Unit, CallId](_ => CallId.generate)

  val findNode: Gen[FindNode] = for {
    cid    <- callId
    source <- ModelGen.peerId
  } yield FindNode(cid, source)

  val ping: Gen[Ping] = for {
    cid         <- callId
    source      <- ModelGen.peerId
    sourceGroup <- ModelGen.groupGen_(1024)
  } yield Ping(cid, source, sourceGroup)

  val pong: Gen[Pong] = for {
    cid         <- callId
    target      <- ModelGen.peerId
    targetGroup <- ModelGen.groupGen_(1024)
  } yield Pong(cid, target, targetGroup)

  val neighbors: Gen[Neighbors] = for {
    cid    <- callId
    source <- Gen.listOf(Gen.listOf(ModelGen.peerAddress(1024)))
  } yield Neighbors(cid, AVector.from(source.map(AVector.from)))

  val message: Gen[DiscoveryMessage] = Gen.oneOf(findNode, ping, pong, neighbors)
}
