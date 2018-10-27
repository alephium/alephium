package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.util.AVector

trait BlockHashPool {
  def numHashes: Int

  def maxWeight: Int

  def maxHeight: Int

  def contains(hash: Keccak256): Boolean

  def getWeight(hash: Keccak256): Int

  def getHeight(hash: Keccak256): Int

  def isTip(hash: Keccak256): Boolean

  // The return includes locator
  def getHashesAfter(locator: Keccak256): AVector[Keccak256]

  def getBlockHashSlice(hash: Keccak256): AVector[Keccak256]

  def getBestTip: Keccak256

  def getAllTips: AVector[Keccak256]

  def getAllBlockHashes: Iterable[Keccak256]

  def show(hash: Keccak256): String = {
    val shortHash = hash.shortHex
    val weight    = getWeight(hash)
    val hashNum   = numHashes - 1 // exclude genesis block
    val height    = getHeight(hash)
    s"Hash: $shortHash; Weight: $weight; Height: $height/$hashNum"
  }
}
