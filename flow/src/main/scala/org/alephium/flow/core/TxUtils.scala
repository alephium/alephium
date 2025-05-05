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
import scala.reflect.ClassTag

import TxUtils._
import akka.util.ByteString

import org.alephium.flow.core.BlockFlowState.{BlockCache, Confirmed, MemPooled, TxStatus}
import org.alephium.flow.core.FlowUtils._
import org.alephium.flow.core.UtxoSelectionAlgo.{AssetAmounts, ProvidedGas}
import org.alephium.flow.gasestimation._
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.{ALPH, PublicKey}
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.model._
import org.alephium.protocol.model.UnsignedTransaction.{
  TotalAmountNeeded,
  TxOutputInfo,
  UnlockScriptWithAssets
}
import org.alephium.protocol.vm._
import org.alephium.util.{AVector, EitherF, TimeStamp, U256}

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
      outputs <- groupView.getRelevantUtxos(
        lockupScript,
        maxUtxosToRead,
        errorIfExceedMaxUtxos = false
      )
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
      totalAmount: TotalAmountNeeded,
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      utxosLimit: Int,
      extraUtxosInfo: ExtraUtxosInfo
  ): IOResult[Either[String, UtxoSelectionAlgo.Selected]] = {
    getUsableUtxos(targetBlockHashOpt, fromLockupScript, utxosLimit)
      .map { utxos =>
        selectUtxos(
          fromLockupScript,
          fromUnlockScript,
          extraUtxosInfo.merge(utxos),
          totalAmount,
          None,
          None,
          gasOpt,
          gasPrice
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
      .flatMap(_.getPreAssetOutputInfos(utxoRefs))
      .map { utxosOpt =>
        val outputScripts = fromLockupScript +: outputInfos.map(_.lockupScript)
        for {
          gas <- gasOpt match {
            case None =>
              for {
                estimatedGas <- GasEstimation.estimateWithInputScript(
                  (fromLockupScript, fromUnlockScript),
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
  ): IOResult[Balance] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))

    getUTXOs(lockupScript, utxosLimit, getMempoolUtxos).map { utxos =>
      TxUtils.getBalance(utxos.map(_.output))
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
          _.getRelevantUtxos(ls, utxosLimit, errorIfExceedMaxUtxos = true).map(_.as[OutputInfo])
        )
      case ls: LockupScript.P2C =>
        getBestPersistedWorldState(groupIndex).flatMap(
          _.getContractOutputInfo(ls.contractId).map { case (ref, output) =>
            AVector(ContractOutputInfo(ref, output))
          }
        )
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def transfer(
      fromPublicKey: PublicKey,
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp],
      amount: U256,
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      utxoLimit: Int,
      extraUtxosInfo: ExtraUtxosInfo = ExtraUtxosInfo.empty
  ): IOResult[Either[String, UnsignedTransaction]] = {
    transfer(
      fromPublicKey,
      AVector(TxOutputInfo(toLockupScript, amount, AVector.empty, lockTimeOpt)),
      gasOpt,
      gasPrice,
      utxoLimit,
      extraUtxosInfo
    )
  }

  def transfer(
      fromPublicKey: PublicKey,
      outputInfos: AVector[TxOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      utxoLimit: Int,
      extraUtxosInfo: ExtraUtxosInfo
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
      utxoLimit,
      extraUtxosInfo
    )
  }

  // scalastyle:off parameter.number method.length
  def polwCoinbase(
      chainIndex: ChainIndex,
      fromPublicKey: PublicKey,
      minerLockupScript: LockupScript.Asset,
      uncles: AVector[SelectedGhostUncle],
      reward: Emission.PoLW,
      gasFee: U256,
      blockTs: TimeStamp,
      minerData: ByteString
  )(implicit networkConfig: NetworkConfig): IOResult[Either[String, UnsignedTransaction]] = {
    val rewardOutputs = Coinbase.calcPoLWCoinbaseRewardOutputs(
      chainIndex,
      minerLockupScript,
      uncles,
      reward,
      gasFee,
      blockTs,
      minerData
    )
    val totalAmount = TotalAmountNeeded(
      reward.burntAmount.addUnsafe(dustUtxoAmount),
      AVector.empty,
      rewardOutputs.length + 1
    )
    val fromLockupScript = LockupScript.p2pkh(fromPublicKey)
    val fromUnlockScript = UnlockScript.polw(fromPublicKey)
    getUsableUtxos(None, fromLockupScript, Int.MaxValue)
      .map { utxos =>
        selectUtxos(
          fromLockupScript,
          fromUnlockScript,
          utxos,
          totalAmount,
          gasEstimationMultiplier = None,
          txScriptOpt = None,
          gasOpt = None,
          gasPrice = coinbaseGasPrice
        )
      }
      .map(
        _.flatMap(selected =>
          polwCoinbase(
            fromLockupScript,
            fromUnlockScript,
            rewardOutputs,
            reward.burntAmount,
            selected.assets,
            selected.gas
          )
        )
      )
  }
  // scalastyle:on parameter.number

  private[flow] def polwCoinbase(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript.PoLW,
      rewardOutputs: AVector[AssetOutput],
      burntAmount: U256,
      utxos: AVector[AssetOutputInfo],
      gas: GasBox
  ): Either[String, UnsignedTransaction] = {
    assume(gas >= minimalGas)
    for {
      _ <- utxos.foreachE { utxo =>
        if (utxo.output.tokens.nonEmpty) {
          Left("Tokens are not allowed for PoLW input")
        } else {
          Right(())
        }
      }
      _ <- checkEstimatedGasAmount(gas)
      inputSum <- EitherF.foldTry(utxos, U256.Zero) { case (acc, asset) =>
        acc.add(asset.output.amount).toRight("Input amount overflow")
      }
      gasUsed = if (gas > minimalGas) gas.subUnsafe(minimalGas) else GasBox.unsafe(0)
      gasFee  = coinbaseGasPrice * gasUsed
      changeAmount <- inputSum
        .sub(burntAmount)
        .flatMap(_.sub(gasFee))
        .toRight("Change amount underflow")
    } yield {
      val changeOutput = AssetOutput(
        changeAmount,
        fromLockupScript,
        TimeStamp.zero,
        AVector.empty,
        ByteString.empty
      )
      UnsignedTransaction(
        None,
        gas,
        coinbaseGasPrice,
        utxos.map(output => TxInput(output.ref, fromUnlockScript)),
        rewardOutputs :+ changeOutput
      )
    }
  }

  private def preTransferCheck(
      fromLockupScript: LockupScript.Asset,
      outputInfos: AVector[TxOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): Either[String, Unit] = {
    for {
      _ <- checkOutputInfos(fromLockupScript.groupIndex, outputInfos)
      _ <- checkProvidedGas(gasOpt, gasPrice)
      _ <- checkTotalAttoAlphAmount(outputInfos.map(_.attoAlphAmount))
    } yield ()
  }

  def checkAndCalcTotalAmountNeeded(
      fromLockupScript: LockupScript.Asset,
      outputInfos: AVector[TxOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): Either[String, TotalAmountNeeded] = {
    for {
      _               <- preTransferCheck(fromLockupScript, outputInfos, gasOpt, gasPrice)
      calculateResult <- UnsignedTransaction.calculateTotalAmountNeeded(outputInfos)
    } yield calculateResult
  }

  def selectUtxos(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      utxos: AVector[FlowUtils.AssetOutputInfo],
      totalAmount: TotalAmountNeeded,
      gasEstimationMultiplier: Option[GasEstimationMultiplier],
      txScriptOpt: Option[StatefulScript],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): Either[String, UtxoSelectionAlgo.Selected] = {
    UtxoSelectionAlgo
      .Build(ProvidedGas(gasOpt, gasPrice, gasEstimationMultiplier))
      .select(
        AssetAmounts(totalAmount.alphAmount, totalAmount.tokens),
        fromLockupScript,
        fromUnlockScript,
        utxos,
        totalAmount.outputLength,
        txScriptOpt,
        AssetScriptGasEstimator.Default(Self.blockFlow),
        TxScriptEmulator.Default(Self.blockFlow)
      )
  }

  def transfer(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      outputInfos: AVector[TxOutputInfo],
      totalAmount: TotalAmountNeeded,
      utxos: AVector[FlowUtils.AssetOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): Either[String, UnsignedTransaction] = {
    for {
      selected <- selectUtxos(
        fromLockupScript,
        fromUnlockScript,
        utxos,
        totalAmount,
        None,
        None,
        gasOpt,
        gasPrice
      )
      _ <- checkEstimatedGasAmount(selected.gas)
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

  // scalastyle:off method.length
  def transfer(
      targetBlockHashOpt: Option[BlockHash],
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      outputInfos: AVector[TxOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      utxosLimit: Int,
      extraUtxosInfo: ExtraUtxosInfo
  ): IOResult[Either[String, UnsignedTransaction]] = {
    val totalAmountsE =
      checkAndCalcTotalAmountNeeded(fromLockupScript, outputInfos, gasOpt, gasPrice)
    totalAmountsE match {
      case Right(totalAmount) =>
        getUsableUtxos(targetBlockHashOpt, fromLockupScript, utxosLimit).map { utxos =>
          transfer(
            fromLockupScript,
            fromUnlockScript,
            outputInfos,
            totalAmount,
            extraUtxosInfo.merge(utxos),
            gasOpt,
            gasPrice
          )
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
        _ <- preTransferCheck(fromLockupScript, outputInfos, gasOpt, gasPrice)
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
            gasOpt // can i have an extra map here?
          ).map(_.flatMap { assetsWithGas =>
            UnsignedTransaction
              .buildTransferTx(
                fromLockupScript,
                fromUnlockScript,
                assetsWithGas.assets.map(outputInfo => (outputInfo.ref, outputInfo.output)),
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

  def buildTransferFromOneToManyGroups(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      targetBlockHash: Option[BlockHash],
      outputRefs: AVector[AssetOutputRef],
      outputInfos: AVector[TxOutputInfo],
      gasPrice: GasPrice,
      defaultUtxosLimit: Int
  )(implicit
      networkConfig: NetworkConfig
  ): Either[String, AVector[UnsignedTransaction]] = {
    for {
      inputSelection <- blockFlow
        .getSelectedUtxoOrArbitrary(
          targetBlockHash,
          fromLockupScript,
          outputRefs,
          defaultUtxosLimit
        )
      _ <- blockFlow
        .sanityCheckBalance(
          fromLockupScript,
          fromUnlockScript,
          inputSelection,
          outputInfos,
          gasPrice
        )
      outputGroups <- TxUtils
        .weightLimitedGroupBy(outputInfos, groups, ALPH.MaxTxOutputNum - 1)( // - change utxo
          _.lockupScript.groupIndex.value,
          _.tokens.length + 1
        )
      txs <- buildTransferFromOneToManyGroups(
        fromLockupScript,
        fromUnlockScript,
        inputSelection,
        outputGroups,
        gasPrice,
        AVector.empty
      )
    } yield txs
  }

  @tailrec
  final def buildTransferFromOneToManyGroups(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      inputs: AVector[AssetOutputInfo],
      outputGroups: AVector[AVector[TxOutputInfo]],
      gasPrice: GasPrice,
      acc: AVector[UnsignedTransaction]
  )(implicit
      networkConfig: NetworkConfig
  ): Either[String, AVector[UnsignedTransaction]] = {
    if (outputGroups.isEmpty) {
      if (acc.isEmpty) {
        Left("Outputs cannot be empty")
      } else {
        Right(acc)
      }
    } else if (inputs.isEmpty) {
      Left("Not enough inputs to build transfer-from-one-to-many-groups")
    } else {
      val currentOutputs = outputGroups.head
      val selectedResult = for {
        _                       <- checkOutputInfos(fromLockupScript.groupIndex, currentOutputs)
        _                       <- checkTotalAttoAlphAmount(currentOutputs.map(_.attoAlphAmount))
        amountTokensOutputCount <- UnsignedTransaction.calculateTotalAmountNeeded(currentOutputs)
        selected <- selectUtxos(
          fromLockupScript,
          fromUnlockScript,
          inputs,
          amountTokensOutputCount,
          None,
          None,
          None,
          gasPrice
        )
      } yield selected

      selectedResult match {
        case Left(error) => Left(error)
        case Right(selected) =>
          if (selected.gas > getMaximalGasPerTx()) {
            if (currentOutputs.length <= 1) {
              val inputsCount = selected.assets.length
              Left(
                s"Unable to build transfer-from-one-to-many-groups from $inputsCount inputs due to exceeding gas per tx"
              )
            } else {
              val (firstOutputs, secondOutputs) = currentOutputs.splitAt(currentOutputs.length / 2)
              val smallerOutputGroups           = firstOutputs +: secondOutputs +: outputGroups.tail
              buildTransferFromOneToManyGroups(
                fromLockupScript,
                fromUnlockScript,
                inputs,
                smallerOutputGroups,
                gasPrice,
                acc
              )
            }
          } else {
            UnsignedTransaction
              .buildTransferTxAndReturnChange(
                fromLockupScript,
                fromUnlockScript,
                selected.assets.map(a => a.ref -> a.output),
                currentOutputs,
                selected.gas,
                gasPrice
              ) match {
              case Left(error) => Left(error)
              case Right((tx, change)) =>
                val changeInputs = change.map { case (oRef, output) =>
                  AssetOutputInfo(oRef, output, MemPoolOutput)
                }
                val remainingInputs =
                  inputs.filterNot(out => selected.assets.exists(_.ref == out.ref))
                buildTransferFromOneToManyGroups(
                  fromLockupScript,
                  fromUnlockScript,
                  remainingInputs ++ changeInputs,
                  outputGroups.tail,
                  gasPrice,
                  acc :+ tx
                )
            }
          }
      }
    }
  }

  def sanityCheckBalance(
      fromLockupScript: LockupScript,
      fromUnlockScript: UnlockScript,
      inputs: AVector[AssetOutputInfo],
      outputs: AVector[TxOutputInfo],
      gasPrice: GasPrice
  ): Either[String, Unit] =
    getAssetRemainders(fromLockupScript, fromUnlockScript, inputs, outputs, gasPrice).map(_ => ())

  def getAssetRemainders(
      fromLockupScript: LockupScript,
      fromUnlockScript: UnlockScript,
      inputs: AVector[AssetOutputInfo],
      outputs: AVector[TxOutputInfo],
      gasPrice: GasPrice
  )(implicit networkConfig: NetworkConfig): Either[String, (U256, AVector[(TokenId, U256)])] = {
    if (inputs.isEmpty || outputs.isEmpty) {
      Left("Both inputs and outputs must be specified")
    } else {
      for {
        gasBox <- GasEstimation.estimateWithInputScript(
          (fromLockupScript, fromUnlockScript),
          inputs.length,
          countResultingTxOutputs(outputs),
          AssetScriptGasEstimator.Default(blockFlow)
        )
        alphRemainder <- UnsignedTransaction.calculateAlphRemainder(
          inputs.map(_.output.amount),
          outputs.map(_.attoAlphAmount),
          gasPrice * gasBox
        )
        tokenRemainder <- UnsignedTransaction.calculateTokensRemainder(
          inputs.flatMap(_.output.tokens),
          outputs.flatMap(_.tokens)
        )
      } yield (alphRemainder, tokenRemainder)
    }
  }

  def getSelectedUtxoOrArbitrary(
      targetBlockHashOpt: Option[BlockHash],
      lockupScript: LockupScript.Asset,
      inputSelection: AVector[AssetOutputRef],
      utxosLimit: Int
  ): Either[String, AVector[AssetOutputInfo]] =
    if (inputSelection.isEmpty) {
      getUsableUtxos(
        targetBlockHashOpt,
        lockupScript,
        maxUtxosToRead = utxosLimit
      ).left.map(_.getMessage)
    } else {
      for {
        _ <- checkUTXOsInSameGroup(inputSelection)
        groupView <- getImmutableGroupViewIncludePool(
          lockupScript.groupIndex,
          targetBlockHashOpt
        ).left.map(_.getMessage)
        utxoOpts <- inputSelection.mapE(groupView.getPreAssetOutputInfo).left.map(_.getMessage)
        existingUtxos    = utxoOpts.collect(identity)
        existingUtxoRefs = existingUtxos.map(_.ref)
        _ <- Either.cond(
          inputSelection.length == existingUtxos.length,
          (),
          s"Selected input UTXOs are not available: " +
            s"${inputSelection.filterNot(existingUtxoRefs.contains).map(_.key.value.toHexString).mkString(", ")}"
        )
      } yield existingUtxos
    }

  def transferMultiInputs(
      inputs: AVector[InputData],
      outputInfos: AVector[TxOutputInfo],
      gasPrice: GasPrice,
      utxosLimit: Int,
      targetBlockHashOpt: Option[BlockHash]
  ): IOResult[Either[String, UnsignedTransaction]] = {
    val dustAmountsE = for {
      groupIndex  <- checkMultiInputsGroup(inputs)
      _           <- checkMultiInputsGas(inputs)
      _           <- checkOutputInfos(groupIndex, outputInfos)
      nbOfTokens  <- checkInOutAmounts(inputs, outputInfos)
      dustAmounts <- calculateDustAmountNeeded(outputInfos)
    } yield (dustAmounts, nbOfTokens)

    dustAmountsE match {
      case Right(((dustAmountNeeded, txOutputLength), nbOfTokens)) =>
        selectMultiInputsUtxos(
          inputs,
          outputInfos,
          dustAmountNeeded,
          targetBlockHashOpt,
          gasPrice,
          utxosLimit,
          txOutputLength,
          nbOfTokens
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
      targetBlockHashOpt: Option[BlockHash],
      gasPrice: GasPrice,
      utxosLimit: Int,
      txOutputLength: Int
  ): IOResult[Either[String, AssetOutputInfoWithGas]] = {
    input.utxos match {
      case None =>
        val amountWithDust = input.amount.add(dustAmountPerInputNeeded).getOrElse(input.amount)
        selectUTXOs(
          targetBlockHashOpt,
          input.fromLockupScript,
          input.fromUnlockScript,
          TotalAmountNeeded(amountWithDust, input.tokens.getOrElse(AVector.empty), txOutputLength),
          input.gasOpt,
          gasPrice,
          utxosLimit,
          ExtraUtxosInfo.empty
        ).map(_.map { selected =>
          AssetOutputInfoWithGas(selected.assets, selected.gas)
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
      targetBlockHashOpt: Option[BlockHash],
      gasPrice: GasPrice,
      utxosLimit: Int,
      txOutputLength: Int,
      nbOfTokens: Int
  ): IOResult[Either[String, AVector[(InputData, AssetOutputInfoWithGas)]]] = {
    /*
     * If one `gasOpt` is defined, then every input is following its `gasOpt` value
     * If `gasOpt` is empty, we let the `UtxoSelectionAlgo` choose enough utxos so everyone can
     * pay the base fee and a simple tx, but later we reduce the fees.
     */
    inputs
      .mapE { input =>
        selectInputDataUtxos(
          input,
          outputInfos,
          dustAmountPerInputNeeded,
          targetBlockHashOpt,
          gasPrice,
          utxosLimit,
          txOutputLength
        ).map(_.map(utxosWithGas => (input, utxosWithGas)))
      }
      .map(_.mapE(identity))
      .map(_.map { selecteds =>
        // Reduce if posible the overall gas for every input
        updateSelectedGas(selecteds, outputInfos.length + nbOfTokens)
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
              utxosWithGas.assets.map(outputInfo => outputInfo.ref -> outputInfo.output)
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
      inputs: AVector[(InputData, AssetOutputInfoWithGas)],
      destinationOutputLengths: Int
  ): AVector[(InputData, AssetOutputInfoWithGas)] = {
    // If some input have explicit gas, we don't update
    if (inputs.length > 0 && !inputs.exists(_._1.gasOpt.isDefined)) {
      val gasPerInput = inputs.map { case (input, selected) =>
        /*
         We only consider 1 change output here.
         In the cases where there are 0 change output we will over-estimate
         */
        val changeTokenOutputs = UnsignedTransaction
          .calculateTokensRemainder(
            selected.assets.flatMap(_.output.tokens),
            input.tokens.getOrElse(AVector.empty)
          )
          .map(_.length)
          .getOrElse(0)

        val changeOutputLength = 1 + changeTokenOutputs
        val outGas             = GasSchedule.txOutputBaseGas.mulUnsafe(changeOutputLength)
        val inGas              = GasEstimation.gasForSameP2PKHInputs(selected.assets.length)
        val inOutGas           = inGas.addUnsafe(outGas)

        (input, selected.copy(gas = inOutGas), selected.gas)
      }

      // Computing base fee and splitting it between all inputs
      val destinationsGas   = GasSchedule.txOutputBaseGas.mulUnsafe(destinationOutputLengths)
      val baseFee           = GasSchedule.txBaseGas.value + destinationsGas.value
      val baseFeeShared     = GasBox.unsafe(baseFee / inputs.length)
      val baseFeeSharedRest = GasBox.unsafe(baseFee % inputs.length)

      gasPerInput.mapWithIndex { case ((input, selected, initialGas), i) =>
        val newGas = selected.gas.addUnsafe(baseFeeShared)
        // We don't want to update to a higher gas
        val payedGas = if (newGas < initialGas) newGas else initialGas

        // If we had only 1 input, we need to make sure we don't update
        // below minimal gas, as its the only one paying.
        if (inputs.length == 1) {
          val oneInputGas = if (payedGas < minimalGas) minimalGas else payedGas
          (input, selected.copy(gas = oneInputGas))
        } else if (i == 0) {
          // First input is paying for the rest that cannot be divided by everyone
          (input, selected.copy(gas = payedGas.addUnsafe(baseFeeSharedRest)))
        } else {
          (input, selected.copy(gas = payedGas))
        }
      }
    } else {
      inputs
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
      selected.assets.map(_.output.amount),
      AVector(input.amount),
      gasPrice * selected.gas
    )
  }

  private def calculateTokensRemainder(
      input: InputData,
      selected: AssetOutputInfoWithGas
  ): Either[String, AVector[(TokenId, U256)]] = {
    UnsignedTransaction.calculateTokensRemainder(
      selected.assets.flatMap(_.output.tokens),
      input.tokens.getOrElse(AVector.empty)
    )
  }

  // Each inputs will need to add some dust amount when selecting UTXOs to cover the complicated cases
  def calculateDustAmountNeeded(
      outputInfos: AVector[TxOutputInfo]
  ): Either[String, (U256, Int)] = {
    for {
      totalAmount     <- checkTotalAttoAlphAmount(outputInfos.map(_.attoAlphAmount))
      calculateResult <- UnsignedTransaction.calculateTotalAmountNeeded(outputInfos)
      dustAmount      <- calculateResult.alphAmount.sub(totalAmount).toRight("ALPH underflow")
    } yield {
      // `calculateTotalAmountNeeded` is adding a +1 length for the sender's output
      (dustAmount, calculateResult.outputLength)
    }
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

  @inline private def isConsolidation(
      fromLockupScript: LockupScript.Asset,
      toLockupScript: LockupScript.Asset
  ): Boolean = {
    fromLockupScript == toLockupScript
  }

  private[core] def buildSweepTokenTxs(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp],
      allTokenUtxos: AVector[AssetOutputInfo],
      allAlphUtxos: AVector[AssetOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): (AVector[UnsignedTransaction], AVector[AssetOutputInfo], Option[String]) = {
    assume(allTokenUtxos.forall(_.output.tokens.length == 1))

    val utxosPerToken = allTokenUtxos.groupBy(_.output.tokens.head._1).view
    val filteredTokenUtxos =
      if (isConsolidation(fromLockupScript, toLockupScript) && lockTimeOpt.isEmpty) {
        utxosPerToken.filter(_._2.length > 1)
      } else {
        utxosPerToken
      }

    // group based on token id, so that utxos with the same token can
    // be included in a single transaction as much as possible
    val groupedTokenUtxos = AVector
      .from(filteredTokenUtxos.flatMap(_._2))
      .groupedWithRemainder(ALPH.MaxTxInputNum / 2)
    val unsignedTxs = mutable.ArrayBuffer.empty[UnsignedTransaction]
    var alphUtxos   = allAlphUtxos
    val error = groupedTokenUtxos
      .foreachE { tokenUtxos =>
        tryBuildSweepTokenTx(
          fromLockupScript,
          fromUnlockScript,
          toLockupScript,
          lockTimeOpt,
          tokenUtxos,
          alphUtxos.sorted(UtxoSelectionAlgo.AssetAscendingOrder.byAlph),
          gasOpt,
          gasPrice
        ) match {
          case Right((unsignedTx, restAlphUtxos)) =>
            unsignedTxs.addOne(unsignedTx)
            alphUtxos = restAlphUtxos
            Right(())
          case Left(error) =>
            logger.info(
              s"Build sweep tx with ascending order returns error: $error, try descending order instead"
            )

            tryBuildSweepTokenTx(
              fromLockupScript,
              fromUnlockScript,
              toLockupScript,
              lockTimeOpt,
              tokenUtxos,
              alphUtxos.sorted(UtxoSelectionAlgo.AssetDescendingOrder.byAlph),
              gasOpt,
              gasPrice
            ).map { case (unsignedTx, restAlphUtxos) =>
              unsignedTxs.addOne(unsignedTx)
              alphUtxos = restAlphUtxos
            }
        }
      }
      .left
      .toOption
    (AVector.from(unsignedTxs), alphUtxos, error)
  }

  @inline private def buildTokenOutputs(
      tokens: AVector[(TokenId, U256)],
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp]
  ): AVector[TxOutputInfo] = {
    tokens.map { case (tokenId, amount) =>
      TxOutputInfo(toLockupScript, dustUtxoAmount, AVector((tokenId, amount)), lockTimeOpt)
    }
  }

  private[core] def tryBuildSweepTokenTx(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp],
      tokenUtxos: AVector[AssetOutputInfo],
      alphUtxos: AVector[AssetOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): Either[String, (UnsignedTransaction, AVector[AssetOutputInfo])] = {
    for {
      totalAlphAmount <- checkTotalAttoAlphAmount(tokenUtxos.map(_.output.amount))
      totalAmountPerToken <- UnsignedTransaction.calculateTotalAmountPerToken(
        tokenUtxos.flatMap(_.output.tokens)
      )
      totalNumOfOutputs  = totalAmountPerToken.length + 1 // 1 for ALPH change output
      requiredAlphAmount = dustUtxoAmount.mulUnsafe(U256.unsafe(totalNumOfOutputs))
      selected <- UtxoSelectionAlgo
        .SelectionWithGasEstimation(gasPrice)
        .select(
          fromLockupScript,
          fromUnlockScript,
          totalNumOfOutputs,
          UtxoSelectionAlgo.SelectedSoFar(totalAlphAmount, tokenUtxos, alphUtxos),
          requiredAlphAmount,
          AssetScriptGasEstimator.Default(blockFlow)
        )
        .left
        .map(_ => "Not enough ALPH for gas fee in sweeping")
      (selectedSoFor, estimatedGas) = selected
      gas <- checkEstimatedGas(gasOpt, estimatedGas)
      tokenDustAmount    = requiredAlphAmount.subUnsafe(dustUtxoAmount)
      changeOutputAmount = selectedSoFor.alph.subUnsafe(gasPrice * gas).subUnsafe(tokenDustAmount)
      changeOutput = TxOutputInfo(toLockupScript, changeOutputAmount, AVector.empty, lockTimeOpt)
      unsignedTx <- UnsignedTransaction.buildTransferTx(
        fromLockupScript,
        fromUnlockScript,
        (tokenUtxos ++ selectedSoFor.selected).map(asset => (asset.ref, asset.output)),
        buildTokenOutputs(totalAmountPerToken, toLockupScript, lockTimeOpt) :+ changeOutput,
        gas,
        gasPrice
      )
    } yield (unsignedTx, selectedSoFor.rest)
  }

  private def checkEstimatedGas(
      gasOpt: Option[GasBox],
      estimatedGas: GasBox
  ): Either[String, GasBox] = {
    if (gasOpt.exists(_ < estimatedGas)) {
      Left(s"The specified gas amount is not enough, estimated gas: ${estimatedGas.value}")
    } else {
      Right(estimatedGas)
    }
  }

  private[core] def tryBuildSweepAlphTx(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp],
      alphUtxos: AVector[AssetOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): Either[String, UnsignedTransaction] = {
    for {
      estimatedGas <- GasEstimation.estimateWithInputScript(
        (fromLockupScript, fromUnlockScript),
        alphUtxos.length,
        1,
        AssetScriptGasEstimator.Default(blockFlow)
      )
      gas             <- checkEstimatedGas(gasOpt, estimatedGas)
      totalAlphAmount <- checkTotalAttoAlphAmount(alphUtxos.map(_.output.amount))
      outputAmount <- totalAlphAmount
        .sub(gasPrice * gas)
        .toRight(s"Not enough ALPH for transaction output in sweeping")
      unsignedTx <- UnsignedTransaction.buildTransferTx(
        fromLockupScript,
        fromUnlockScript,
        alphUtxos.map(asset => (asset.ref, asset.output)),
        AVector(TxOutputInfo(toLockupScript, outputAmount, AVector.empty, lockTimeOpt)),
        gas,
        gasPrice
      )
    } yield unsignedTx
  }

  private[core] def buildSweepAlphTxs(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp],
      allAlphUtxos: AVector[AssetOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): (AVector[UnsignedTransaction], Option[String]) = {
    assume(allAlphUtxos.forall(_.output.tokens.isEmpty))

    val sortedAlphUtxos  = allAlphUtxos.sorted(UtxoSelectionAlgo.AssetAscendingOrder.byAlph)
    val groupedAlphUtxos = sortedAlphUtxos.groupedWithRemainder(ALPH.MaxTxInputNum)
    val unsignedTxs      = mutable.ArrayBuffer.empty[UnsignedTransaction]
    val consolidation    = isConsolidation(fromLockupScript, toLockupScript)
    val error = groupedAlphUtxos
      .foreachE { alphUtxos =>
        if (consolidation && alphUtxos.length == 1 && lockTimeOpt.isEmpty) {
          Right(())
        } else {
          tryBuildSweepAlphTx(
            fromLockupScript,
            fromUnlockScript,
            toLockupScript,
            lockTimeOpt,
            alphUtxos,
            gasOpt,
            gasPrice
          ).map(unsignedTx => unsignedTxs.addOne(unsignedTx))
        }
      }
      .left
      .toOption
    (AVector.from(unsignedTxs), error)
  }

  // scalastyle:off parameter.number
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
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
          val allUtxos = maxAttoAlphPerUTXOOpt match {
            case Some(maxAttoAlphPerUTXO) =>
              allUtxosUnfiltered.filter(_.output.amount < maxAttoAlphPerUTXO)
            case None => allUtxosUnfiltered
          }
          val (allAlphUtxos, allTokenUtxos) = allUtxos.partition(_.output.tokens.isEmpty)
          val (sweepTokenTxs, restAlphUtxos, error0) = buildSweepTokenTxs(
            fromLockupScript,
            fromUnlockScript,
            toLockupScript,
            lockTimeOpt,
            allTokenUtxos,
            allAlphUtxos,
            gasOpt,
            gasPrice
          )
          val (sweepAlphTxs, error1) = buildSweepAlphTxs(
            fromLockupScript,
            fromUnlockScript,
            toLockupScript,
            lockTimeOpt,
            restAlphUtxos,
            gasOpt,
            gasPrice
          )
          val txs   = sweepTokenTxs ++ sweepAlphTxs
          val error = error0.orElse(error1)
          if (error.nonEmpty && txs.isEmpty) Left(error.get) else Right(txs)
        }

      case Left(e) => Right(Left(e))
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
        fromChain.maxHeightByWeightUnsafe - firstConfirmationHeight + 1
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
      toChain.maxHeightByWeightUnsafe - ALPH.GenesisHeight + 1
    } else {
      iter(toTipHeight + 1) match {
        case None => 0
        case Some(firstConfirmationHeight) =>
          toChain.maxHeightByWeightUnsafe - firstConfirmationHeight + 1
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
      failedTxs <- txs.filterE(tx =>
        groupView.getPreAssetOutputs(tx.unsigned.inputs).map(_.isEmpty)
      )
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

  private def checkProvidedGas(gasOpt: Option[GasBox], gasPrice: GasPrice): Either[String, Unit] = {
    for {
      _ <- checkProvidedGasAmount(gasOpt)
      _ <- checkGasPrice(gasPrice)
    } yield ()
  }

  private[core] def checkProvidedGasAmount(gasOpt: Option[GasBox]): Either[String, Unit] = {
    gasOpt match {
      case None => Right(())
      case Some(gas) =>
        val maximalGasPerTx = getMaximalGasPerTx()
        if (gas < minimalGas) {
          Left(s"Provided gas $gas too small, minimal $minimalGas")
        } else if (gas > maximalGasPerTx) {
          Left(s"Provided gas $gas too large, maximal $maximalGasPerTx")
        } else {
          Right(())
        }
    }
  }

  private[core] def checkEstimatedGasAmount(gas: GasBox): Either[String, Unit] = {
    val maximalGasPerTx = getMaximalGasPerTx()
    if (gas > maximalGasPerTx) {
      Left(
        s"Estimated gas $gas too large, maximal $maximalGasPerTx. " ++
          "Consider consolidating UTXOs using the sweep endpoints or " ++
          "sending to less addresses"
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
      val utxos        = inputs.flatMap(_.utxos.getOrElse(AVector.empty))

      if (groupIndexes.forall(_ == groupIndex)) {
        utxos.headOption match {
          case Some(utxo) =>
            if (utxo.hint.groupIndex == groupIndex) {
              checkUTXOsInSameGroup(utxos).map(_ => groupIndex)
            } else {
              Left("Selected UTXOs different from lockup script group")
            }
          case None => Right(groupIndex)
        }
      } else {
        Left("Different groups for transaction inputs")
      }
    }
  }

  /*
   * Either every input defined gas or none
   */
  private def checkMultiInputsGas(
      inputs: AVector[InputData]
  ): Either[String, Unit] = {
    if (inputs.isEmpty) {
      Left("Zero transaction inputs")
    } else {
      val definedGas = inputs.filter(_.gasOpt.isDefined).length
      if (definedGas == 0 || definedGas == inputs.length) {
        Right(())
      } else {
        Left("Missing `gasAmount` in some inputs")
      }
    }
  }

  private def checkInOutAmounts(
      inputs: AVector[InputData],
      outputInfos: AVector[TxOutputInfo]
  ): Either[String, Int] = {
    for {
      in  <- checkTotalAttoAlphAmount(inputs.map(_.amount))
      out <- checkTotalAttoAlphAmount(outputInfos.map(_.attoAlphAmount))
      _   <- Either.cond(in == out, (), "Total input amount doesn't match total output amount")
      inToken <- UnsignedTransaction.calculateTotalAmountPerToken(
        inputs.flatMap(_.tokens.getOrElse(AVector.empty))
      )
      outToken <- UnsignedTransaction.calculateTotalAmountPerToken(outputInfos.flatMap(_.tokens))
      _ <- Either.cond(
        inToken.toSet == outToken.toSet,
        (),
        "Total token input amount doesn't match total output amount"
      )
    } yield outToken.length
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
      assets: AVector[AssetOutputInfo],
      gas: GasBox
  )

  def countResultingTxOutputs(outputs: AVector[TxOutputInfo]): Int = {
    if (outputs.isEmpty) {
      0
    } else {
      // we include Change Utxo at the beginning
      outputs.fold(1) { case (outputsCount, output) =>
        // each token will become dedicated utxo regardless of TokenId
        outputsCount + 1 + output.tokens.length
      }
    }
  }

  def isSpent(blockCaches: AVector[BlockCache], outputRef: TxOutputRef): Boolean = {
    blockCaches.exists(_.inputs.contains(outputRef))
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

  def getBalance(outputs: AVector[TxOutput]): Balance = {
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

    Balance(
      attoAlphBalance,
      attoAlphLockedBalance,
      tokenBalances.getBalances(),
      tokenLockedBalances.getBalances(),
      outputs.length
    )
  }

  def checkTotalAttoAlphAmount(
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

  def weightLimitedGroupBy[E: ClassTag](
      elems: AVector[E],
      groupCount: Int,
      weightLimit: Int
  )(groupFn: E => Int, weightFn: E => Int): Either[String, AVector[AVector[E]]] = {
    assume(groupCount > 0, "groupCount must be positive")
    assume(weightLimit > 0, "weightLimit must be positive")

    val outputGroups        = Array.fill(groupCount)(mutable.ArrayBuffer[mutable.ArrayBuffer[E]]())
    val currentGroupWeights = Array.fill(groupCount)(0)

    elems.collectFirst { elem =>
      val groupIndex = groupFn(elem)
      val elemWeight = weightFn(elem)

      if (groupIndex >= groupCount || groupIndex < 0) {
        Some(s"Unexpected group index $groupIndex for element $elem")
      } else if (elemWeight <= 0) {
        Some(s"Element weight $elemWeight was not positive for element $elem")
      } else {
        val groupWeight = currentGroupWeights(groupIndex)
        val groupList   = outputGroups(groupIndex)

        if (groupList.isEmpty || groupWeight + elemWeight > weightLimit) {
          groupList.append(mutable.ArrayBuffer(elem))
          currentGroupWeights(groupIndex) = elemWeight
        } else {
          groupList(groupList.length - 1).append(elem)
          currentGroupWeights(groupIndex) += elemWeight
        }
        None
      }
    } match {
      case Some(errorMessage) =>
        Left(errorMessage)
      case None =>
        Right(AVector.from(outputGroups.iterator.flatMap(_.iterator.map(AVector.from))))
    }
  }
}
