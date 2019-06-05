package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._
import org.alephium.util.AVector

// All the groups [0, ..., G-1] are divided into G/gFactor continuous groups
// Assume the peers are ordered according to the groups they correspond to
class CliqueInfo(val id: CliqueId,
                 val peers: AVector[InetSocketAddress],
                 val groupNumPerBroker: Int) {
  def brokerNum: Int = peers.length

  // TODO: add a field for master broker
  def masterAddress: InetSocketAddress = peers.head
}

object CliqueInfo {
  // TODO: make this safe by validation
  implicit val serde: Serde[CliqueInfo] =
    Serde.forProduct3(new CliqueInfo(_, _, _), ci => (ci.id, ci.peers, ci.groupNumPerBroker))

  def apply(id: CliqueId, peers: AVector[InetSocketAddress], groupNumPerBroker: Int)(
      implicit config: GroupConfig): CliqueInfo = {
    assert(config.groups == peers.length * groupNumPerBroker)
    new CliqueInfo(id, peers, groupNumPerBroker)
  }
}
