package org.alephium.flow.network

import scala.concurrent.duration.FiniteDuration

import org.alephium.protocol.model.{GroupIndex, PeerId}

case class DiscoveryConfig(
    udpPort: Int,
    groups: Int,
    peerId: PeerId,
    group: GroupIndex,
    /* Maximum number of peers to track. */
    peersPerGroup: Int,
    /* Maximum number of peers used for probing during a scan. */
    scanMax: Int,
    /* Wait time between two scan. */
    scanFrequency: FiniteDuration,
    /* Maximum number of peers returned from a query (`k` in original kademlia paper). */
    neighborsPerGroup: Int
) {

  /** Duration we wait before considering a peer dead. **/
  def peersTimeout: FiniteDuration = scanFrequency * 4
}
