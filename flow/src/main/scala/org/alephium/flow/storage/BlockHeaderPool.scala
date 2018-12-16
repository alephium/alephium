package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.BlockHeader
import org.alephium.util.AVector

trait BlockHeaderPool extends BlockHashPool {

  def contains(bh: BlockHeader): Boolean = contains(bh.hash)

  // Assuming the entity is in the pool
  def getBlockHeader(hash: Keccak256): BlockHeader

  def add(block: BlockHeader, weight: Int): AddBlockHeaderResult

  def add(block: BlockHeader, parentHash: Keccak256, weight: Int): AddBlockHeaderResult

  def getHeight(bh: BlockHeader): Int = getHeight(bh.hash)

  def getWeight(bh: BlockHeader): Int = getWeight(bh.hash)

  def isTip(bh: BlockHeader): Boolean = isTip(bh.hash)

  def getBlockHeaderSlice(hash: Keccak256): AVector[BlockHeader] = {
    getBlockHashSlice(hash).map(getBlockHeader)
  }

  def getBlockHeaderSlice(bh: BlockHeader): AVector[BlockHeader] = getBlockHeaderSlice(bh.hash)

  def getBestBlockHeaderChain: AVector[BlockHeader] = getBlockHeaderSlice(getBestTip)
}

sealed trait AddBlockHeaderResult

object AddBlockHeaderResult {
  case object Success extends AddBlockHeaderResult

  trait Failure extends AddBlockHeaderResult
  case object AlreadyExisted extends Failure {
    override def toString: String = "BlockHeader already exist"
  }
  case class MissingDeps(deps: AVector[Keccak256]) extends Failure {
    override def toString: String = s"Missing #$deps.length deps"
  }
  case object InvalidIndex extends Failure {
    override def toString: String = "Block header index is invalid"
  }
  case class Other(message: String) extends Failure {
    override def toString: String = s"Failed in adding blockheader: $message"
  }
}
