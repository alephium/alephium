package org.alephium.flow.storage

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.io.IOResult
import org.alephium.flow.trie.MerklePatriciaTrie
import org.alephium.protocol.model.{Block, ModelGen}
import org.alephium.util.AVector
import org.scalatest.EitherValues._

class BlockChainWithStateSpec extends AlephiumFlowSpec {
  trait Fixture {
    val genesis  = Block.genesis(AVector.empty, config.maxMiningTarget, 0)
    val blockGen = ModelGen.blockGenWith(AVector.fill(config.depsNum)(genesis.hash))
    val chainGen = ModelGen.chainGen(4, genesis)

    def myUpdateState(trie: MerklePatriciaTrie, block: Block): IOResult[MerklePatriciaTrie] = {
      import BlockFlowState._
      val cache = convertBlock(block, block.chainIndex.from)
      cache match {
        case InBlockCache(outputs) =>
          updateStateForOutputs(trie, outputs)
        case OutBlockCache(_) =>
          Right(trie)
        case InOutBlockCache(outputs, _) =>
          updateStateForOutputs(trie, outputs)
      }
    }
  }

  it should "add block" in new Fixture {
    forAll(blockGen) { block =>
      val chain = BlockChainWithState.fromGenesisUnsafe(genesis, myUpdateState)
      chain.numHashes is 1
      val blocksSize1 = chain.numHashes
      val res         = chain.add(block, 0)
      res.isRight is true
      val blocksSize2 = chain.numHashes
      blocksSize1 + 1 is blocksSize2
    }
  }

  it should "add blocks correctly" in new Fixture {
    forAll(chainGen) { blocks =>
      val chain       = BlockChain.fromGenesisUnsafe(genesis)
      val blocksSize1 = chain.numHashes
      blocks.foreach(block => chain.add(block, 0))
      val blocksSize2 = chain.numHashes
      blocksSize1 + blocks.length is blocksSize2

      checkConfirmedBlocks(chain, blocks)
    }
  }

  def checkConfirmedBlocks(chain: BlockChain, newBlocks: AVector[Block]): Unit = {
    newBlocks.indices.foreach { index =>
      val height    = index + 1
      val headerOpt = chain.getConfirmedHeader(height).right.value
      if (height + config.blockConfirmNum <= newBlocks.length) {
        headerOpt.get is newBlocks(index).header
      } else {
        headerOpt.isEmpty
      }
    }
  }

}
