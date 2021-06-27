// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.core

import scala.annotation.tailrec

import org.alephium.flow.core.BlockFlowState.{BlockCache, TxStatus}
import org.alephium.flow.core.FlowUtils._
import org.alephium.flow.core.UtxoUtils.Asset
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.{ALF, BlockHash, Hash, PublicKey}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, UnlockScript, WorldState}
import org.alephium.util.{AVector, TimeStamp, U256}

trait TxUtils { Self: FlowUtils =>
  def getPersistedUtxos(
      groupIndex: GroupIndex,
      bestDeps: BlockDeps,
      lockupScript: LockupScript,
      maxUtxosToRead: Int
  ): IOResult[AVector[AssetOutputInfo]] = {
    for {
      bestWorldState <- getPersistedWorldState(bestDeps, groupIndex)
      persistedUtxos <- bestWorldState
        .getAssetOutputs(
          lockupScript.assetHintBytes,
          maxUtxosToRead,
          (_, output) => output.lockupScript == lockupScript
        )
        .map(
          _.map(p => AssetOutputInfo(p._1, p._2, PersistedOutput))
        )
    } yield persistedUtxos
  }

  def mergeUtxos(
      persistedUtxos: AVector[AssetOutputInfo],
      usedInCache: AVector[AssetOutputRef],
      newInCache: AVector[AssetOutputInfo]
  ): AVector[AssetOutputInfo] = {
    persistedUtxos.filter(p => !usedInCache.contains(p.ref)) ++ newInCache
  }

  def getRelevantUtxos(
      groupIndex: GroupIndex,
      bestDeps: BlockDeps,
      lockupScript: LockupScript,
      maxUtxosToRead: Int
  ): IOResult[AVector[AssetOutputInfo]] = {
    for {
      persistedUtxos <- getPersistedUtxos(groupIndex, bestDeps, lockupScript, maxUtxosToRead)
      cachedResult   <- getUtxosInCache(groupIndex, bestDeps, lockupScript, persistedUtxos)
    } yield {
      val utxosInBlock = mergeUtxos(persistedUtxos, cachedResult._1, cachedResult._2)
      grandPool.getRelevantUtxos(groupIndex, lockupScript, utxosInBlock)
    }
  }

  def getPreOutputsIncludingPools(
      mainGroup: GroupIndex,
      inputs: AVector[TxOutputRef]
  ): IOResult[Option[AVector[TxOutput]]] = {
    getPreOutputs(mainGroup, inputs, getPreOutputIncludingPools)
  }

  def getPreOutputsInGroupView(
      mainGroup: GroupIndex,
      inputs: AVector[TxOutputRef]
  ): IOResult[Option[AVector[TxOutput]]] = {
    getPreOutputs(mainGroup, inputs, (_, b, c, d) => getPreOutputInGroupView(b, c, d))
  }

  def getPreOutputsInGroupView(
      mainGroup: GroupIndex,
      blockDeps: BlockDeps,
      worldState: WorldState.Cached,
      inputs: AVector[TxOutputRef]
  ): IOResult[Option[AVector[TxOutput]]] = {
    getPreOutputs(
      inputs,
      mainGroup,
      blockDeps,
      worldState,
      (_: GroupIndex, b: WorldState.Cached, c: AVector[BlockCache], d: TxOutputRef) =>
        getPreOutputInGroupView(b, c, d)
    )
  }

  private def getPreOutputs(
      mainGroup: GroupIndex,
      inputs: AVector[TxOutputRef],
      getPreOutput: (
          GroupIndex,
          WorldState.Persisted,
          AVector[BlockCache],
          TxOutputRef
      ) => IOResult[Option[TxOutput]]
  ): IOResult[Option[AVector[TxOutput]]] = {
    val bestDeps = getBestDeps(mainGroup)
    for {
      worldState <- getPersistedWorldState(bestDeps, mainGroup)
      result     <- getPreOutputs(inputs, mainGroup, bestDeps, worldState, getPreOutput)
    } yield result
  }

  private def getPreOutputs[WS <: WorldState[_]](
      inputs: AVector[TxOutputRef],
      mainGroup: GroupIndex,
      blockDeps: BlockDeps,
      worldState: WS,
      getPreOutput: (
          GroupIndex,
          WS,
          AVector[BlockCache],
          TxOutputRef
      ) => IOResult[Option[TxOutput]]
  ): IOResult[Option[AVector[TxOutput]]] = {
    for {
      blockCaches <- getBlocksForUpdates(mainGroup, blockDeps)
      result <- inputs.foldE(Option(AVector.empty[TxOutput])) {
        case (Some(outputs), input) =>
          getPreOutput(mainGroup, worldState, blockCaches, input).map(
            _.map(outputs :+ _)
          )
        case (None, _) => Right(None)
      }
    } yield result
  }

