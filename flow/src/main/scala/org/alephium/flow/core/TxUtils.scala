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
import scala.collection.mutable

import TxUtils._

import org.alephium.flow.core.BlockFlowState.{BlockCache, Confirmed, MemPooled, TxStatus}
import org.alephium.flow.core.FlowUtils._
import org.alephium.flow.core.UtxoSelectionAlgo.{AssetAmounts, ProvidedGas}
import org.alephium.flow.gasestimation._
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.{ALPH, PublicKey}
import org.alephium.protocol.model._
import org.alephium.protocol.model.UnsignedTransaction.{TxOutputInfo, UnlockScriptWithAssets}
import org.alephium.protocol.vm.{GasBox, GasPrice, GasSchedule, LockupScript, UnlockScript}
import org.alephium.util.{AVector, TimeStamp, U256}

// scalastyle:off number.of.methods
// scalastyle:off file.size.limit number.of.types
trait TxUtils { Self: FlowUtils =>

  // We call getUsableUtxosOnce multiple times until the resulted tx does not change
  // In this way, we can guarantee that no concurrent utxos operations are making trouble
  def getUsableUtxos(
      targetBlockHashOpt: Option[BlockHash],
      lockupScript: LockupScript.Asset,
      maxUtxosToRead: Int
  ): IOResult[AVector[AssetOutputInfo]] = {
    @tailrec
    def iter(lastTryOpt: Option[AVector[AssetOutputInfo]]): IOResult[AVector[AssetOutputInfo]] = {
      getUsableUtxosOnce(targetBlockHashOpt, lockupScript, maxUtxosToRead) match {
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

  def getUsableUtxos(
      lockupScript: LockupScript.Asset,
      maxUtxosToRead: Int
  ): IOResult[AVector[AssetOutputInfo]] = {
    getUsableUtxos(None, lockupScript, maxUtxosToRead)
  }

  def getUsableUtxosOnce(
      targetBlockHashOpt: Option[BlockHash],
      lockupScript: LockupScript.Asset,
      maxUtxosToRead: Int
  ): IOResult[AVector[AssetOutputInfo]] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))
    for {
      groupView <- getImmutableGroupViewIncludePool(groupIndex, targetBlockHashOpt)
      outputs   <- groupView.getRelevantUtxos(lockupScript, maxUtxosToRead)
    } yield {
      val currentTs = TimeStamp.now()
      outputs.filter(_.output.lockTime <= currentTs)
    }
  }

  // scalastyle:off parameter.number
  private def selectUTXOs(
      targetBlockHashOpt: Option[BlockHash],
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      totalAmount: U256,
      totalAmountPerToken: AVector[(TokenId, U256)],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      utxosLimit: Int,
      txOutputLength: Int
  ): IOResult[Either[String, UtxoSelectionAlgo.Selected]] = {
    getUsableUtxos(targetBlockHashOpt, fromLockupScript, utxosLimit)
      .map { utxos =>
        UtxoSelectionAlgo
          .Build(ProvidedGas(gasOpt, gasPrice))
          .select(
            AssetAmounts(totalAmount, totalAmountPerToken),
            fromUnlockScript,
            utxos,
            txOutputLength,
            txScriptOpt = None,
            AssetScriptGasEstimator.Default(Self.blockFlow),
            TxScriptGasEstimator.NotImplemented
          )
      }
  }

