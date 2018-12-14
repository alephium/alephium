package org.alephium.flow.model

import org.alephium.crypto.Keccak256
import org.alephium.flow.constant.{Consensus, Network}
import org.alephium.protocol.model.Block

case class ChainIndex(from: Int, to: Int) {
  def accept(hash: Keccak256): Boolean = {
    val target     = from * Network.groups + to
    val miningHash = Block.toMiningHash(hash)
    val actual     = ChainIndex.hash2Index(miningHash)
    actual == target && {
      val current = BigInt(1, miningHash.bytes.toArray)
      current <= Consensus.maxMiningTarget
    }
  }

  def toOneDim: Int = from * Network.groups + to
}

object ChainIndex {
  def fromHash(hash: Keccak256): ChainIndex = {
    val miningHash = Block.toMiningHash(hash)
    val target     = hash2Index(miningHash)
    val from       = target / Network.groups
    val to         = target % Network.groups
    ChainIndex(from, to)
  }

  private[ChainIndex] def hash2Index(hash: Keccak256): Int = {
    val BigIndex = (hash.second2LastByte & 0xFF) << 8 | (hash.lastByte & 0xFF)
    BigIndex % Network.chainNum
  }
}