  def getPreOutputIncludingPools[WS <: WorldState[_]](
      mainGroup: GroupIndex,
      worldState: WS,
      blockCaches: AVector[BlockCache],
      outputRef: TxOutputRef
  ): IOResult[Option[TxOutput]] = {
    val mempool = getMemPool(mainGroup)
    if (mempool.isSpent(outputRef)) {
      Right(None)
    } else {
      mempool.getOutput(outputRef) match {
        case Some(output) => Right(Some(output))
        case None         => getPreOutputInGroupView(worldState, blockCaches, outputRef)
      }
    }
  }

  def getPreOutputInGroupView[WS <: WorldState[_]](
      worldState: WS,
      blockCaches: AVector[BlockCache],
      outputRef: TxOutputRef
  ): IOResult[Option[TxOutput]] = {
    if (TxUtils.isSpent(blockCaches, outputRef)) {
      Right(None)
    } else {
      worldState.getOutputOpt(outputRef).map {
        case Some(output) => Some(output)
        case None =>
          val index = blockCaches.indexWhere(_.relatedOutputs.contains(outputRef))
          if (index != -1) {
            Some(blockCaches(index).relatedOutputs(outputRef))
          } else {
            None
          }
      }
    }
  }

  // We call getUsableUtxosOnce multiple times until the resulted tx does not change
  // In this way, we can guarantee that no concurrent utxos operations are making trouble
  def getUsableUtxos(
      lockupScript: LockupScript
  ): IOResult[AVector[AssetOutputInfo]] = {
    @tailrec
    def iter(lastTryOpt: Option[AVector[AssetOutputInfo]]): IOResult[AVector[AssetOutputInfo]] = {
      getUsableUtxosOnce(lockupScript) match {
        case Right(utxos) =>
          lastTryOpt match {
            case Some(lastTry) if isSame(utxos, lastTry) => Right(utxos)
            case _                                       => iter(Some(utxos))
          }
        case Left(error) => Left(error)
      }
    }
    iter(None)
  }