  private def getUTXOsFromRefs(
      groupIndex: GroupIndex,
      targetBlockHashOpt: Option[BlockHash],
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      utxoRefs: AVector[AssetOutputRef],
      outputInfos: AVector[TxOutputInfo],
      gasOpt: Option[GasBox]
  ): IOResult[Either[String, AssetOutputInfoWithGas]] = {
    getImmutableGroupViewIncludePool(groupIndex, targetBlockHashOpt)
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
        } yield {
          AssetOutputInfoWithGas(utxos, gas)
        }
      }
  }

  // return the total balance, the locked balance, and the number of all utxos
  def getBalance(
      lockupScript: LockupScript,
      utxosLimit: Int,
      getMempoolUtxos: Boolean
  ): IOResult[(U256, U256, AVector[(TokenId, U256)], AVector[(TokenId, U256)], Int)] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))

    getUTXOs(lockupScript, utxosLimit, getMempoolUtxos).map { utxos =>
      val utxosNum = utxos.length

      val (attoAlphBalance, attoAlphLockedBalance, tokenBalances, tokenLockedBalances) =
        TxUtils.getBalance(utxos.map(_.output))
      (attoAlphBalance, attoAlphLockedBalance, tokenBalances, tokenLockedBalances, utxosNum)
    }
  }

  def getUTXOs(
      lockupScript: LockupScript,
      utxosLimit: Int,
      getMempoolUtxos: Boolean
  ): IOResult[AVector[OutputInfo]] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))

    lockupScript match {
      case ls: LockupScript.Asset =>
        val blockFlowGroupView = if (getMempoolUtxos) {
          getImmutableGroupViewIncludePool(groupIndex)
        } else {
          getImmutableGroupView(groupIndex)
        }
        blockFlowGroupView.flatMap(
          _.getRelevantUtxos(ls, utxosLimit).map(_.as[OutputInfo])
        )
      case ls: LockupScript.P2C =>
        getBestPersistedWorldState(groupIndex).flatMap(
          _.getContractOutputInfo(ls.contractId).map { case (ref, output) =>
            AVector(ContractOutputInfo(ref, output))
          }
        )
    }
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
      None,
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
      targetBlockHashOpt: Option[BlockHash],
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
      _               <- checkTotalAttoAlphAmount(outputInfos.map(_.attoAlphAmount))
      calculateResult <- UnsignedTransaction.calculateTotalAmountNeeded(outputInfos)
    } yield calculateResult

    totalAmountsE match {
      case Right((totalAmount, totalAmountPerToken, txOutputLength)) =>
        selectUTXOs(
          targetBlockHashOpt,
          fromLockupScript,
          fromUnlockScript,
          totalAmount,
          totalAmountPerToken,
          gasOpt,
          gasPrice,
          utxosLimit,
          txOutputLength
        ).map { utxoSelectionResult =>
          for {
            selected <- utxoSelectionResult
            _        <- checkEstimatedGasAmount(selected.gas)
            unsignedTx <- UnsignedTransaction
              .buildTransferTx(
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
    val fromLockupScript = LockupScript.p2pkh(fromPublicKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromPublicKey)

    transfer(
      None,
      fromLockupScript,
      fromUnlockScript,
      utxoRefs,
      outputInfos,
      gasOpt,
      gasPrice
    )
  }

  def transfer(
      targetBlockHashOpt: Option[BlockHash],
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
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

      val checkResult = for {
        _ <- checkUTXOsInSameGroup(utxoRefs)
        _ <- checkOutputInfos(fromLockupScript.groupIndex, outputInfos)
        _ <- checkProvidedGas(gasOpt, gasPrice)
        _ <- checkTotalAttoAlphAmount(outputInfos.map(_.attoAlphAmount))
        _ <- UnsignedTransaction.calculateTotalAmountPerToken(
          outputInfos.flatMap(_.tokens)
        )
      } yield ()

      checkResult match {
        case Right(()) =>
          getUTXOsFromRefs(
            groupIndex,
            targetBlockHashOpt,
            fromLockupScript,
            fromUnlockScript,
            utxoRefs,
            outputInfos,
            gasOpt
          ).map(_.flatMap { assetsWithGas =>
            UnsignedTransaction
              .buildTransferTx(
                fromLockupScript,
                fromUnlockScript,
                assetsWithGas.assets,
                outputInfos,
                assetsWithGas.gas,
                gasPrice
              )
          })
        case Left(e) =>
          Right(Left(e))
      }
    }
  }

  def transferMultiInputs(
      targetBlockHashOpt: Option[BlockHash],
      inputs: AVector[InputData],
      outputInfos: AVector[TxOutputInfo],
      gasPrice: GasPrice,
      utxosLimit: Int
  ): IOResult[Either[String, UnsignedTransaction]] = {
    val dustAmountsE = for {
      groupIndex  <- checkMultiInputsGroup(inputs)
      _           <- checkOutputInfos(groupIndex, outputInfos)
      dustAmounts <- calculateDustAmountNeeded(outputInfos)
    } yield dustAmounts

    dustAmountsE match {
      case Right((dustAmountNeeded, dustAmountPerTokenNeeded, txOutputLength)) =>
        selectMultiInputsUtxos(
          inputs,
          outputInfos,
          dustAmountNeeded,
          dustAmountPerTokenNeeded,
          targetBlockHashOpt,
          gasPrice,
          utxosLimit,
          txOutputLength
        ).map(_.flatMap { selecteds =>
          for {
            _ <- checkProvidedGas(
              Some(selecteds.map(_._2.gas).fold(GasBox.zero)(_ addUnsafe _)),
              gasPrice
            )
            tx <- buildMultiInputUnsignedTransaction(selecteds, outputInfos, gasPrice)
          } yield tx
        })

      case Left(e) =>
        Right(Left(e))
    }
  }

  /*
   * If the input has define `utxos`, we try to get them.
   * If none was defined, we let the `UtxoSelectionAlgo` finding enough
   * utxos to cover everything: ALPH, tokens and gas.
   */
  // scalastyle:off parameter.number
  def selectInputDataUtxos(
      input: InputData,
      outputInfos: AVector[TxOutputInfo],
      dustAmountPerInputNeeded: U256,
      dustAmountPerTokenNeeded: Map[TokenId, U256],
      targetBlockHashOpt: Option[BlockHash],
      gasPrice: GasPrice,
      utxosLimit: Int,
      txOutputLength: Int
  ): IOResult[Either[String, AssetOutputInfoWithGas]] = {
    input.utxos match {
      case None =>
        val amountWithDust = input.amount.add(dustAmountPerInputNeeded).getOrElse(input.amount)
        val tokensWithDust = input.tokens.getOrElse(AVector.empty).map { case (id, a) =>
          (id, a.add(dustAmountPerTokenNeeded.getOrElse(id, U256.Zero)).getOrElse(a))
        }
        selectUTXOs(
          targetBlockHashOpt,
          input.fromLockupScript,
          input.fromUnlockScript,
          amountWithDust,
          tokensWithDust,
          input.gasOpt,
          gasPrice,
          utxosLimit,
          txOutputLength
        ).map(_.map { selected =>
          AssetOutputInfoWithGas(
            selected.assets.map(asset => (asset.ref, asset.output)),
            selected.gas
          )
        })

      case Some(utxoRefs) =>
        getUTXOsFromRefs(
          input.fromLockupScript.groupIndex,
          targetBlockHashOpt,
          input.fromLockupScript,
          input.fromUnlockScript,
          utxoRefs,
          outputInfos,
          input.gasOpt
        )
    }
  }

  def selectMultiInputsUtxos(
      inputs: AVector[InputData],
      outputInfos: AVector[TxOutputInfo],
      dustAmountPerInputNeeded: U256,
      dustAmountPerTokenNeeded: Map[TokenId, U256],
      targetBlockHashOpt: Option[BlockHash],
      gasPrice: GasPrice,
      utxosLimit: Int,
      txOutputLength: Int
  ): IOResult[Either[String, AVector[(InputData, AssetOutputInfoWithGas)]]] = {
    /*
     * If one `gasOpt` is defined, then every input is following its `gasOpt` value
     * If `gasOpt` is empty, we let the `UtxoSelectionAlgo` choose enough utxos so everyone can
     * pay the base fee and a simple tx, but later we reduce the fees.
     */
    val gasDefined = inputs.exists(_.gasOpt.isDefined)

    inputs
      .mapE { input =>
        selectInputDataUtxos(
          input.copy(gasOpt = if (gasDefined) input.gasOpt.orElse(Some(GasBox.zero)) else None),
          outputInfos,
          dustAmountPerInputNeeded,
          dustAmountPerTokenNeeded,
          targetBlockHashOpt,
          gasPrice,
          utxosLimit,
          txOutputLength
        ).map(_.map(utxosWithGas => (input, utxosWithGas)))
      }
      .map(_.mapE(identity))
      .map(_.map { selecteds =>
        if (gasDefined) {
          // If gas was defined we just take what was returned by the selection algo
          selecteds
        } else {
          // If gas was not defined, we can lower gas for every inputs
          updateSelectedGas(selecteds)
        }
      })
  }

  def buildMultiInputUnsignedTransaction(
      inputs: AVector[(InputData, AssetOutputInfoWithGas)],
      outputInfos: AVector[TxOutputInfo],
      gasPrice: GasPrice
  ): Either[String, UnsignedTransaction] = {
    inputs
      .mapE { case (input, utxosWithGas) =>
        makeChangeOutput(input, utxosWithGas, gasPrice).map { change =>
          val from =
            UnlockScriptWithAssets(
              input.fromUnlockScript,
              utxosWithGas.assets
            )
          (from, change, utxosWithGas.gas)
        }
      }
      .flatMap { result =>
        val (froms, changes, gases) = result.toSeq.unzip3
        UnsignedTransaction.buildGeneric(
          AVector.from(froms),
          outputInfos ++ AVector.from(changes.flatMap(identity)),
          gases.fold(GasBox.zero)(_ addUnsafe _),
          gasPrice
        )
      }
  }

  /*
   * Update the gas for all inputs.
   * Each input pay individually for their own utxos, more inputs mean higher fees
   * The base fee needed for the tx is then shared by everyone
   */
  def updateSelectedGas(
      inputs: AVector[(InputData, AssetOutputInfoWithGas)]
  ): AVector[(InputData, AssetOutputInfoWithGas)] = {
    val gasPerInput = inputs.map { case (input, selected) =>
      val inGas = GasSchedule.txInputBaseGas.mulUnsafe(selected.assets.length)
      // 1 output for change TODO can we have more? we could have 0 tho
      val outGas   = GasSchedule.txOutputBaseGas
      val inOutGas = inGas.addUnsafe(outGas)
      val baseFee  = selected.gas.sub(inOutGas).getOrElse(GasBox.zero)
      (input, selected.copy(gas = inOutGas), baseFee)
    }

    val baseFeeShared = GasBox.unsafe(gasPerInput.map(_._3.value).sum / (2 * inputs.length))

    gasPerInput.map { case (input, selected, _) =>
      val payedGas = selected.gas.addUnsafe(baseFeeShared)
      (input, selected.copy(gas = payedGas))
    }
  }

  private def makeChangeOutput(
      input: InputData,
      selected: AssetOutputInfoWithGas,
      gasPrice: GasPrice
  ): Either[String, AVector[TxOutputInfo]] = {
    for {
      alphRemainder   <- calculateAlphRemainder(input, selected, gasPrice)
      tokensRemainder <- calculateTokensRemainder(input, selected)
      changes <- UnsignedTransaction.calculateChangeOutputs(
        alphRemainder,
        tokensRemainder,
        input.fromLockupScript
      )
    } yield {
      changes.map { asset =>
        TxOutputInfo(
          asset.lockupScript,
          asset.amount,
          asset.tokens,
          Some(asset.lockTime),
          Some(asset.additionalData)
        )
      }
    }
  }

  private def calculateAlphRemainder(
      input: InputData,
      selected: AssetOutputInfoWithGas,
      gasPrice: GasPrice
  ): Either[String, U256] = {
    UnsignedTransaction.calculateAlphRemainder(
      selected.assets.map(_._2.amount),
      AVector(input.amount),
      gasPrice * selected.gas
    )
  }

  private def calculateTokensRemainder(
      input: InputData,
      selected: AssetOutputInfoWithGas
  ): Either[String, AVector[(TokenId, U256)]] = {
    UnsignedTransaction.calculateTokensRemainder(
      selected.assets.map(_._2).flatMap(_.tokens),
      input.tokens.getOrElse(AVector.empty)
    )
  }

  // Each inputs will need to add some dust amount when selecting UTXOs to cover the complicated cases
  def calculateDustAmountNeeded(
      outputInfos: AVector[TxOutputInfo]
  ): Either[String, (U256, Map[TokenId, U256], Int)] = {
    for {
      totalAmount <- checkTotalAttoAlphAmount(outputInfos.map(_.attoAlphAmount))
      totalPerToken <- UnsignedTransaction
        .calculateTotalAmountPerToken(outputInfos.flatMap(_.tokens))
        .map(_.view.toMap)
      calculateResult <- UnsignedTransaction.calculateTotalAmountNeeded(outputInfos)
      (totalAmountNeeded, totalAmountPerTokenNeeded, txOutputLength) = calculateResult
      dustAmount <- totalAmountNeeded.sub(totalAmount).toRight("ALPH underflow")
      dustAmountPerToken <- totalAmountPerTokenNeeded
        .mapE { case (id, amount) =>
          totalPerToken.get(id) match {
            case Some(value) => amount.sub(value).map(v => (id, v)).toRight("ALPH underflow")
            case None        => Right((id, U256.Zero))
          }
        }
    } yield (dustAmount, dustAmountPerToken.view.toMap, txOutputLength)
  }

  def sweepAddress(
      targetBlockHashOpt: Option[BlockHash],
      fromPublicKey: PublicKey,
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      maxAttoAlphPerUTXOOpt: Option[U256],
      utxosLimit: Int
  ): IOResult[Either[String, AVector[UnsignedTransaction]]] = {
    val fromLockupScript = LockupScript.p2pkh(fromPublicKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromPublicKey)
    sweepAddressFromScripts(
      targetBlockHashOpt,
      fromLockupScript,
      fromUnlockScript,
      toLockupScript,
      lockTimeOpt,
      gasOpt,
      gasPrice,
      maxAttoAlphPerUTXOOpt,
      utxosLimit
    )
  }

  // scalastyle:off parameter.number
  def sweepAddressFromScripts(
      targetBlockHashOpt: Option[BlockHash],
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      maxAttoAlphPerUTXOOpt: Option[U256],
      utxosLimit: Int
  ): IOResult[Either[String, AVector[UnsignedTransaction]]] = {
    val checkResult = checkProvidedGas(gasOpt, gasPrice)

    checkResult match {
      case Right(()) =>
        getUsableUtxos(targetBlockHashOpt, fromLockupScript, utxosLimit).map { allUtxosUnfiltered =>
          // Sweep as much as we can, taking maximalGasPerTx into consideration
          // Gas for ALPH.MaxTxInputNum P2PKH inputs exceeds maximalGasPerTx
          val allUtxos = maxAttoAlphPerUTXOOpt match {
            case Some(maxAttoAlphPerUTXO) =>
              allUtxosUnfiltered.filter(_.output.amount < maxAttoAlphPerUTXO)
            case None => allUtxosUnfiltered
          }
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
                .buildTransferTx(
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
  // scalastyle:on method.length

  def isTxConfirmed(txId: TransactionId, chainIndex: ChainIndex): IOResult[Boolean] = {
    assume(brokerConfig.contains(chainIndex.from))
    val chain = getBlockChain(chainIndex)
    chain.isTxConfirmed(txId)
  }

  def getTxConfirmedStatus(
      txId: TransactionId,
      chainIndex: ChainIndex
  ): IOResult[Option[Confirmed]] =
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
      txId: TransactionId,
      chainIndexes: AVector[ChainIndex]
  ): Either[String, Option[TxStatus]] = {
    searchByIndexes(txId, chainIndexes, getTransactionStatus)
  }

  def getTransactionStatus(
      txId: TransactionId,
      chainIndex: ChainIndex
  ): Either[String, Option[TxStatus]] = {
    if (brokerConfig.contains(chainIndex.from)) {
      for {
        status <- getTxConfirmedStatus(txId, chainIndex)
          .map[Option[TxStatus]] {
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

  private def searchByIndexes[T](
      txId: TransactionId,
      chainIndexes: AVector[ChainIndex],
      getFromLocal: (TransactionId, ChainIndex) => Either[String, Option[T]]
  ): Either[String, Option[T]] = {
    @tailrec
    def rec(
        indexes: AVector[ChainIndex],
        currentRes: Either[String, Option[T]]
    ): Either[String, Option[T]] = {
      indexes.headOption match {
        case Some(index) =>
          val res = getFromLocal(txId, index)
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

  def getTransaction(
      txId: TransactionId,
      chainIndex: ChainIndex
  ): Either[String, Option[Transaction]] = {
    if (brokerConfig.contains(chainIndex.from)) {
      val chain = Self.blockFlow.getBlockChain(chainIndex)
      chain.getTransaction(txId).left.map(_.toString)
    } else {
      Right(None)
    }
  }

  def searchTransaction(
      txId: TransactionId,
      chainIndexes: AVector[ChainIndex]
  ): Either[String, Option[Transaction]] = {
    searchByIndexes(txId, chainIndexes, getTransaction)
  }

  def isInMemPool(txId: TransactionId, chainIndex: ChainIndex): Boolean = {
    Self.blockFlow.getMemPool(chainIndex).contains(txId)
  }

  def checkTxChainIndex(chainIndex: ChainIndex, tx: TransactionId): Either[String, Unit] = {
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
    if (gasPrice < coinbaseGasPrice) {
      Left(s"Gas price $gasPrice too small, minimal $coinbaseGasPrice")
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

  private def checkMultiInputsGroup(
      inputs: AVector[InputData]
  ): Either[String, GroupIndex] = {
    if (inputs.isEmpty) {
      Left("Zero transaction inputs")
    } else {
      val groupIndexes = inputs.map(_.fromLockupScript.groupIndex)
      val groupIndex   = groupIndexes.head

      if (groupIndexes.forall(_ == groupIndex)) {
        Right(groupIndex)
      } else {
        Left("Different groups for transaction inputs")
      }
    }
  }

}

object TxUtils {
  final case class InputData(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      amount: U256,                             // How much ALPH must be payed by this address
      tokens: Option[AVector[(TokenId, U256)]], // How much tokens must be payed by this address
      gasOpt: Option[GasBox],
      utxos: Option[AVector[AssetOutputRef]]
  )

  final case class AssetOutputInfoWithGas(
      assets: AVector[(AssetOutputRef, AssetOutput)],
      gas: GasBox
  )

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
      totalAmount <- checkTotalAttoAlphAmount(utxos.map(_.amount))
      totalAmountPerToken <- UnsignedTransaction.calculateTotalAmountPerToken(
        utxos.flatMap(_.tokens)
      )
      extraNumOfOutputs =
        (totalAmountPerToken.length + maxTokenPerAssetUtxo - 1) / maxTokenPerAssetUtxo
      gas = gasOpt.getOrElse(GasEstimation.sweepAddress(utxos.length, extraNumOfOutputs + 1))
      totalAmountWithoutGas <- totalAmount
        .sub(gasPrice * gas)
        .toRight("Not enough balance for gas fee in Sweeping")
      amountRequiredForExtraOutputs <- dustUtxoAmount
        .mul(U256.unsafe(extraNumOfOutputs))
        .toRight("Too many tokens")
      amountOfFirstOutput <- totalAmountWithoutGas
        .sub(amountRequiredForExtraOutputs)
        .toRight("Not enough ALPH balance for transaction outputs")
      _ <- amountOfFirstOutput
        .sub(dustUtxoAmount)
        .toRight("Not enough ALPH balance for transaction outputs")
    } yield {
      val firstOutput =
        TxOutputInfo(toLockupScript, amountOfFirstOutput, AVector.empty, lockTimeOpt)
      val restOfTokens = totalAmountPerToken.grouped(maxTokenPerAssetUtxo)
      val restOfOutputs = restOfTokens.map { tokens =>
        TxOutputInfo(
          toLockupScript,
          dustUtxoAmount,
          tokens,
          lockTimeOpt
        )
      }

      (firstOutput +: restOfOutputs, gas)
    }
  }

  class TokenBalances(balances: mutable.Map[TokenId, U256]) {
    def addToken(tokenId: TokenId, amount: U256): Option[Unit] = {
      balances.get(tokenId) match {
        case Some(currentAmount) =>
          currentAmount.add(amount).map(balances(tokenId) = _)
        case None =>
          balances(tokenId) = amount
          Some(())
      }
    }

    def getBalances(): AVector[(TokenId, U256)] = AVector.from(balances)
  }

  def getBalance(
      outputs: AVector[TxOutput]
  ): (U256, U256, AVector[(TokenId, U256)], AVector[(TokenId, U256)]) = {
    var attoAlphBalance: U256       = U256.Zero
    var attoAlphLockedBalance: U256 = U256.Zero
    val tokenBalances               = new TokenBalances(mutable.Map.empty)
    val tokenLockedBalances         = new TokenBalances(mutable.Map.empty)
    val currentTs                   = TimeStamp.now()

    outputs.foreach { output =>
      attoAlphBalance.add(output.amount).foreach(attoAlphBalance = _)
      output.tokens.foreach { case (tokenId, amount) =>
        tokenBalances.addToken(tokenId, amount)
      }

      val isLocked = output match {
        case o: AssetOutput    => o.lockTime > currentTs
        case _: ContractOutput => false
      }
      if (isLocked) {
        attoAlphLockedBalance.add(output.amount).foreach(attoAlphLockedBalance = _)
        output.tokens.foreach { case (tokenId, amount) =>
          tokenLockedBalances.addToken(tokenId, amount)
        }
      }
    }

    (
      attoAlphBalance,
      attoAlphLockedBalance,
      tokenBalances.getBalances(),
      tokenLockedBalances.getBalances()
    )
  }

  private[core] def checkTotalAttoAlphAmount(
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
