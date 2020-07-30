package org.alephium.protocol.model

import org.alephium.protocol.{ALF, Hash, HashSerde}
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
final case class BlockHeader(
    blockDeps: AVector[Hash],
    txsHash: Hash,
    timestamp: TimeStamp,
    target: BigInt,
    nonce: BigInt
) extends HashSerde[BlockHeader]
    with FlowData {
  override lazy val hash: Hash = _getHash

  def chainIndex(implicit config: GroupConfig): ChainIndex = {
    ChainIndex.from(hash)
  }

  def isGenesis: Boolean = timestamp == TimeStamp.zero

  def parentHash(implicit config: GroupConfig): Hash = {
    uncleHash(chainIndex.to)
  }

  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): Hash = {
    assert(!isGenesis)
    blockDeps.takeRight(config.groups)(toIndex.value)
  }

  def inDeps(implicit config: GroupConfig): AVector[Hash] = {
    assert(!isGenesis)
    blockDeps.dropRight(config.groups)
  }

  def outDeps(implicit config: GroupConfig): AVector[Hash] = {
    assert(!isGenesis)
    blockDeps.takeRight(config.groups)
  }

  def outTips(implicit config: GroupConfig): AVector[Hash] = {
    assert(!isGenesis)
    blockDeps.takeRight(config.groups).replace(chainIndex.to.value, hash)
  }
}

object BlockHeader {
  implicit val serde: Serde[BlockHeader] =
    Serde.forProduct5(apply, bh => (bh.blockDeps, bh.txsHash, bh.timestamp, bh.target, bh.nonce))

  def genesis(txsHash: Hash, target: BigInt, nonce: BigInt): BlockHeader = {
    BlockHeader(AVector.empty, txsHash, ALF.GenesisTimestamp, target, nonce)
  }
}
