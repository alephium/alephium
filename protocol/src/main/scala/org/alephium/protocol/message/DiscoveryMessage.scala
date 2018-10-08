package org.alephium.protocol.message

import akka.util.ByteString

import org.alephium.serde.{Deserializer, RandomBytes, Serde, Serializer}
import org.alephium.protocol.model.{peerIdLength, PeerAddress, PeerId}

/**
  *  Discovery RPC Protocol (Kademila based)
  *  https://pdos.csail.mit.edu/~petar/papers/maymounkov-kademlia-lncs.pdf
  *
  *  FindNode  -> Neighbors
  *  Ping      -> Pong
 **/
sealed trait DiscoveryMessage {
  def callId: DiscoveryMessage.CallId
}

object DiscoveryMessage {
  val version: Byte = 0

  /** The 160bits (20bytes) identifier of a Remote Procedure Call **/
  class CallId private[CallId] (val bytes: ByteString) extends RandomBytes
  object CallId extends RandomBytes.Companion[CallId](new CallId(_), _.bytes) {
    override def length: Int = peerIdLength
  }

  // Commands
  case class FindNode(callId: CallId, target: PeerId) extends DiscoveryMessage
  object FindNode extends Code {
    val serde: Serde[FindNode] =
      Serde.forProduct2(FindNode.apply, p => (p.callId, p.target))
  }

  /** Ping using the `PeerId` of the sender **/
  case class Ping(callId: CallId, source: PeerId) extends DiscoveryMessage
  object Ping extends Code {
    val serde: Serde[Ping] =
      Serde.forProduct2(Ping.apply, p => (p.callId, p.source))
  }

  // Events
  case class Neighbors(callId: CallId, peers: Seq[PeerAddress]) extends DiscoveryMessage
  object Neighbors extends Code {
    val serde: Serde[Neighbors] =
      Serde.forProduct2(Neighbors.apply, p => (p.callId, p.peers))
  }

  /** Pong using the `sourceId` of the sender of the `Ping` and the `targetId` of the sender of `Pong` **/
  case class Pong(callId: CallId, sourceId: PeerId, targetId: PeerId) extends DiscoveryMessage
  object Pong extends Code {
    val serde: Serde[Pong] =
      Serde.forProduct3(Pong.apply, p => (p.callId, p.sourceId, p.targetId))
  }

  sealed trait Code
  object Code {
    val values: Seq[Code] = Seq(Ping, Pong, FindNode, Neighbors)

    val toByte: Map[Code, Byte]   = values.zipWithIndex.toMap.mapValues(_.toByte)
    val fromByte: Map[Byte, Code] = toByte.map(_.swap)
  }

  val deserializerCode: Deserializer[Code] =
    Serde[Byte].validateGet(Code.fromByte.get, c => s"Invalid message code '$c'")

  val deserializerVersion: Deserializer[Byte] =
    Serde[Byte]
      .validate(_ == version, v => s"Incompatible protocol version $v (expecting $version)")

  implicit val deserializer: Deserializer[DiscoveryMessage] = (input0: ByteString) =>
    for {
      (_, input1) <- deserializerVersion._deserialize(input0)
      (c, input2) <- deserializerCode._deserialize(input1)
      serde = c match {
        case Ping      => Ping.serde
        case Pong      => Pong.serde
        case FindNode  => FindNode.serde
        case Neighbors => Neighbors.serde
      }
      res <- serde._deserialize(input2)
    } yield res

  implicit val serializer: Serializer[DiscoveryMessage] = { message =>
    val (code, data) = message match {
      case x: Ping      => (Ping, Ping.serde.serialize(x))
      case x: Pong      => (Pong, Pong.serde.serialize(x))
      case x: FindNode  => (FindNode, FindNode.serde.serialize(x))
      case x: Neighbors => (Neighbors, Neighbors.serde.serialize(x))
    }

    Serde[Byte].serialize(version) ++ Serde[Byte].serialize(Code.toByte(code)) ++ data
  }

}
