package org.alephium.flow.model

import org.alephium.crypto.Keccak256
import org.alephium.flow.constant.Network
import org.alephium.protocol.model.Block

case class ChainIndex(from: Int, to: Int) {
  def accept(hash: Keccak256): Boolean = {
    val target     = from * Network.groups + to
    val miningHash = Block.toMiningHash(hash)
    val actual     = ChainIndex.hash2Index(miningHash)
    actual == target
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
    val bytes    = hash.bytes
    val BigIndex = bytes(1).toInt * 256 + bytes(0).toInt
    Math.floorMod(BigIndex, Network.chainNum)
  }
}
