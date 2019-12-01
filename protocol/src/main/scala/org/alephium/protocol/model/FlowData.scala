package org.alephium.protocol.model

import org.alephium.crypto.Keccak256
import org.alephium.protocol.config.GroupConfig
import org.alephium.util.TimeStamp

trait FlowData {
  def timestamp: TimeStamp

  def target: BigInt

  def hash: Keccak256

  def chainIndex(implicit config: GroupConfig): ChainIndex

  def isGenesis: Boolean

  def parentHash(implicit config: GroupConfig): Keccak256

  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): Keccak256

  def shortHex: String
}
