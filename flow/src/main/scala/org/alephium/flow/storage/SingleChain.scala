package org.alephium.flow.storage
import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.Block

trait SingleChain extends BlockPool {

  def maxHeight: Int

  // Note: this function is mainly for testing right now
  def add(block: Block, weight: Int): AddBlockResult = {
    val deps = block.blockHeader.blockDeps
    add(block, deps.last, weight)
  }

  def add(block: Block, parent: Keccak256, weight: Int): AddBlockResult

  def isBefore(hash1: Keccak256, hash2: Keccak256): Boolean
}

sealed trait AddBlockResult

object AddBlockResult {
  case object Success extends AddBlockResult

  trait Failure extends AddBlockResult
  case object AlreadyExisted extends Failure {
    override def toString: String = "Block already exist"
  }
  case class MissingDeps(deps: Seq[Keccak256]) extends Failure {
    override def toString: String = s"Missing #${deps.size - 1} deps"
  }
}
