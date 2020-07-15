package org.alephium.flow.core

import org.alephium.flow.model.BlockState
import org.alephium.io.IOResult
import org.alephium.protocol.ALF.Hash
import org.alephium.util.AVector

trait BlockHashPool {
  def numHashes: Int

  def contains(hash: Hash): IOResult[Boolean]

  def containsUnsafe(hash: Hash): Boolean

  def getState(hash: Hash): IOResult[BlockState]

  def getStateUnsafe(hash: Hash): BlockState

  def getWeight(hash: Hash): IOResult[BigInt]

  def getWeightUnsafe(hash: Hash): BigInt

  def getChainWeight(hash: Hash): IOResult[BigInt]

  def getChainWeightUnsafe(hash: Hash): BigInt

  def getHeight(hash: Hash): IOResult[Int]

  def getHeightUnsafe(hash: Hash): Int

  def isTip(hash: Hash): Boolean

  // The return excludes locator
  def getHashesAfter(locator: Hash): IOResult[AVector[Hash]]

  def getPredecessor(hash: Hash, height: Int): IOResult[Hash]

  def getBlockHashSlice(hash: Hash): IOResult[AVector[Hash]]

  // Hashes ordered by height
  def chainBack(hash: Hash, heightUntil: Int): IOResult[AVector[Hash]]

  def getBestTipUnsafe: Hash

  def getAllTips: AVector[Hash]

  def show(hash: Hash): String = {
    val shortHash = hash.shortHex
    val weight    = getWeight(hash).getOrElse(BigInt(-1))
    val hashNum   = numHashes - 1 // exclude genesis block
    val height    = getHeight(hash).getOrElse(-1)
    s"hash: $shortHash; weight: $weight; height: $height/$hashNum"
  }
}
