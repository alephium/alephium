package org.alephium.flow.core

import org.alephium.protocol.ALF.Hash
import org.alephium.util.AVector

trait BlockHashPool {
  def numHashes: Int

  def maxWeight: Int

  def maxHeight: Int

  def contains(hash: Hash): Boolean

  def getWeight(hash: Hash): Int

  def getHeight(hash: Hash): Int

  def isTip(hash: Hash): Boolean

  // The return includes locator
  def getHashesAfter(locator: Hash): AVector[Hash]

  def getPredecessor(hash: Hash, height: Int): Hash

  def getBlockHashSlice(hash: Hash): AVector[Hash]

  def getBestTip: Hash

  def getAllTips: AVector[Hash]

  def getAllBlockHashes: Iterator[Hash]

  def show(hash: Hash): String = {
    val shortHash = hash.shortHex
    val weight    = getWeight(hash)
    val hashNum   = numHashes - 1 // exclude genesis block
    val height    = getHeight(hash)
    s"Hash: $shortHash; Weight: $weight; Height: $height/$hashNum"
  }
}
