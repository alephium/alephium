package org.alephium.protocol.model

import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.util.TimeStamp

trait FlowData {
  def timestamp: TimeStamp

  def target: Target

  def hash: Hash

  def chainIndex(implicit config: GroupConfig): ChainIndex

  def isGenesis: Boolean

  def parentHash(implicit config: GroupConfig): Hash

  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): Hash

  def shortHex: String
}
