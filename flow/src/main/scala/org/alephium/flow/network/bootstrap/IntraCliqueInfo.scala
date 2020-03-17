package org.alephium.flow.network.bootstrap

import java.net.InetSocketAddress

import org.alephium.protocol.SafeSerdeImpl
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{CliqueId, CliqueInfo}
import org.alephium.serde._
import org.alephium.util.AVector

sealed abstract case class IntraCliqueInfo(
    id: CliqueId,
    peers: AVector[PeerInfo],
    groupNumPerBroker: Int
) {
  def cliqueInfo: CliqueInfo = {
    val addresses = peers.map(info => new InetSocketAddress(info.address, info.tcpPort))
    CliqueInfo.unsafe(id, addresses, groupNumPerBroker)
  }
}

object IntraCliqueInfo extends SafeSerdeImpl[IntraCliqueInfo, GroupConfig] {
  def unsafe(id: CliqueId, peers: AVector[PeerInfo], groupNumPerBroker: Int): IntraCliqueInfo = {
    new IntraCliqueInfo(id, peers, groupNumPerBroker) {}
  }

  private implicit val peerSerde  = PeerInfo._serde
  private implicit val peersSerde = avectorSerde[PeerInfo]
  override val _serde: Serde[IntraCliqueInfo] =
    Serde.forProduct3(unsafe, t => (t.id, t.peers, t.groupNumPerBroker))

  override def validate(info: IntraCliqueInfo)(
      implicit config: GroupConfig): Either[String, Unit] = {
    for {
      _ <- checkGroups(info)
      _ <- checkPeers(info)
    } yield ()
  }

  private def checkGroups(info: IntraCliqueInfo)(
      implicit config: GroupConfig): Either[String, Unit] = {
    if (info.peers.length * info.groupNumPerBroker != config.groups)
      Left(s"invalid groups: $info")
    else Right(())
  }

  private def checkPeers(info: IntraCliqueInfo)(
      implicit config: GroupConfig): Either[String, Unit] = {
    info.peers.foreachWithIndexE { (peer, index) =>
      if (peer.id != index) Left(s"invalid index: $peer")
      else PeerInfo.validate(peer)
    }
  }
}
