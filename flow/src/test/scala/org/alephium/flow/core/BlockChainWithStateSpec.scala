package org.alephium.flow.core

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.io.{IOResult, Storages}
import org.alephium.flow.io.RocksDBSource.Settings
import org.alephium.flow.trie.WorldState
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model.{Block, ChainIndex, ModelGen}
import org.alephium.util.AVector

class BlockChainWithStateSpec extends AlephiumFlowSpec {
  trait Fixture {
    val genesis  = Block.genesis(AVector.empty, config.maxMiningTarget, 0)
    val blockGen = ModelGen.blockGenWith(AVector.fill(config.depsNum)(genesis.hash))
    val chainGen = ModelGen.chainGen(4, genesis)
    val heightDB = storages.nodeStateStorage.heightIndexStorage(ChainIndex.unsafe(0, 0))
    val stateDB  = storages.nodeStateStorage.chainStateStorage(ChainIndex.unsafe(0, 0))

    def myUpdateState(worldState: WorldState, block: Block): IOResult[WorldState] = {
      import BlockFlowState._
      val cache = convertBlock(block, block.chainIndex.from)
      cache match {
        case InBlockCache(outputs) =>
          updateStateForOutputs(worldState, outputs)
        case OutBlockCache(_, _) =>
          Right(worldState)
        case InOutBlockCache(outputs, _) =>
          updateStateForOutputs(worldState, outputs)
      }
    }

    def buildGenesis(): BlockChainWithState = {
      val dbFolder     = "db-" + Hash.random.toHexString
      val blocksFolder = "blocks-" + Hash.random.toHexString
      val storages     = Storages.createUnsafe(rootPath, dbFolder, blocksFolder, Settings.syncWrite)
      BlockChainWithState.createUnsafe(
        ChainIndex.unsafe(0, 0),
        genesis,
        storages,
        myUpdateState,
        BlockChainWithState.initializeGenesis(genesis, storages.emptyWorldState)(_))
    }
  }

  it should "add block" in new Fixture {
    forAll(blockGen) { block =>
      val chain = buildGenesis()
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
      val chain       = buildGenesis()
      val blocksSize1 = chain.numHashes
      blocks.foreach(block => chain.add(block, 0))
      val blocksSize2 = chain.numHashes
      blocksSize1 + blocks.length is blocksSize2
    }
  }
}
