package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.flow.constant.Consensus
import org.alephium.protocol.model.Block
import org.alephium.util.AVector

trait SingleChain extends BlockPool {

  def maxHeight: Int

  // Note: this function is mainly for testing right now
  def add(block: Block, weight: Int): AddBlockResult = {
    val deps = block.blockHeader.blockDeps
    add(block, deps.last, weight)
  }

  def add(block: Block, parent: Keccak256, weight: Int): AddBlockResult

  def getConfirmedBlock(height: Int): Option[Block]

  def isBefore(hash1: Keccak256, hash2: Keccak256): Boolean

  def getHashTarget(hash: Keccak256): BigInt = {
    val block     = getBlock(hash)
    val height    = getHeight(hash)
    val refHeight = height - Consensus.retargetInterval
    getConfirmedBlock(refHeight) match {
      case Some(refBlock) =>
        val timeSpan = block.blockHeader.timestamp - refBlock.blockHeader.timestamp
        val retarget = block.blockHeader.target * Consensus.retargetInterval * Consensus.blockTargetTime.toMillis / timeSpan
        retarget
      case None => Consensus.maxMiningTarget
    }
  }

  def show(block: Block): String = {
    val shortHash = block.shortHash
    val weight    = getWeight(block)
    val blockNum  = numBlocks - 1 // exclude genesis block
    val height    = getHeight(block)
    s"Hash: $shortHash; Weight: $weight; Height: $height/$blockNum"
  }
}

sealed trait AddBlockResult

object AddBlockResult {
  case object Success extends AddBlockResult

  trait Failure extends AddBlockResult
  case object AlreadyExisted extends Failure {
    override def toString: String = "Block already exist"
  }
  case class MissingDeps(deps: AVector[Keccak256]) extends Failure {
    override def toString: String = s"Missing #${deps.length - 1} deps"
  }
}
