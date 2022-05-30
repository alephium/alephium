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

import TxUtils._

import org.alephium.flow.core.BlockFlowState.{BlockCache, Confirmed, MemPooled, TxStatus}
import org.alephium.flow.core.FlowUtils._
import org.alephium.flow.core.UtxoSelectionAlgo.{AssetAmounts, ProvidedGas}
import org.alephium.flow.gasestimation._
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.{ALPH, BlockHash, Hash, PublicKey}
import org.alephium.protocol.model._
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, UnlockScript}
import org.alephium.util.{AVector, TimeStamp, U256}

trait TxUtils { Self: FlowUtils =>

  // We call getUsableUtxosOnce multiple times until the resulted tx does not change
  // In this way, we can guarantee that no concurrent utxos operations are making trouble
  def getUsableUtxos(
      lockupScript: LockupScript.Asset,
      maxUtxosToRead: Int
  ): IOResult[AVector[AssetOutputInfo]] = {
    @tailrec
    def iter(lastTryOpt: Option[AVector[AssetOutputInfo]]): IOResult[AVector[AssetOutputInfo]] = {
      getUsableUtxosOnce(lockupScript, maxUtxosToRead) match {
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
      lockupScript: LockupScript.Asset,
      maxUtxosToRead: Int
  ): IOResult[AVector[AssetOutputInfo]] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))
    for {
      groupView <- getImmutableGroupViewIncludePool(groupIndex)
      outputs   <- groupView.getRelevantUtxos(lockupScript, maxUtxosToRead)
    } yield {
      val currentTs = TimeStamp.now()
      outputs.filter(_.output.lockTime <= currentTs)
    }
  }

  // return the total balance, the locked balance, and the number of all utxos
  def getBalance(lockupScript: LockupScript.Asset, utxosLimit: Int): IOResult[(U256, U256, Int)] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))

    val currentTs = TimeStamp.now()

    getUTXOsIncludePool(lockupScript, utxosLimit).map { utxos =>
      val balance = utxos.fold(U256.Zero)(_ addUnsafe _.output.amount)
      val lockedBalance = utxos.fold(U256.Zero) { case (acc, utxo) =>
        if (utxo.output.lockTime > currentTs) acc addUnsafe utxo.output.amount else acc
      }
      (balance, lockedBalance, utxos.length)
    }
  }

  def getUTXOsIncludePool(
      lockupScript: LockupScript.Asset,
      utxosLimit: Int
  ): IOResult[AVector[AssetOutputInfo]] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))

    getImmutableGroupViewIncludePool(groupIndex)
      .flatMap(_.getRelevantUtxos(lockupScript, utxosLimit))
  }

  def transfer(
      fromPublicKey: PublicKey,
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp],
      amount: U256,
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      utxoLimit: Int
  ): IOResult[Either[String, UnsignedTransaction]] = {
    transfer(
      fromPublicKey,
      AVector(TxOutputInfo(toLockupScript, amount, AVector.empty, lockTimeOpt)),
      gasOpt,
      gasPrice,
      utxoLimit
    )
  }

  def transfer(
      fromPublicKey: PublicKey,
      outputInfos: AVector[TxOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      utxoLimit: Int
  ): IOResult[Either[String, UnsignedTransaction]] = {
    val fromLockupScript = LockupScript.p2pkh(fromPublicKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromPublicKey)
    transfer(
      fromLockupScript,
      fromUnlockScript,
      outputInfos,
      gasOpt,
      gasPrice,
      utxoLimit
    )
  }

  // scalastyle:off method.length
  def transfer(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      outputInfos: AVector[TxOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      utxosLimit: Int
  ): IOResult[Either[String, UnsignedTransaction]] = {
    val totalAmountsE = for {
      _               <- checkOutputInfos(fromLockupScript.groupIndex, outputInfos)
      _               <- checkProvidedGas(gasOpt, gasPrice)
      totalAlphAmount <- checkTotalAlphAmount(outputInfos.map(_.alphAmount))
      totalAmountPerToken <- UnsignedTransaction.calculateTotalAmountPerToken(
        outputInfos.flatMap(_.tokens)
      )
    } yield (totalAlphAmount, totalAmountPerToken)

    totalAmountsE match {
      case Right((totalAmount, totalAmountPerToken)) =>
        getUsableUtxos(fromLockupScript, utxosLimit)
          .map { utxos =>
            UtxoSelectionAlgo
              .Build(dustUtxoAmount, ProvidedGas(gasOpt, gasPrice))
              .select(
                AssetAmounts(totalAmount, totalAmountPerToken),
                fromUnlockScript,
                utxos,
                outputInfos.length + 1,
                txScriptOpt = None,
                AssetScriptGasEstimator.Default(Self.blockFlow),
                TxScriptGasEstimator.NotImplemented
              )
          }
          .map { utxoSelectionResult =>
            for {
              selected <- utxoSelectionResult
              _        <- checkEstimatedGasAmount(selected.gas)
              unsignedTx <- UnsignedTransaction
                .build(
                  fromLockupScript,
                  fromUnlockScript,
                  selected.assets.map(asset => (asset.ref, asset.output)),
                  outputInfos,
                  selected.gas,
                  gasPrice
                )
            } yield unsignedTx
          }

      case Left(e) =>
        Right(Left(e))
    }
  }

  def transfer(
      fromPublicKey: PublicKey,
      utxoRefs: AVector[AssetOutputRef],
      outputInfos: AVector[TxOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): IOResult[Either[String, UnsignedTransaction]] = {
    if (utxoRefs.isEmpty) {
      Right(Left("Empty UTXOs"))
    } else {
      val groupIndex = utxoRefs.head.hint.groupIndex
      assume(brokerConfig.contains(groupIndex))

      val fromLockupScript = LockupScript.p2pkh(fromPublicKey)
      val fromUnlockScript = UnlockScript.p2pkh(fromPublicKey)

      val checkResult = for {
        _ <- checkUTXOsInSameGroup(utxoRefs)
        _ <- checkOutputInfos(fromLockupScript.groupIndex, outputInfos)
        _ <- checkProvidedGas(gasOpt, gasPrice)
        _ <- checkTotalAlphAmount(outputInfos.map(_.alphAmount))
        _ <- UnsignedTransaction.calculateTotalAmountPerToken(
          outputInfos.flatMap(_.tokens)
        )
      } yield ()

      checkResult match {
        case Right(()) =>
          getImmutableGroupViewIncludePool(groupIndex)
            .flatMap(_.getPrevAssetOutputs(utxoRefs))
            .map { utxosOpt =>
              val outputScripts = fromLockupScript +: outputInfos.map(_.lockupScript)
              for {
                gas <- gasOpt match {
                  case None =>
                    for {
                      estimatedGas <- GasEstimation.estimateWithInputScript(
                        fromUnlockScript,
                        utxoRefs.length,
                        outputScripts.length,
                        AssetScriptGasEstimator.NotImplemented // Not P2SH
                      )
                      _ <- checkEstimatedGasAmount(estimatedGas)
                    } yield estimatedGas
                  case Some(gas) =>
                    Right(gas)
                }
                utxos <- utxosOpt.toRight("Can not find all selected UTXOs")
                unsignedTx <- UnsignedTransaction
                  .build(fromLockupScript, fromUnlockScript, utxos, outputInfos, gas, gasPrice)
              } yield unsignedTx
            }
        case Left(e) =>
          Right(Left(e))
      }
    }
  }
  // scalastyle:on method.length

  def sweepAddress(
      fromPublicKey: PublicKey,
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      utxosLimit: Int
  ): IOResult[Either[String, AVector[UnsignedTransaction]]] = {
    val fromLockupScript = LockupScript.p2pkh(fromPublicKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromPublicKey)

    val checkResult = checkProvidedGas(gasOpt, gasPrice)

    checkResult match {
      case Right(()) =>
        getUsableUtxos(fromLockupScript, utxosLimit).map { allUtxos =>
          // Sweep as much as we can, taking maximalGasPerTx into consideration
          // Gas for ALPH.MaxTxInputNum P2PKH inputs exceeds maximalGasPerTx
          val groupedUtxos = allUtxos.groupedWithRemainder(ALPH.MaxTxInputNum / 2)
          groupedUtxos.mapE { utxos =>
            for {
              txOutputsWithGas <- buildSweepAddressTxOutputsWithGas(
                toLockupScript,
                lockTimeOpt,
                utxos.map(_.output),
                gasOpt,
                gasPrice
              )
              unsignedTx <- UnsignedTransaction
                .build(
                  fromLockupScript,
                  fromUnlockScript,
                  utxos.map(asset => (asset.ref, asset.output)),
                  txOutputsWithGas._1,
                  txOutputsWithGas._2,
                  gasPrice
                )
            } yield unsignedTx
          }
        }

      case Left(e) =>
        Right(Left(e))
    }
  }

  def isTxConfirmed(txId: Hash, chainIndex: ChainIndex): IOResult[Boolean] = {
    assume(brokerConfig.contains(chainIndex.from))
    val chain = getBlockChain(chainIndex)
    chain.isTxConfirmed(txId)
  }

  def getTxStatus(txId: Hash, chainIndex: ChainIndex): IOResult[Option[TxStatus]] =
    IOUtils.tryExecute {
      assume(brokerConfig.contains(chainIndex.from))
      val chain = getBlockChain(chainIndex)
      chain.getTxStatusUnsafe(txId).flatMap { chainStatus =>
        val confirmations = chainStatus.confirmations
        if (chainIndex.isIntraGroup) {
          Some(Confirmed(chainStatus.index, confirmations, confirmations, confirmations))
        } else {
          val confirmHash = chainStatus.index.hash
          val fromGroupConfirmations =
            getFromGroupConfirmationsUnsafe(confirmHash, chainIndex)
          val toGroupConfirmations =
            getToGroupConfirmationsUnsafe(confirmHash, chainIndex)
          Some(
            Confirmed(
              chainStatus.index,
              confirmations,
              fromGroupConfirmations,
              toGroupConfirmations
            )
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
      toChain.maxHeightUnsafe - ALPH.GenesisHeight + 1
    } else {
      iter(toTipHeight + 1) match {
        case None => 0
        case Some(firstConfirmationHeight) =>
          toChain.maxHeightUnsafe - firstConfirmationHeight + 1
      }
    }
  }

  // return all the txs that are not valid
  def recheckInputs(
      groupIndex: GroupIndex,
      txs: AVector[TransactionTemplate]
  ): IOResult[AVector[TransactionTemplate]] = {
    for {
      groupView <- getImmutableGroupView(groupIndex)
      failedTxs <- txs.filterE(tx => groupView.getPreOutputs(tx.unsigned.inputs).map(_.isEmpty))
    } yield failedTxs
  }

  def searchLocalTransactionStatus(
      txId: Hash,
      chainIndexes: AVector[ChainIndex]
  ): Either[String, Option[TxStatus]] = {
    @tailrec
    def rec(
        indexes: AVector[ChainIndex],
        currentRes: Either[String, Option[TxStatus]]
    ): Either[String, Option[TxStatus]] = {
      indexes.headOption match {
        case Some(index) =>
          val res = getTransactionStatus(txId, index)
          res match {
            case Right(None) => rec(indexes.tail, res)
            case Right(_)    => res
            case Left(_)     => res
          }
        case None =>
          currentRes
      }
    }
    rec(chainIndexes, Right(None))
  }

  def getTransactionStatus(
      txId: Hash,
      chainIndex: ChainIndex
  ): Either[String, Option[TxStatus]] = {
    if (brokerConfig.contains(chainIndex.from)) {
      for {
        status <- getTxStatus(txId, chainIndex)
          .map {
            case Some(status) => Some(status)
            case None         => if (isInMemPool(txId, chainIndex)) Some(MemPooled) else None
          }
          .left
          .map(_.toString)
      } yield status
    } else {
      Right(None)
    }
  }

  def isInMemPool(txId: Hash, chainIndex: ChainIndex): Boolean = {
    Self.blockFlow.getMemPool(chainIndex).contains(chainIndex, txId)
  }

  def checkTxChainIndex(chainIndex: ChainIndex, tx: Hash): Either[String, Unit] = {
    if (brokerConfig.contains(chainIndex.from)) {
      Right(())
    } else {
      Left(s"${tx.toHexString} belongs to other groups")
    }
  }

  private def checkProvidedGas(gasOpt: Option[GasBox], gasPrice: GasPrice): Either[String, Unit] = {
    for {
      _ <- checkProvidedGasAmount(gasOpt)
      _ <- checkGasPrice(gasPrice)
    } yield ()
  }

  private def checkProvidedGasAmount(gasOpt: Option[GasBox]): Either[String, Unit] = {
    gasOpt match {
      case None => Right(())
      case Some(gas) =>
        if (gas < minimalGas) {
          Left(s"Provided gas $gas too small, minimal $minimalGas")
        } else if (gas > maximalGasPerTx) {
          Left(s"Provided gas $gas too large, maximal $maximalGasPerTx")
        } else {
          Right(())
        }
    }
  }

  private def checkEstimatedGasAmount(gas: GasBox): Either[String, Unit] = {
    if (gas > maximalGasPerTx) {
      Left(
        s"Estimated gas $gas too large, maximal $maximalGasPerTx. " ++
          "Consider consolidating UTXOs using the sweep endpoints"
      )
    } else {
      Right(())
    }
  }

  private def checkGasPrice(gasPrice: GasPrice): Either[String, Unit] = {
    if (gasPrice < minimalGasPrice) {
      Left(s"Gas price $gasPrice too small, minimal $minimalGasPrice")
    } else if (gasPrice.value >= ALPH.MaxALPHValue) {
      val maximalGasPrice = GasPrice(ALPH.MaxALPHValue.subOneUnsafe())
      Left(s"Gas price $gasPrice too large, maximal $maximalGasPrice")
    } else {
      Right(())
    }
  }

  private def checkOutputInfos(
      fromGroup: GroupIndex,
      outputInfos: AVector[TxOutputInfo]
  ): Either[String, Unit] = {
    if (outputInfos.isEmpty) {
      Left("Zero transaction outputs")
    } else if (outputInfos.length > ALPH.MaxTxOutputNum) {
      Left(s"Too many transaction outputs, maximal value: ${ALPH.MaxTxOutputNum}")
    } else {
      val groupIndexes = outputInfos.map(_.lockupScript.groupIndex).filter(_ != fromGroup)

      if (groupIndexes.forall(_ == groupIndexes.head)) {
        Right(())
      } else {
        Left("Different groups for transaction outputs")
      }
    }
  }

  private def checkUTXOsInSameGroup(
      utxoRefs: AVector[AssetOutputRef]
  ): Either[String, Unit] = {
    val groupIndexes = utxoRefs.map(_.hint.groupIndex)

    Either.cond(
      groupIndexes.forall(_ == groupIndexes.head),
      (),
      "Selected UTXOs are not from the same group"
    )
  }
}

object TxUtils {
  def isSpent(blockCaches: AVector[BlockCache], outputRef: TxOutputRef): Boolean = {
    blockCaches.exists(_.inputs.contains(outputRef))
  }

  // Normally there is one output for the `sweepAddress` transaction. However, If there are more
  // tokens than `maxTokenPerUtxo`, instead of failing the transaction validation, we will
  // try to build a valid transaction by creating more outputs.
  def buildSweepAddressTxOutputsWithGas(
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp],
      utxos: AVector[AssetOutput],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): Either[String, (AVector[TxOutputInfo], GasBox)] = {
    for {
      totalAmount <- checkTotalAlphAmount(utxos.map(_.amount))
      totalAmountPerToken <- UnsignedTransaction.calculateTotalAmountPerToken(
        utxos.flatMap(_.tokens)
      )
      groupsOfTokens    = (totalAmountPerToken.length + maxTokenPerUtxo - 1) / maxTokenPerUtxo
      extraNumOfOutputs = Math.max(0, groupsOfTokens - 1)
      gas = gasOpt.getOrElse(GasEstimation.sweepAddress(utxos.length, extraNumOfOutputs + 1))
      totalAmountWithoutGas <- totalAmount
        .sub(gasPrice * gas)
        .toRight("Not enough balance for gas fee")
      amountRequiredForExtraOutputs <- minimalAlphAmountPerTxOutput(maxTokenPerUtxo)
        .mul(U256.unsafe(extraNumOfOutputs))
        .toRight("Too many tokens")
      amountOfFirstOutput <- totalAmountWithoutGas
        .sub(amountRequiredForExtraOutputs)
        .toRight("Not enough ALPH balance for transaction outputs")
      firstOutputTokensNum = getFirstOutputTokensNum(totalAmountPerToken.length)
      _ <- amountOfFirstOutput
        .sub(minimalAlphAmountPerTxOutput(firstOutputTokensNum))
        .toRight("Not enough ALPH balance for transaction outputs")
    } yield {
      val firstTokens  = totalAmountPerToken.take(firstOutputTokensNum)
      val restOfTokens = totalAmountPerToken.drop(firstOutputTokensNum).grouped(maxTokenPerUtxo)
      val firstOutput  = TxOutputInfo(toLockupScript, amountOfFirstOutput, firstTokens, lockTimeOpt)
      val restOfOutputs = restOfTokens.map { tokens =>
        TxOutputInfo(
          toLockupScript,
          minimalAlphAmountPerTxOutput(maxTokenPerUtxo),
          tokens,
          lockTimeOpt
        )
      }

      (firstOutput +: restOfOutputs, gas)
    }
  }

  private[core] def getFirstOutputTokensNum(tokensNum: Int): Int = {
    val remainder = tokensNum % maxTokenPerUtxo
    if (tokensNum == 0) {
      tokensNum
    } else if (remainder == 0) {
      maxTokenPerUtxo
    } else {
      remainder
    }
  }

  private[core] def checkTotalAlphAmount(
      amounts: AVector[U256]
  ): Either[String, U256] = {
    amounts.foldE(U256.Zero) { case (acc, amount) =>
      acc.add(amount).toRight("Alph Amount overflow").flatMap { newAmount =>
        if (newAmount > ALPH.MaxALPHValue) {
          Left("ALPH amount overflow")
        } else {
          Right(newAmount)
        }
      }
    }
  }
}
