package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.protocol.model.BlockHeader
import org.alephium.util.AVector

trait BlockHeaderPool extends BlockHashPool {

  def contains(bh: BlockHeader): Boolean = contains(bh.hash)

  // Assuming the entity is in the pool
  def getBlockHeader(hash: Keccak256): IOResult[BlockHeader]
  def getBlockHeaderUnsafe(hash: Keccak256): BlockHeader

  def add(header: BlockHeader, weight: Int): IOResult[Unit]

  def add(header: BlockHeader, parentHash: Keccak256, weight: Int): IOResult[Unit]

  // scalastyle:off return
  def getHeaders(locators: AVector[Keccak256]): IOResult[AVector[BlockHeader]] = {
    var blocks = AVector.empty[BlockHeader]
    locators.foreach { hash =>
      if (contains(hash)) {
        getBlockHeader(hash) match {
          case Left(error)  => return Left(error)
          case Right(block) => blocks = blocks :+ block
        }
      }
    }
    Right(blocks)
  }
  // scalastyle:on return

  def getHeight(bh: BlockHeader): Int = getHeight(bh.hash)

  def getWeight(bh: BlockHeader): Int = getWeight(bh.hash)

  def isTip(bh: BlockHeader): Boolean = isTip(bh.hash)
}

sealed trait AddBlockHeaderResult

object AddBlockHeaderResult {
  sealed trait OK     extends AddBlockHeaderResult
  case object Success extends OK
  case object AlreadyExisted extends OK {
    override def toString: String = "BlockHeader already exist"
  }

  sealed trait Incomplete extends AddBlockHeaderResult
  case class MissingDeps(deps: AVector[Keccak256]) extends Incomplete {
    override def toString: String = s"Missing #$deps.length deps"
  }

  trait Error                                 extends AddBlockHeaderResult
  sealed trait VerificationError              extends Error
  case class IOErrorForHeader(error: IOError) extends Error
  case class Other(message: String) extends Error {
    override def toString: String = s"Failed in adding blockheader: $message"
  }

  case object InvalidGroup extends VerificationError {
    override def toString: String = "Block index is related to node's group"
  }
  case object InvalidDifficulty extends VerificationError {
    override def toString: String = "Difficulty is invalid"
  }
}
