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
    cid    <- callId
    source <- ModelGen.peerId
  } yield Ping(cid, source)

  val pong: Gen[Pong] = for {
    cid    <- callId
    source <- ModelGen.peerId
    target <- ModelGen.peerId
  } yield Pong(cid, source, target)

  val neighbors: Gen[Neighbors] = for {
    cid    <- callId
    source <- Gen.listOf(ModelGen.peerAddress)
  } yield Neighbors(cid, AVector.from(source))

  val message: Gen[DiscoveryMessage] = Gen.oneOf(findNode, ping, pong, neighbors)
}
