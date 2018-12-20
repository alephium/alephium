package org.alephium.flow.network

import java.net.{InetAddress, InetSocketAddress}

import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}

import scala.concurrent.duration.FiniteDuration
import org.alephium.protocol.model.PeerInfo
import org.alephium.protocol.config.{DiscoveryConfig => DC}

case class DiscoveryConfig(
    publicAddress: InetAddress,
    udpPort: Int,
    groups: Int,
    discoveryPrivateKey: ED25519PrivateKey,
    discoveryPublicKey: ED25519PublicKey,
    /* Maximum number of peers to track. */
    peersPerGroup: Int,
    /* Maximum number of peers used for probing during a scan. */
    scanMax: Int,
    /* Wait time between two scan. */
    scanFrequency: FiniteDuration,
    /* Maximum number of peers returned from a query (`k` in original kademlia paper). */
    neighborsPerGroup: Int
) extends DC {

  /** Duration we wait before considering a peer dead. **/
  def peersTimeout: FiniteDuration = scanFrequency * 4

  def udpAddress: InetSocketAddress = nodeInfo.socketAddress

  val nodeInfo: PeerInfo = PeerInfo(peerId, new InetSocketAddress(publicAddress, udpPort))
}