  def getUsableUtxosOnce(
      lockupScript: LockupScript
  ): IOResult[AVector[AssetOutputInfo]] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))
    val bestDeps = getBestDeps(groupIndex)
    getUsableUtxosOnce(groupIndex, bestDeps, lockupScript)
  }

  def getUsableUtxosOnce(
      groupIndex: GroupIndex,
      bestDeps: BlockDeps,
      lockupScript: LockupScript
  ): IOResult[AVector[AssetOutputInfo]] = {
    val currentTs = TimeStamp.now()
    getRelevantUtxos(groupIndex, bestDeps, lockupScript, maxUtxosToReadForTransfer).map(
      _.filter(_.output.lockTime <= currentTs)
    )
  }

  // return the total balance, the locked balance, and the number of all utxos
  def getBalance(lockupScript: LockupScript): IOResult[(U256, U256, Int)] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))
    val bestDeps = getBestDeps(groupIndex)

    val currentTs = TimeStamp.now()
    getRelevantUtxos(groupIndex, bestDeps, lockupScript, Int.MaxValue).map { utxos =>
      val balance = utxos.fold(U256.Zero)(_ addUnsafe _.output.amount)
      val lockedBalance = utxos.fold(U256.Zero) { case (acc, utxo) =>
        if (utxo.output.lockTime > currentTs) acc addUnsafe utxo.output.amount else acc
      }
      (balance, lockedBalance, utxos.length)
    }
  }

  def transfer(
      fromPublicKey: PublicKey,
      outputInfos: AVector[(LockupScript, U256, Option[TimeStamp])],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): IOResult[Either[String, UnsignedTransaction]] = {
    val fromLockupScript = LockupScript.p2pkh(fromPublicKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromPublicKey)
    getUsableUtxos(fromLockupScript).map { utxos =>
      for {
        totalAmount <- outputInfos.foldE(U256.Zero) { case (acc, (_, amount, _)) =>
          acc.add(amount).toRight("Amount overflow")
        }
        _ <- checkOutputInfos(outputInfos)
        _ <- checkWithMinimalGas(gasOpt, minimalGas)
        selected <- UtxoUtils.select(
          utxos,
          totalAmount,
          gasOpt,
          gasPrice,
          defaultGasPerInput,
          defaultGasPerOutput,
          dustUtxoAmount,
          outputInfos.length + 1,
          minimalGas
        )
        _ <- checkWithMaxTxInputNum(selected.assets)
        unsignedTx <- UnsignedTransaction
          .transferAlf(
            selected.assets.map(asset => (asset.ref, asset.output)),
            fromLockupScript,
            fromUnlockScript,
            outputInfos,
            selected.gas,
            gasPrice
          )
      } yield unsignedTx
    }
  }

  def transfer(
      fromPublicKey: PublicKey,
      toLockupScript: LockupScript,
      lockTimeOpt: Option[TimeStamp],
      amount: U256,
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): IOResult[Either[String, UnsignedTransaction]] = {
    transfer(fromPublicKey, AVector((toLockupScript, amount, lockTimeOpt)), gasOpt, gasPrice)
  }

  def sweepAll(
      fromPublicKey: PublicKey,
      toLockupScript: LockupScript,
      lockTimeOpt: Option[TimeStamp],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): IOResult[Either[String, UnsignedTransaction]] = {
    val fromLockupScript = LockupScript.p2pkh(fromPublicKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromPublicKey)

    getUsableUtxos(fromLockupScript).map { utxos =>
      for {
        _   <- checkWithMinimalGas(gasOpt, minimalGas)
        gas <- Right(gasOpt.getOrElse(UtxoUtils.estimateGas(utxos.length, 1)))
        _   <- checkWithMaxTxInputNum(utxos)
        totalAmount <- utxos.foldE(U256.Zero)(
          _ add _.output.amount toRight "Input amount overflow"
        )
        amount <- totalAmount.sub(gasPrice * gas).toRight("Not enough balance for gas fee")
        unsignedTx <- UnsignedTransaction
          .transferAlf(
            utxos.map(asset => (asset.ref, asset.output)),
            fromLockupScript,
            fromUnlockScript,
            AVector((toLockupScript, amount, lockTimeOpt)),
            gas,
            gasPrice
          )
      } yield unsignedTx
    }
  }

  def getTxStatus(txId: Hash, chainIndex: ChainIndex): IOResult[Option[TxStatus]] =
    IOUtils.tryExecute {
      assume(brokerConfig.contains(chainIndex.from))
      val chain = getBlockChain(chainIndex)
      chain.getTxStatusUnsafe(txId).flatMap { chainStatus =>
        val confirmations = chainStatus.confirmations
        if (chainIndex.isIntraGroup) {
          Some(TxStatus(chainStatus.index, confirmations, confirmations, confirmations))
        } else {
          val confirmHash = chainStatus.index.hash
          val fromGroupConfirmations =
            getFromGroupConfirmationsUnsafe(confirmHash, chainIndex)
          val toGroupConfirmations =
            getToGroupConfirmationsUnsafe(confirmHash, chainIndex)
          Some(
            TxStatus(chainStatus.index, confirmations, fromGroupConfirmations, toGroupConfirmations)
          )
        }
      }
    }

  def getFromGroupConfirmationsUnsafe(hash: BlockHash, chainIndex: ChainIndex): Int = {
    assume(ChainIndex.from(hash) == chainIndex)
    val targetChain   = getHeaderChain(chainIndex)
    val header        = targetChain.getBlockHeaderUnsafe(hash)
    val fromChain     = getHeaderChain(chainIndex.from, chainIndex.from)
    val fromTip       = getOutTip(header, chainIndex.from)
    val fromTipHeight = fromChain.getHeightUnsafe(fromTip)

    @tailrec
    def iter(height: Int): Option[Int] = {
      val hashes = fromChain.getHashesUnsafe(height)
      if (hashes.isEmpty) {
        None
      } else {
        val header   = fromChain.getBlockHeaderUnsafe(hashes.head)
        val chainDep = header.uncleHash(chainIndex.to)
        if (targetChain.isBeforeUnsafe(hash, chainDep)) Some(height) else iter(height + 1)
      }
    }

    iter(fromTipHeight + 1) match {
      case None => 0
      case Some(firstConfirmationHeight) =>
        fromChain.maxHeightUnsafe - firstConfirmationHeight + 1
    }
  }

  def getToGroupConfirmationsUnsafe(hash: BlockHash, chainIndex: ChainIndex): Int = {
    assume(ChainIndex.from(hash) == chainIndex)
    val header        = getBlockHeaderUnsafe(hash)
    val toChain       = getHeaderChain(chainIndex.to, chainIndex.to)
    val toGroupTip    = getGroupTip(header, chainIndex.to)
    val toGroupHeader = getBlockHeaderUnsafe(toGroupTip)
    val toTip         = getOutTip(toGroupHeader, chainIndex.to)
    val toTipHeight   = toChain.getHeightUnsafe(toTip)

    assume(ChainIndex.from(toTip) == ChainIndex(chainIndex.to, chainIndex.to))

    @tailrec
    def iter(height: Int): Option[Int] = {
      val hashes = toChain.getHashesUnsafe(height)
      if (hashes.isEmpty) {
        None
      } else {
        val header   = toChain.getBlockHeaderUnsafe(hashes.head)
        val chainDep = getGroupTip(header, chainIndex.from)
        if (isExtendingUnsafe(chainDep, hash)) Some(height) else iter(height + 1)
      }
    }

    if (header.isGenesis) {
      toChain.maxHeightUnsafe - ALF.GenesisHeight + 1
    } else {
      iter(toTipHeight + 1) match {
        case None => 0
        case Some(firstConfirmationHeight) =>
          toChain.maxHeightUnsafe - firstConfirmationHeight + 1
      }
    }
  }

  def getUtxosInCache(
      groupIndex: GroupIndex,
      bestDeps: BlockDeps,
      lockupScript: LockupScript,
      persistedUtxos: AVector[AssetOutputInfo]
  ): IOResult[(AVector[AssetOutputRef], AVector[AssetOutputInfo])] = {
    getBlocksForUpdates(groupIndex, bestDeps).map { blockCaches =>
      val usedUtxos = blockCaches.flatMap[AssetOutputRef] { blockCache =>
        AVector.from(
          blockCache.inputs.view
            .filter(input => persistedUtxos.exists(_.ref == input))
            .map(_.asInstanceOf[AssetOutputRef])
        )
      }
      val newUtxos = blockCaches.flatMap { blockCache =>
        AVector
          .from(
            blockCache.relatedOutputs.view
              .filter(p => ableToUse(p._2, lockupScript) && p._1.isAssetType && p._2.isAsset)
              .map(p =>
                AssetOutputInfo(
                  p._1.asInstanceOf[AssetOutputRef],
                  p._2.asInstanceOf[AssetOutput],
                  UnpersistedBlockOutput
                )
              )
          )
      }
      (usedUtxos, newUtxos)
    }
  }

  // return all the txs that are not valid
  def recheckInputs(
      groupIndex: GroupIndex,
      txs: AVector[TransactionTemplate]
  ): IOResult[AVector[TransactionTemplate]] = {
    val bestDeps = getBestDeps(groupIndex)
    for {
      blockCaches <- getBlocksForUpdates(groupIndex, bestDeps)
      worldState  <- getPersistedWorldState(bestDeps, groupIndex)
      failedTxs   <- txs.filterNotE(recheckInputs(_, worldState, blockCaches))
    } yield failedTxs
  }

  private def recheckInputs(
      tx: TransactionTemplate,
      worldState: WorldState.Persisted,
      blockCaches: AVector[BlockCache]
  ): IOResult[Boolean] = {
    tx.unsigned.inputs.forallE { input =>
      if (blockCaches.exists(_.relatedOutputs.contains(input.outputRef))) {
        Right(true)
      } else {
        worldState.existOutput(input.outputRef)
      }
    }
  }

  private def checkWithMinimalGas(
      gasOpt: Option[GasBox],
      minimalGas: GasBox
  ): Either[String, Unit] = {
    gasOpt match {
      case None => Right(())
      case Some(gas) =>
        if (gas < minimalGas) Left(s"Invalid gas $gas, minimal $minimalGas") else Right(())
    }
  }

  private def ableToUse(
      output: TxOutput,
      lockupScript: LockupScript
  ): Boolean =
    output match {
      case o: AssetOutput    => o.lockupScript == lockupScript
      case _: ContractOutput => false
    }

  private def checkWithMaxTxInputNum(assets: AVector[Asset]): Either[String, Unit] = {
    if (assets.length > ALF.MaxTxInputNum) {
      Left(s"Too many inputs for the transfer, consider to reduce the amount to send")
    } else {
      Right(())
    }
  }

  private def checkOutputInfos(
      outputInfos: AVector[(LockupScript, U256, Option[TimeStamp])]
  ): Either[String, Unit] = {
    if (outputInfos.isEmpty) {
      Left("Zero transaction outputs")
    } else {
      val groupIndexes = outputInfos.map(_._1.groupIndex)

      if (groupIndexes.forall(_ == groupIndexes.head)) {
        Right(())
      } else {
        Left("Different groups for transaction outputs")
      }
    }
  }
}

object TxUtils {
  def isSpent(blockCaches: AVector[BlockCache], outputRef: TxOutputRef): Boolean = {
    blockCaches.exists(_.inputs.contains(outputRef))
  }
}
