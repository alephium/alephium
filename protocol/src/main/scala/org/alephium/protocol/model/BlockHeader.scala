package org.alephium.protocol.model

import org.alephium.crypto._
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._
import org.alephium.util.{AVector, TimeStamp}

/** The header of a block with index.from == _group_
  *
  * @param blockDeps If the block is genesis, then it's empty.
  *                  Otherwise, 2 * G - 1 deps in total:
  *                  the first G - 1 hashes are from groups different from _group_
  *                  the rest G hashes are from all the chain related to _group_
  */
case class BlockHeader(
    blockDeps: AVector[Keccak256],
    txsHash: Keccak256,
    timestamp: TimeStamp,
    target: BigInt,
    nonce: BigInt
) extends Keccak256Hash[BlockHeader] {

  override lazy val hash: Keccak256 = _getHash

  def chainIndex(implicit config: GroupConfig): ChainIndex = {
    ChainIndex.from(hash)
  }

  def isGenesis: Boolean = blockDeps.isEmpty

  def parentHash(implicit config: GroupConfig): Keccak256 = {
    uncleHash(chainIndex.to)
  }

  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): Keccak256 = {
    assert(!isGenesis)
    blockDeps.takeRight(config.groups)(toIndex.value)
  }

  def inDeps(implicit config: GroupConfig): AVector[Keccak256] = {
    assert(!isGenesis)
    blockDeps.dropRight(config.groups)
  }

  def outDeps(implicit config: GroupConfig): AVector[Keccak256] = {
    assert(!isGenesis)
    blockDeps.takeRight(config.groups)
  }

  def validateIndex(target: ChainIndex)(implicit config: GroupConfig): Boolean = {
    val actual = chainIndex
    target.from == actual.from && target.to == actual.to
  }
}

object BlockHeader {
  private implicit val serdeTS: Serde[TimeStamp] =
    longSerde
      .validate(_ >= 0, n => s"Expect positive timestamp, got $n")
      .xmap(TimeStamp.fromMillis, _.millis)

  implicit val serde: Serde[BlockHeader] =
    Serde.forProduct5(apply, bh => (bh.blockDeps, bh.txsHash, bh.timestamp, bh.target, bh.nonce))
}
