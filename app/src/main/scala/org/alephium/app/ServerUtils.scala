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

package org.alephium.app

import java.math.BigInteger

import scala.concurrent._

import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging

import org.alephium.api._
import org.alephium.api.ApiError
import org.alephium.api.model
import org.alephium.api.model.{AssetOutput => _, Transaction => _, TransactionTemplate => _, _}
import org.alephium.crypto.Byte32
import org.alephium.flow.core.{BlockFlow, BlockFlowState, ExtraUtxosInfo, UtxoSelectionAlgo}
import org.alephium.flow.core.FlowUtils.{AssetOutputInfo, MemPoolOutput}
import org.alephium.flow.core.TxUtils
import org.alephium.flow.core.TxUtils.InputData
import org.alephium.flow.core.UtxoSelectionAlgo._
import org.alephium.flow.gasestimation._
import org.alephium.flow.handler.TxHandler
import org.alephium.flow.mempool.MemPool._
import org.alephium.io.IOError
import org.alephium.protocol.{vm, ALPH, Hash, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.config._
import org.alephium.protocol.model.{ContractOutput => ProtocolContractOutput, _}
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.{failed => _, BlockHash => _, ContractState => _, Val => _, _}
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.protocol.vm.nodeindexes.{TxIdTxOutputLocators, TxOutputLocator}
import org.alephium.ralph.{CompiledContract, Compiler, Testing}
import org.alephium.serde.{avectorSerde, deserialize, serialize}
import org.alephium.util._

// scalastyle:off number.of.methods
// scalastyle:off file.size.limit number.of.types
class ServerUtils(implicit
    brokerConfig: BrokerConfig,
    consensusConfigs: ConsensusConfigs,
    networkConfig: NetworkConfig,
    apiConfig: ApiConfig,
    logConfig: LogConfig,
    executionContext: ExecutionContext
) extends StrictLogging {
  import ServerUtils._

  def getHeightedBlocks(
      blockFlow: BlockFlow,
      timeInterval: TimeInterval
  ): Try[AVector[(ChainIndex, AVector[(Block, Int)])]] = {
    for {
      _      <- timeInterval.validateTimeSpan(apiConfig.blockflowFetchMaxAge)
      blocks <- wrapResult(blockFlow.getHeightedBlocks(timeInterval.from, timeInterval.to))
    } yield blocks
  }

  def getBlocks(blockFlow: BlockFlow, timeInterval: TimeInterval): Try[BlocksPerTimeStampRange] = {
    getHeightedBlocks(blockFlow, timeInterval).flatMap { heightedBlocks =>
      heightedBlocks
        .mapE(_._2.mapE { case (block, height) =>
          BlockEntry.from(block, height).left.map(failed)
        })
        .map(BlocksPerTimeStampRange.apply)
    }
  }

  def getBlocksAndEvents(
      blockFlow: BlockFlow,
      timeInterval: TimeInterval
  ): Try[BlocksAndEventsPerTimeStampRange] = {
    getHeightedBlocks(blockFlow, timeInterval).flatMap { heightedBlocks =>
      heightedBlocks
        .mapE(_._2.mapE { case (block, height) =>
          for {
            blockEntry <- BlockEntry.from(block, height).left.map(failed)
            events     <- getEventsByBlockHash(blockFlow, blockEntry.hash)
          } yield {
            BlockAndEvents(blockEntry, events.events)
          }

        })
        .map(BlocksAndEventsPerTimeStampRange.apply)
    }
  }

  def getRichBlocksAndEvents(
      blockFlow: BlockFlow,
      timeInterval: TimeInterval
  ): Try[RichBlocksAndEventsPerTimeStampRange] = {
    getHeightedBlocks(blockFlow, timeInterval).flatMap { heightedBlocks =>
      heightedBlocks
        .mapE(_._2.mapE { case (block, height) =>
          for {
            transactions <- block.transactions.mapE(tx =>
              getRichTransaction(blockFlow, tx, block.hash)
            )
            blockEntry <- RichBlockEntry.from(block, height, transactions).left.map(failed)
            events     <- getEventsByBlockHash(blockFlow, blockEntry.hash)
          } yield {
            RichBlockAndEvents(blockEntry, events.events)
          }

        })
        .map(RichBlocksAndEventsPerTimeStampRange.apply)
    }
  }

  def averageHashRate(blockFlow: BlockFlow, timeInterval: TimeInterval)(implicit
      groupConfig: GroupConfig
  ): Try[HashRateResponse] = {
    getHeightedBlocks(blockFlow, timeInterval).map { blocks =>
      val hashCount = blocks.fold(BigInt(0)) { case (acc, (_, entries)) =>
        entries.fold(acc) { case (hashCount, entry) =>
          val target   = entry._1.target
          val hashDone = Target.maxBigInt.divide(target.value)
          hashCount + hashDone
        }
      }
      val hashrate =
        (hashCount * 1000 * groupConfig.chainNum) / timeInterval.durationUnsafe().millis
      HashRateResponse(s"${hashrate / 1000000} MH/s")
    }
  }

  def getCurrentDifficulty(
      blockFlow: BlockFlow
  ): Try[BigInteger] = {
    wrapResult(blockFlow.getDifficultyMetric().map(_.value))
  }

  private def tooManyUtxos[T](error: IOError): Try[T] = {
    error match {
      case IOError.MaxNodeReadLimitExceeded =>
        val message =
          "Your address has too many UTXOs and exceeds the API limit. Please consolidate your UTXOs, or run your own full node with a higher API limit."
        Left(ApiError.InternalServerError(message))
      case error => failed(error)
    }
  }

  def getBalance(blockFlow: BlockFlow, address: Address, getMempoolUtxos: Boolean): Try[Balance] = {
    val utxosLimit = apiConfig.defaultUtxosLimit
    for {
      _ <- checkGroup(address.lockupScript)
      balance <- blockFlow
        .getBalance(
          address.lockupScript,
          utxosLimit,
          getMempoolUtxos
        )
        .map(Balance.from)
        .left
        .flatMap(tooManyUtxos)
    } yield balance
  }

  def getUTXOsIncludePool(blockFlow: BlockFlow, address: Address): Try[UTXOs] = {
    val utxosLimit = apiConfig.defaultUtxosLimit
    for {
      _ <- checkGroup(address.lockupScript)
      utxos <- blockFlow
        .getUTXOs(address.lockupScript, utxosLimit, getMempoolUtxos = true)
        .map(_.map(outputInfo => UTXO.from(outputInfo.ref, outputInfo.output)))
        .left
        .flatMap(tooManyUtxos)
    } yield UTXOs.from(utxos)
  }

  def getContractGroup(
      blockFlow: BlockFlow,
      contractId: ContractId,
      groupIndex: GroupIndex
  ): Try[Group] = {
    val searchResult = for {
      worldState <- blockFlow.getBestPersistedWorldState(groupIndex)
      existed    <- worldState.contractState.exists(contractId)
    } yield existed

    searchResult match {
      case Right(true)  => Right(Group(groupIndex.value))
      case Right(false) => Left(failed("Group not found. Please check another broker"))
      case Left(error)  => Left(failedInIO(error))
    }
  }

  def getGroup(blockFlow: BlockFlow, query: GetGroup): Try[Group] = query match {
    case GetGroup(assetAddress: Address.Asset) =>
      Right(Group(assetAddress.groupIndex(brokerConfig).value))
    case GetGroup(Address.Contract(LockupScript.P2C(contractId))) =>
      getGroupForContract(blockFlow, contractId)
  }

  def getGroupForContract(blockFlow: BlockFlow, contractId: ContractId): Try[Group] = {
    blockFlow
      .getGroupForContract(contractId)
      .map { groupIndex =>
        Group(groupIndex.value)
      }
      .left
      .map(failed)
  }

  def listMempoolTransactions(
      blockFlow: BlockFlow
  ): Try[AVector[MempoolTransactions]] = {
    val result = brokerConfig.groupRange.foldLeft(
      AVector.ofCapacity[MempoolTransactions](brokerConfig.chainNum)
    ) { case (acc, group) =>
      val groupIndex       = GroupIndex.unsafe(group)
      val txsWithTimestamp = blockFlow.getMemPool(groupIndex).getAllWithTimestamp()
      val groupedTxsWithTimestamp =
        txsWithTimestamp.filter(_._1.chainIndex.from == groupIndex).groupBy(_._1.chainIndex)
      acc ++ AVector.from(
        groupedTxsWithTimestamp.map { case (chainIndex, txsWithTimestamp) =>
          MempoolTransactions(
            chainIndex.from.value,
            chainIndex.to.value,
            txsWithTimestamp.map(model.TransactionTemplate.fromProtocol.tupled)
          )
        }
      )
    }
    Right(result)
  }

  def buildTransferFromOneToManyGroups(
      blockFlow: BlockFlow,
      transferRequest: BuildTransferTx
  ): Try[AVector[BuildTransferTxResult]] =
    for {
      _ <- Either.cond(
        transferRequest.gasAmount.isEmpty,
        (),
        badRequest(
          "Explicit gas amount is not permitted, transfer-from-one-to-many-groups requires gas estimation."
        )
      )
      assetOutputRefs <- transferRequest.utxos match {
        case Some(outputRefs) => prepareOutputRefs(outputRefs).left.map(badRequest)
        case None             => Right(AVector.empty[AssetOutputRef])
      }
      lockPair <- transferRequest.getLockPair()
      _ <- Either.cond(
        brokerConfig.contains(lockPair._1.groupIndex),
        (),
        badRequest(s"This node cannot serve request for Group ${lockPair._1.groupIndex}")
      )
      outputInfos = prepareOutputInfos(transferRequest.destinations)
      gasPrice    = transferRequest.gasPrice.getOrElse(nonCoinbaseMinGasPrice)
      unsignedTxs <- blockFlow
        .buildTransferFromOneToManyGroups(
          lockPair._1,
          lockPair._2,
          transferRequest.targetBlockHash,
          assetOutputRefs,
          outputInfos,
          gasPrice,
          apiConfig.defaultUtxosLimit
        )
        .left
        .map(failed)
      txs <- unsignedTxs.mapE(validateUnsignedTransaction)
    } yield txs.map(BuildTransferTxResult.from)

  def buildTransferUnsignedTransaction(
      blockFlow: BlockFlow,
      query: BuildTransferTx,
      extraUtxosInfo: ExtraUtxosInfo
  ): Try[UnsignedTransaction] = {
    for {
      lockPair <- query.getLockPair()
      unsignedTx <- prepareUnsignedTransaction(
        blockFlow,
        lockPair._1,
        lockPair._2,
        query.utxos,
        query.destinations,
        query.gasAmount,
        query.gasPrice.getOrElse(nonCoinbaseMinGasPrice),
        query.targetBlockHash,
        extraUtxosInfo
      )
    } yield unsignedTx
  }

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def buildTransferTransaction(
      blockFlow: BlockFlow,
      query: BuildTransferTx,
      extraUtxosInfo: ExtraUtxosInfo = ExtraUtxosInfo.empty
  ): Try[BuildTransferTxResult] = {
    for {
      unsignedTx <- buildTransferUnsignedTransaction(blockFlow, query, extraUtxosInfo)
    } yield BuildTransferTxResult.from(unsignedTx)
  }

  def buildMultiInputsTransaction(
      blockFlow: BlockFlow,
      query: BuildMultiAddressesTransaction
  ): Try[BuildTransferTxResult] = {
    for {
      unsignedTx <- prepareMultiInputsUnsignedTransactionFromQuery(
        blockFlow,
        query
      )
    } yield {
      BuildTransferTxResult.from(unsignedTx)
    }
  }
  def buildMultisig(
      blockFlow: BlockFlow,
      query: BuildMultisig
  ): Try[BuildTransferTxResult] = {
    for {
      _ <- checkGroup(query.fromAddress.lockupScript)
      unlockScript <- buildMultisigUnlockScript(
        query.fromAddress.lockupScript,
        query.fromPublicKeys
      )
      unsignedTx <- prepareUnsignedTransaction(
        blockFlow,
        query.fromAddress.lockupScript,
        unlockScript,
        query.destinations,
        query.gas,
        query.gasPrice.getOrElse(nonCoinbaseMinGasPrice),
        None,
        ExtraUtxosInfo.empty
      )
    } yield {
      BuildTransferTxResult.from(unsignedTx)
    }
  }

  def buildSweepMultisig(
      blockFlow: BlockFlow,
      query: BuildSweepMultisig
  ): Try[BuildSweepAddressTransactionsResult] = {
    val lockupScript = query.fromAddress.lockupScript
    for {
      _            <- checkGroup(lockupScript)
      unlockScript <- buildMultisigUnlockScript(lockupScript, query.fromPublicKeys)
      unsignedTxs <- prepareSweepAddressTransactionFromScripts(
        blockFlow,
        lockupScript,
        unlockScript,
        query.toAddress,
        query.maxAttoAlphPerUTXO,
        query.lockTime,
        query.gasAmount,
        query.gasPrice.getOrElse(nonCoinbaseMinGasPrice),
        query.targetBlockHash,
        query.utxosLimit
      )
    } yield {
      BuildSweepAddressTransactionsResult.from(
        unsignedTxs,
        lockupScript.groupIndex,
        query.toAddress.groupIndex
      )
    }
  }

  private def buildMultisigUnlockScript(
      lockupScript: LockupScript,
      pubKeys: AVector[PublicKey]
  ): Try[UnlockScript.P2MPKH] = {
    lockupScript match {
      case LockupScript.P2MPKH(pkHashes, m) =>
        if (m == pubKeys.length) {
          val indexes = pkHashes.zipWithIndex
          pubKeys
            .mapE { pub =>
              val pubHash = Hash.hash(pub.bytes)
              indexes.find { case (hash, _) => hash == pubHash } match {
                case Some((_, index)) => Right((pub, index))
                case None => Left(ApiError.BadRequest(s"Invalid public key: ${pub.toHexString}"))

              }
            }
            .map(UnlockScript.P2MPKH(_))
        } else {
          Left(
            ApiError.BadRequest(s"Invalid public key number. Expected ${m}, got ${pubKeys.length}")
          )
        }
      case _ =>
        Left(ApiError.BadRequest(s"Invalid lockup script"))
    }
  }

  def buildSweepAddressTransactions(
      blockFlow: BlockFlow,
      query: BuildSweepAddressTransactions
  ): Try[BuildSweepAddressTransactionsResult] = {
    val lockupScript = LockupScript.p2pkh(query.fromPublicKey)
    for {
      _ <- checkGroup(lockupScript)
      unsignedTxs <- prepareSweepAddressTransaction(
        blockFlow,
        query.fromPublicKey,
        query.toAddress,
        query.maxAttoAlphPerUTXO,
        query.lockTime,
        query.gasAmount,
        query.gasPrice.getOrElse(nonCoinbaseMinGasPrice),
        query.targetBlockHash,
        query.utxosLimit
      )
    } yield {
      BuildSweepAddressTransactionsResult.from(
        unsignedTxs,
        lockupScript.groupIndex,
        query.toAddress.groupIndex
      )
    }
  }

  def submitTransaction(txHandler: ActorRefT[TxHandler.Command], tx: TransactionTemplate)(implicit
      askTimeout: Timeout
  ): FutureTry[SubmitTxResult] = {
    publishTx(txHandler, tx)
  }

  def createTxTemplate(query: SubmitTransaction): Try[TransactionTemplate] = {
    for {
      unsignedTx <- decodeUnsignedTransaction(query.unsignedTx)
      _          <- validateUnsignedTransaction(unsignedTx)
    } yield {
      templateWithSignatures(
        unsignedTx,
        AVector(query.signature)
      )
    }
  }

  def createMultisigTxTemplate(query: SubmitMultisig): Try[TransactionTemplate] = {
    for {
      unsignedTx <- decodeUnsignedTransaction(query.unsignedTx)
      _          <- validateUnsignedTransaction(unsignedTx)
    } yield {
      templateWithSignatures(
        unsignedTx,
        query.signatures
      )
    }
  }

  private def templateWithSignatures(
      unsignedTx: UnsignedTransaction,
      signatures: AVector[Signature]
  ): TransactionTemplate = {
    TransactionTemplate(
      unsignedTx,
      signatures,
      scriptSignatures = AVector.empty
    )
  }

  def convert(statusOpt: Option[BlockFlowState.TxStatus]): TxStatus = {
    statusOpt match {
      case Some(confirmed: BlockFlowState.Confirmed) =>
        Confirmed(
          confirmed.index.hash,
          confirmed.index.index,
          confirmed.chainConfirmations,
          confirmed.fromGroupConfirmations,
          confirmed.toGroupConfirmations
        )
      case Some(BlockFlowState.MemPooled) =>
        MemPooled()
      case None =>
        TxNotFound()
    }
  }

  def getTransactionStatus(
      blockFlow: BlockFlow,
      txId: TransactionId,
      chainIndex: ChainIndex
  ): Try[TxStatus] = {
    blockFlow.getTransactionStatus(txId, chainIndex).left.map(failed).map(convert)
  }

  def decodeUnsignedTransaction(
      unsignedTx: String
  ): Try[UnsignedTransaction] = {
    for {
      txByteString <- Hex.from(unsignedTx).toRight(badRequest("Invalid hex"))
      unsignedTx <- deserialize[UnsignedTransaction](txByteString).left
        .map(serdeError => badRequest(serdeError.getMessage))
      _ <- validateUnsignedTransaction(unsignedTx)
    } yield unsignedTx
  }

  def decodeUnlockScript(
      unlockScript: String
  ): Try[UnlockScript] = {
    Hex.from(unlockScript).toRight(badRequest("Invalid hex")).flatMap { unlockScriptBytes =>
      deserialize[UnlockScript](unlockScriptBytes).left
        .map(serdeError => badRequest(serdeError.getMessage))
    }
  }

  def getEventsForContractCurrentCount(
      blockFlow: BlockFlow,
      contractAddress: Address.Contract
  ): Try[Int] = {
    val contractId = contractAddress.lockupScript.contractId
    for {
      countOpt <- wrapResult(blockFlow.getEventsCurrentCount(contractId))
      count    <- countOpt.toRight(notFound(s"Current events count for contract $contractAddress"))
    } yield count
  }

  private def handleBlockError(blockHash: BlockHash, error: IOError) = {
    error match {
      case _: IOError.KeyNotFound =>
        failed(
          s"The block ${blockHash.toHexString} does not exist, please check if your full node synced"
        )
      case other =>
        failed(s"Fail fetching block with hash ${blockHash.toHexString}, error: $other")
    }
  }

  def getBlock(blockFlow: BlockFlow, hash: BlockHash): Try[BlockEntry] =
    for {
      _ <- checkHashChainIndex(hash)
      block <- blockFlow
        .getBlock(hash)
        .left
        .map(handleBlockError(hash, _))
      height <- blockFlow
        .getHeight(block.header)
        .left
        .map(failedInIO)
      blockEntry <- BlockEntry.from(block, height).left.map(failed)
    } yield blockEntry

  def getRichBlockAndEvents(blockFlow: BlockFlow, hash: BlockHash): Try[RichBlockAndEvents] =
    for {
      _ <- checkHashChainIndex(hash)
      block <- blockFlow
        .getBlock(hash)
        .left
        .map(handleBlockError(hash, _))
      height <- blockFlow
        .getHeight(block.header)
        .left
        .map(failedInIO)
      transactions <- block.transactions.mapE(tx => getRichTransaction(blockFlow, tx, hash))
      blockEntry   <- RichBlockEntry.from(block, height, transactions).left.map(failed)
      contractEventsByBlockHash <- getEventsByBlockHash(blockFlow, hash)
    } yield RichBlockAndEvents(blockEntry, contractEventsByBlockHash.events)

  private[app] def getRichTransaction(
      blockFlow: BlockFlow,
      transaction: Transaction,
      spentBlockHash: BlockHash
  ): Try[RichTransaction] = {
    for {
      assetInputs    <- getRichAssetInputs(blockFlow, transaction, spentBlockHash)
      contractInputs <- getRichContractInputs(blockFlow, transaction, spentBlockHash)
    } yield {
      RichTransaction.from(transaction, assetInputs, contractInputs)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private[app] def getRichContractInputs(
      blockFlow: BlockFlow,
      transaction: Transaction,
      spentBlockHash: BlockHash
  ): Try[AVector[RichContractInput]] = {
    transaction.contractInputs.mapE { contractOutputRef =>
      for {
        txOutputOpt <- wrapResult(blockFlow.getTxOutput(contractOutputRef, spentBlockHash))
        richInput <- txOutputOpt match {
          case Some(txOutput) =>
            Right(RichInput.from(contractOutputRef, txOutput.asInstanceOf[ProtocolContractOutput]))
          case None =>
            Left(notFound(s"Transaction output for contract output reference ${contractOutputRef}"))
        }
      } yield richInput
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private[app] def getRichAssetInputs(
      blockFlow: BlockFlow,
      transaction: Transaction,
      spentBlockHash: BlockHash
  ): Try[AVector[RichAssetInput]] = {
    transaction.unsigned.inputs.mapE { assetInput =>
      for {
        txOutputOpt <- wrapResult(blockFlow.getTxOutput(assetInput.outputRef, spentBlockHash))
        richInput <- txOutputOpt match {
          case Some(txOutput) =>
            Right(RichInput.from(assetInput, txOutput.asInstanceOf[AssetOutput]))
          case None =>
            Left(notFound(s"Transaction output for asset output reference ${assetInput.outputRef}"))
        }
      } yield richInput
    }
  }

  def getMainChainBlockByGhostUncle(
      blockFlow: BlockFlow,
      ghostUncleHash: BlockHash
  ): Try[BlockEntry] =
    for {
      chainIndex <- checkHashChainIndex(ghostUncleHash)
      result <- blockFlow
        .getMainChainBlockByGhostUncle(chainIndex, ghostUncleHash)
        .left
        .map(handleBlockError(ghostUncleHash, _))
      blockEntry <- result match {
        case None =>
          isBlockInMainChain(blockFlow, ghostUncleHash).flatMap { isMainChainBlock =>
            if (isMainChainBlock) {
              val message =
                s"The block ${ghostUncleHash.toHexString} is not a ghost uncle block, you should use a ghost uncle block hash to call this endpoint"
              Left(failed(message))
            } else {
              val resource =
                s"The mainchain block that references the ghost uncle block ${ghostUncleHash.toHexString}"
              Left(notFound(resource))
            }
          }
        case Some((block, height)) => BlockEntry.from(block, height).left.map(failed)
      }
    } yield blockEntry

  def getBlockAndEvents(blockFlow: BlockFlow, hash: BlockHash): Try[BlockAndEvents] =
    for {
      block  <- getBlock(blockFlow, hash)
      events <- getEventsByBlockHash(blockFlow, hash)
    } yield BlockAndEvents(block, events.events)

  def isBlockInMainChain(blockFlow: BlockFlow, blockHash: BlockHash): Try[Boolean] = {
    blockFlow.isBlockInMainChain(blockHash).left.map(handleBlockError(blockHash, _))
  }

  def getBlockHeader(blockFlow: BlockFlow, hash: BlockHash): Try[BlockHeaderEntry] =
    for {
      blockHeader <- blockFlow
        .getBlockHeader(hash)
        .left
        .map(handleBlockError(hash, _))
      height <- blockFlow
        .getHeight(hash)
        .left
        .map(failedInIO)
    } yield BlockHeaderEntry.from(blockHeader, height)

  def getRawBlock(blockFlow: BlockFlow, hash: BlockHash): Try[RawBlock] =
    for {
      _ <- checkHashChainIndex(hash)
      blockBytes <- blockFlow
        .getBlockBytes(hash)
        .left
        .map(handleBlockError(hash, _))
    } yield RawBlock(blockBytes)

  def getHashesAtHeight(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      query: GetHashesAtHeight
  ): Try[HashesAtHeight] =
    for {
      hashes <- blockFlow
        .getHashes(chainIndex, query.height)
        .left
        .map(failedInIO)
    } yield HashesAtHeight(hashes)

  def getChainInfo(blockFlow: BlockFlow, chainIndex: ChainIndex): Try[ChainInfo] =
    for {
      maxHeight <- blockFlow
        .getMaxHeightByWeight(chainIndex)
        .left
        .map(failedInIO)
    } yield ChainInfo(maxHeight)

  def searchLocalTransactionStatus(
      blockFlow: BlockFlow,
      txId: TransactionId,
      chainIndexes: AVector[ChainIndex]
  ): Try[TxStatus] = {
    blockFlow.searchLocalTransactionStatus(txId, chainIndexes).left.map(failed).map(convert)
  }

  def getTransaction(
      blockFlow: BlockFlow,
      txId: TransactionId,
      fromGroup: Option[GroupIndex],
      toGroup: Option[GroupIndex]
  ): Try[model.Transaction] = {
    getTransactionAndConvert(
      blockFlow,
      txId,
      fromGroup,
      toGroup,
      tx => model.Transaction.fromProtocol(tx)
    )
  }

  def getRichTransaction(
      blockFlow: BlockFlow,
      txId: TransactionId,
      fromGroup: Option[GroupIndex],
      toGroup: Option[GroupIndex]
  ): Try[model.RichTransaction] = {
    for {
      blockHash       <- getBlockHashForTransaction(blockFlow, txId)
      transaction     <- getTransactionAndConvert(blockFlow, txId, fromGroup, toGroup, identity)
      richTransaction <- getRichTransaction(blockFlow, transaction, blockHash)
    } yield richTransaction
  }

  def getBlockHashForTransaction(blockFlow: BlockFlow, txId: TransactionId): Try[BlockHash] = {
    val outputRef = TxOutputRef.key(txId, 0)
    for {
      locatorsOpt <- wrapResult(blockFlow.getTxIdTxOutputLocatorsFromOutputRef(outputRef))
      locators <- locatorsOpt.toRight(
        notFound(s"Transaction id for output ref ${outputRef.value.toHexString}")
      )
      mainchainBlockHash <- getMainChainBlockHashFromOutputLocators(blockFlow, locators)
    } yield mainchainBlockHash
  }

  def getMainChainBlockHashFromOutputLocators(
      blockFlow: BlockFlow,
      locators: TxIdTxOutputLocators
  ): Try[BlockHash] = {
    for {
      locatorOpt <- locators.txOutputLocators.findE(locator =>
        isBlockInMainChain(blockFlow, locator.blockHash)
      )
      locator <- locatorOpt.toRight(
        notFound(s"Main chain block hash for ${locators.txId}")
      )
    } yield locator.blockHash
  }

  def getRawTransaction(
      blockFlow: BlockFlow,
      txId: TransactionId,
      fromGroup: Option[GroupIndex],
      toGroup: Option[GroupIndex]
  ): Try[model.RawTransaction] = {
    getTransactionAndConvert(
      blockFlow,
      txId,
      fromGroup,
      toGroup,
      tx => RawTransaction(serialize(tx))
    )
  }

  def getTransactionAndConvert[T](
      blockFlow: BlockFlow,
      txId: TransactionId,
      fromGroup: Option[GroupIndex],
      toGroup: Option[GroupIndex],
      convert: Transaction => T
  ): Try[T] = {
    val result = (fromGroup, toGroup) match {
      case (Some(from), Some(to)) =>
        blockFlow.getTransaction(txId, ChainIndex(from, to)).left.map(failed)
      case _ =>
        val chainIndexes = brokerConfig.chainIndexes.filter { chainIndex =>
          fromGroup.forall(_ == chainIndex.from) && toGroup.forall(_ == chainIndex.to)
        }
        blockFlow.searchTransaction(txId, chainIndexes).left.map(failed)
    }

    result.flatMap {
      case Some(tx) => Right(convert(tx))
      case None     => Left(notFound(s"Transaction ${txId.toHexString}"))
    }
  }

  def getEventsByTxId(
      blockFlow: BlockFlow,
      txId: TransactionId
  ): Try[ContractEventsByTxId] = {
    wrapResult(
      blockFlow.getEventsByHash(Byte32.unsafe(txId.bytes)).map { logs =>
        val events = logs.map(p => ContractEventByTxId.from(p._1, p._2, p._3))
        ContractEventsByTxId(events)
      }
    )
  }

  def getEventsByBlockHash(
      blockFlow: BlockFlow,
      blockHash: BlockHash
  ): Try[ContractEventsByBlockHash] = {
    wrapResult(
      blockFlow.getEventsByHash(Byte32.unsafe(blockHash.bytes)).map { logs =>
        val events = logs.map(p => ContractEventByBlockHash.from(p._2, p._3))
        ContractEventsByBlockHash(events)
      }
    )
  }

  def getEventsByContractAddress(
      blockFlow: BlockFlow,
      start: Int,
      limit: Int,
      contractAddress: Address.Contract
  ): Try[ContractEvents] = {
    wrapResult(blockFlow.getEvents(contractAddress.lockupScript.contractId, start, start + limit))
      .flatMap {
        case (nextStart, logStatesVec) => {
          if (logStatesVec.isEmpty) {
            wrapResult(blockFlow.getEventsCurrentCount(contractAddress.contractId)).flatMap {
              case None =>
                Left(notFound(s"Contract events of ${contractAddress}"))
              case Some(currentCount) if currentCount == start =>
                Right(ContractEvents.from(AVector.empty, nextStart))
              case Some(currentCount) =>
                Left(
                  notFound(
                    s"Current count for events of ${contractAddress} is '$currentCount', events start from '$start' with limit '$limit'"
                  )
                )
            }
          } else {
            Right(ContractEvents.from(logStatesVec, nextStart))
          }
        }
      }
  }

  private def publishTx(txHandler: ActorRefT[TxHandler.Command], tx: TransactionTemplate)(implicit
      askTimeout: Timeout
  ): FutureTry[SubmitTxResult] = {
    val message =
      TxHandler.AddToMemPool(AVector(tx), isIntraCliqueSyncing = false, isLocalTx = true)
    txHandler.ask(message).mapTo[TxHandler.SubmitToMemPoolResult].map {
      case TxHandler.ProcessedByMemPool(_, AddedToMemPool) =>
        Right(SubmitTxResult(tx.id, tx.fromGroup.value, tx.toGroup.value))
      case TxHandler.ProcessedByMemPool(_, AlreadyExisted) =>
        // succeed for idempotency reasons due to clients retrying submission
        Right(SubmitTxResult(tx.id, tx.fromGroup.value, tx.toGroup.value))
      case failedResult =>
        Left(failed(failedResult.message))
    }
  }

  private[app] def mergeAndprepareOutputInfos(
      destinations: AVector[Destination]
  ): Either[String, AVector[TxOutputInfo]] = {
    AVector.from(destinations.groupBy(_.address)).flatMapE { case (address, dests) =>
      val simpleDests = dests.filter(dest => dest.lockTime.isEmpty && dest.message.isEmpty)
      val otherDests =
        prepareOutputInfos(dests.filter(dest => dest.lockTime.isDefined || dest.message.isDefined))

      if (simpleDests.nonEmpty) {
        for {
          amount <- TxUtils.checkTotalAttoAlphAmount(simpleDests.map(_.getAttoAlphAmount().value))
          tokens <- UnsignedTransaction
            .calculateTotalAmountPerToken(
              simpleDests.flatMap(
                _.tokens.map(_.map(t => (t.id, t.amount))).getOrElse(AVector.empty)
              )
            )
        } yield {
          TxOutputInfo(address.lockupScript, amount, tokens, None, None) +: otherDests
        }
      } else {
        Right(otherDests)
      }
    }
  }

  private def prepareOutputInfos(destinations: AVector[Destination]): AVector[TxOutputInfo] = {
    destinations.map { destination =>
      val tokensInfo = destination.tokens match {
        case Some(tokens) =>
          tokens.map { token =>
            token.id -> token.amount
          }
        case None =>
          AVector.empty[(TokenId, U256)]
      }

      val tokensDustAmount = dustUtxoAmount.mulUnsafe(U256.unsafe(tokensInfo.length))

      TxOutputInfo(
        destination.address.lockupScript,
        Math.max(destination.getAttoAlphAmount().value, tokensDustAmount),
        tokensInfo,
        destination.lockTime,
        destination.message
      )
    }
  }

  // scalastyle:off method.length
  def prepareMultiInputsUnsignedTransactionFromQuery(
      blockFlow: BlockFlow,
      query: BuildMultiAddressesTransaction
  ): Try[UnsignedTransaction] = {

    val transferResult = for {
      outputInfos <- mergeAndprepareOutputInfos(query.from.flatMap(_.destinations)).left.map(failed)
      inputs <- query.from.mapE { in =>
        for {
          lockUnlock <- in.getLockPair()
          utxos      <- prepareOutputRefsOpt(in.utxos).left.map(failed)
          amount <- TxUtils
            .checkTotalAttoAlphAmount(in.destinations.map(_.getAttoAlphAmount().value))
            .left
            .map(failed)
          tokens <- UnsignedTransaction
            .calculateTotalAmountPerToken(
              in.destinations.flatMap(
                _.tokens.map(_.map(t => (t.id, t.amount))).getOrElse(AVector.empty)
              )
            )
            .left
            .map(failed)
        } yield {
          lockUnlock match {
            case (lock, unlock) =>
              InputData(
                lock,
                unlock,
                amount,
                Option.when(tokens.nonEmpty)(tokens),
                in.gasAmount,
                utxos
              )
          }
        }
      }
      _ <- checkUniqueInputs(inputs)
      result <-
        blockFlow
          .transferMultiInputs(
            inputs,
            outputInfos,
            query.gasPrice.getOrElse(nonCoinbaseMinGasPrice),
            apiConfig.defaultUtxosLimit,
            query.targetBlockHash
          )
          .left
          .map(failedInIO)
    } yield {
      result
    }

    transferResult match {
      case Right(Right(unsignedTransaction)) => validateUnsignedTransaction(unsignedTransaction)
      case Right(Left(error))                => Left(failed(error))
      case Left(error)                       => Left(error)
    }
  }

  def prepareUnsignedTransaction(
      blockFlow: BlockFlow,
      fromPublicKey: PublicKey,
      outputRefsOpt: Option[AVector[OutputRef]],
      destinations: AVector[Destination],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      targetBlockHashOpt: Option[BlockHash],
      extraUtxosInfo: ExtraUtxosInfo
  ): Try[UnsignedTransaction] = {
    val fromLockupScript = LockupScript.p2pkh(fromPublicKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromPublicKey)
    prepareUnsignedTransaction(
      blockFlow,
      fromLockupScript,
      fromUnlockScript,
      outputRefsOpt,
      destinations,
      gasOpt,
      gasPrice,
      targetBlockHashOpt,
      extraUtxosInfo
    )
  }

  // scalastyle:off parameter.number
  def prepareUnsignedTransaction(
      blockFlow: BlockFlow,
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      outputRefsOpt: Option[AVector[OutputRef]],
      destinations: AVector[Destination],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      targetBlockHashOpt: Option[BlockHash],
      extraUtxosInfo: ExtraUtxosInfo
  ): Try[UnsignedTransaction] = {
    val outputInfos = prepareOutputInfos(destinations)

    val transferResult = outputRefsOpt match {
      case Some(outputRefs) =>
        prepareOutputRefs(outputRefs) match {
          case Right(assetOutputRefs) =>
            blockFlow.transfer(
              targetBlockHashOpt,
              fromLockupScript,
              fromUnlockScript,
              assetOutputRefs,
              outputInfos,
              gasOpt,
              gasPrice
            )
          case Left(error) =>
            Right(Left(error))
        }
      case None =>
        blockFlow.transfer(
          targetBlockHashOpt,
          fromLockupScript,
          fromUnlockScript,
          outputInfos,
          gasOpt,
          gasPrice,
          apiConfig.defaultUtxosLimit,
          extraUtxosInfo
        )
    }

    transferResult match {
      case Right(Right(unsignedTransaction)) => validateUnsignedTransaction(unsignedTransaction)
      case Right(Left(error))                => Left(failed(error))
      case Left(error)                       => failed(error)
    }
  }
  // scalastyle:on parameter.number

  private def getUtxosLimit(utxosLimit: Option[Int]): Int = {
    utxosLimit match {
      case Some(limit) => math.min(apiConfig.defaultUtxosLimit, limit)
      case None        => apiConfig.defaultUtxosLimit
    }
  }

  // scalastyle:off parameter.number
  def prepareSweepAddressTransaction(
      blockFlow: BlockFlow,
      fromPublicKey: PublicKey,
      toAddress: Address.Asset,
      maxAttoAlphPerUTXO: Option[Amount],
      lockTimeOpt: Option[TimeStamp],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      targetBlockHashOpt: Option[BlockHash],
      utxosLimit: Option[Int]
  ): Try[AVector[UnsignedTransaction]] = {
    blockFlow.sweepAddress(
      targetBlockHashOpt,
      fromPublicKey,
      toAddress.lockupScript,
      lockTimeOpt,
      gasOpt,
      gasPrice,
      maxAttoAlphPerUTXO.map(_.value),
      getUtxosLimit(utxosLimit)
    ) match {
      case Right(Right(unsignedTxs)) => unsignedTxs.mapE(validateUnsignedTransaction)
      case Right(Left(error))        => Left(failed(error))
      case Left(error)               => failed(error)
    }
  }
  // scalastyle:on parameter.number

  // scalastyle:off parameter.number
  def prepareSweepAddressTransactionFromScripts(
      blockFlow: BlockFlow,
      fromLockupScript: LockupScript.Asset,
      fromUnlockupScript: UnlockScript,
      toAddress: Address.Asset,
      maxAttoAlphPerUTXO: Option[Amount],
      lockTimeOpt: Option[TimeStamp],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      targetBlockHashOpt: Option[BlockHash],
      utxosLimit: Option[Int]
  ): Try[AVector[UnsignedTransaction]] = {
    blockFlow.sweepAddressFromScripts(
      targetBlockHashOpt,
      fromLockupScript,
      fromUnlockupScript,
      toAddress.lockupScript,
      lockTimeOpt,
      gasOpt,
      gasPrice,
      maxAttoAlphPerUTXO.map(_.value),
      getUtxosLimit(utxosLimit)
    ) match {
      case Right(Right(unsignedTxs)) => unsignedTxs.mapE(validateUnsignedTransaction)
      case Right(Left(error))        => Left(failed(error))
      case Left(error)               => failed(error)
    }
  }

  def prepareUnsignedTransaction(
      blockFlow: BlockFlow,
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      destinations: AVector[Destination],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      targetBlockHashOpt: Option[BlockHash],
      extraUtxosInfo: ExtraUtxosInfo
  ): Try[UnsignedTransaction] = {
    prepareUnsignedTransaction(
      blockFlow,
      fromLockupScript,
      fromUnlockScript,
      None,
      destinations,
      gasOpt,
      gasPrice,
      targetBlockHashOpt,
      extraUtxosInfo
    )
  }

  def prepareOutputRefsOpt(
      outputRefsOpt: Option[AVector[OutputRef]]
  ): Either[String, Option[AVector[AssetOutputRef]]] = {
    outputRefsOpt match {
      case Some(outputRefs) => prepareOutputRefs(outputRefs).map(Some(_))
      case None             => Right(None)
    }
  }

  def prepareOutputRefs(
      outputRefs: AVector[OutputRef]
  ): Either[String, AVector[AssetOutputRef]] = {
    val allAssetType = outputRefs.forall(outputRef => Hint.unsafe(outputRef.hint).isAssetType)
    if (allAssetType) {
      Right(outputRefs.map(_.unsafeToAssetOutputRef()))
    } else {
      Left("Selected UTXOs must be of asset type")
    }
  }

  def checkGroup(lockupScript: LockupScript): Try[Unit] = {
    checkGroup(
      lockupScript.groupIndex(brokerConfig),
      Some(s"Address ${Address.from(lockupScript)}")
    )
  }

  def checkGroup(publicKey: PublicKey): Try[Unit] = {
    val lockupScript = LockupScript.p2pkh(publicKey)
    checkGroup(lockupScript)
  }

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def checkGroup(groupIndex: GroupIndex, data: Option[String] = None): Try[Unit] = {
    if (brokerConfig.contains(groupIndex)) {
      Right(())
    } else {
      Left(badRequest(s"${data.getOrElse("This node")} belongs to other groups"))
    }
  }

  def checkChainIndex(chainIndex: ChainIndex, data: String): Try[ChainIndex] = {
    if (
      brokerConfig.contains(chainIndex.from) ||
      brokerConfig.contains(chainIndex.to)
    ) {
      Right(chainIndex)
    } else {
      Left(badRequest(s"$data belongs to other groups"))
    }
  }

  def checkHashChainIndex(hash: BlockHash): Try[ChainIndex] = {
    val chainIndex = ChainIndex.from(hash)
    checkChainIndex(chainIndex, hash.toHexString)
  }

  def checkUniqueInputs(
      inputs: AVector[InputData]
  ): Try[Unit] = {
    if (inputs.groupBy(_.fromLockupScript).values.exists(_.length > 1)) {
      Left(badRequest("Some addresses defined multiple time"))
    } else {
      Right(())
    }
  }

  def execute(f: => Unit): FutureTry[Boolean] =
    Future {
      f
      Right(true)
    }

  def buildMultisigAddress(
      keys: AVector[PublicKey],
      mrequired: Int
  ): Either[String, BuildMultisigAddressResult] = {
    LockupScript.p2mpkh(keys, mrequired) match {
      case Some(lockupScript) =>
        Right(
          BuildMultisigAddressResult(
            Address.Asset(lockupScript)
          )
        )
      case None => Left(s"Invalid m-of-n multisig")
    }
  }

  private def unsignedTxFromScript(
      blockFlow: BlockFlow,
      script: StatefulScript,
      amount: U256,
      tokens: AVector[(TokenId, U256)],
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      gas: Option[GasBox],
      gasPrice: Option[GasPrice],
      gasEstimationMultiplier: Option[GasEstimationMultiplier],
      extraUtxosInfo: ExtraUtxosInfo
  ): Try[(UnsignedTransaction, AVector[TxInputWithAsset])] = {
    for {
      selectedUtxos <- buildSelectedUtxos(
        blockFlow,
        script,
        amount,
        tokens,
        fromLockupScript,
        fromUnlockScript,
        gas,
        gasPrice,
        gasEstimationMultiplier,
        extraUtxosInfo
      )
      inputs = selectedUtxos.assets.map(asset => (asset.ref, asset.output))
      unsignedTx <- wrapError {
        UnsignedTransaction.buildScriptTx(
          script,
          fromLockupScript,
          fromUnlockScript,
          inputs,
          amount,
          tokens,
          gas.getOrElse(selectedUtxos.gas),
          gasPrice.getOrElse(nonCoinbaseMinGasPrice)
        )
      }
      validatedUnsignedTx <- validateUnsignedTransaction(unsignedTx)
    } yield (
      validatedUnsignedTx,
      selectedUtxos.assets.map(TxInputWithAsset.from(_, fromUnlockScript))
    )
  }

  final def buildSelectedUtxos(
      blockFlow: BlockFlow,
      script: StatefulScript,
      amount: U256,
      tokens: AVector[(TokenId, U256)],
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      gas: Option[GasBox],
      gasPrice: Option[GasPrice],
      gasEstimationMultiplier: Option[GasEstimationMultiplier],
      extraUtxosInfo: ExtraUtxosInfo
  ): Try[Selected] = {
    val result = tryBuildSelectedUtxos(
      blockFlow,
      script,
      amount,
      tokens,
      fromLockupScript,
      fromUnlockScript,
      gas,
      gasPrice,
      gasEstimationMultiplier,
      extraUtxosInfo
    )
    result match {
      case Right(res) =>
        val alphAmount = res.assets.fold(U256.Zero)(_ addUnsafe _.output.amount)
        val gasFee     = gasPrice.getOrElse(nonCoinbaseMinGasPrice) * res.gas

        val remainingAmount = alphAmount.subUnsafe(gasFee).subUnsafe(amount)
        if (remainingAmount < dustUtxoAmount) {
          tryBuildSelectedUtxos(
            blockFlow,
            script,
            amount.addUnsafe(dustUtxoAmount),
            tokens,
            fromLockupScript,
            fromUnlockScript,
            Some(res.gas),
            gasPrice,
            None,
            extraUtxosInfo
          )
        } else {
          Right(res)
        }
      case err @ _ => err
    }
  }

  private def tryBuildSelectedUtxos(
      blockFlow: BlockFlow,
      script: StatefulScript,
      amount: U256,
      tokens: AVector[(TokenId, U256)],
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      gas: Option[GasBox],
      gasPrice: Option[GasPrice],
      gasEstimationMultiplier: Option[GasEstimationMultiplier],
      extraUtxosInfo: ExtraUtxosInfo
  ): Try[Selected] = {
    val utxosLimit               = apiConfig.defaultUtxosLimit
    val estimatedTxOutputsLength = tokens.length + 1
    // Allocate extra dust amounts for potential fixed outputs as well as generated outputs
    val estimatedTotalDustAmount =
      dustUtxoAmount.mulUnsafe(U256.unsafe(estimatedTxOutputsLength * 2))

    for {
      utxos <- blockFlow.getUsableUtxos(fromLockupScript, utxosLimit).left.map(failedInIO)
      totalSelectAmount <- amount
        .add(estimatedTotalDustAmount)
        .toRight(failed("ALPH amount overflow"))
      selectedUtxos <- wrapError(
        UtxoSelectionAlgo
          .Build(
            ProvidedGas(gas, gasPrice.getOrElse(nonCoinbaseMinGasPrice), gasEstimationMultiplier)
          )
          .select(
            AssetAmounts(totalSelectAmount, tokens),
            fromUnlockScript,
            extraUtxosInfo.merge(utxos),
            txOutputsLength = estimatedTxOutputsLength,
            Some(script),
            AssetScriptGasEstimator.Default(blockFlow),
            TxScriptEmulator.Default(blockFlow)
          )
      )
    } yield selectedUtxos
  }

  def buildDeployContractUnsignedTx(
      blockFlow: BlockFlow,
      query: BuildDeployContractTx,
      extraUtxosInfo: ExtraUtxosInfo
  ): Try[UnsignedTransaction] = {
    val hardfork = blockFlow.networkConfig.getHardFork(TimeStamp.now())
    for {
      amounts <- BuildTxCommon
        .getAlphAndTokenAmounts(query.initialAttoAlphAmount, query.initialTokenAmounts)
        .left
        .map(badRequest)
      tokenIssuanceInfo <- BuildTxCommon
        .getTokenIssuanceInfo(query.issueTokenAmount, query.issueTokenTo)
        .left
        .map(badRequest)
      initialAttoAlphAmount <- getInitialAttoAlphAmount(amounts._1, hardfork)
      code                  <- query.decodeBytecode()
      lockPair              <- query.getLockPair()
      script <- buildDeployContractTxWithParsedState(
        code.contract,
        Address.Asset(lockPair._1),
        code.initialImmFields,
        code.initialMutFields,
        initialAttoAlphAmount,
        amounts._2,
        tokenIssuanceInfo
      )
      totalAttoAlphAmount <- initialAttoAlphAmount
        .add(query.issueTokenTo.map(_ => dustUtxoAmount).getOrElse(U256.Zero))
        .toRight(failed("ALPH amount overflow"))
      result <- unsignedTxFromScript(
        blockFlow,
        script,
        totalAttoAlphAmount,
        amounts._2,
        lockPair._1,
        lockPair._2,
        query.gasAmount,
        query.gasPrice,
        None,
        extraUtxosInfo
      )
    } yield result._1
  }

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def buildDeployContractTx(
      blockFlow: BlockFlow,
      query: BuildDeployContractTx,
      extraUtxosInfo: ExtraUtxosInfo = ExtraUtxosInfo.empty
  ): Try[BuildDeployContractTxResult] = {
    for {
      utx <- buildDeployContractUnsignedTx(blockFlow, query, extraUtxosInfo)
    } yield BuildDeployContractTxResult.from(utx)
  }

  def buildChainedTransactions(
      blockFlow: BlockFlow,
      buildTransactionRequests: AVector[BuildChainedTx]
  ): Try[AVector[BuildChainedTxResult]] = {
    val buildResults = buildTransactionRequests.foldE(
      (AVector.empty[BuildChainedTxResult], ExtraUtxosInfo.empty)
    ) { case ((buildTransactionResults, extraUtxosInfo), buildTransactionRequest) =>
      for {
        keyPair <- buildTransactionRequest.value.getLockPair()
        (newUtxosForThisLockupScript, restOfUtxos) = extraUtxosInfo.newUtxos.partition(
          _.output.lockupScript == keyPair._1
        )
        buildResult <- buildChainedTransaction(
          blockFlow,
          buildTransactionRequest,
          extraUtxosInfo.copy(newUtxos = newUtxosForThisLockupScript)
        )
        (buildTransactionResult, updatedExtraUtxosInfo) = buildResult
      } yield (
        buildTransactionResults :+ buildTransactionResult,
        updatedExtraUtxosInfo.copy(newUtxos = updatedExtraUtxosInfo.newUtxos ++ restOfUtxos)
      )
    }

    buildResults.map(_._1)
  }

  def buildChainedTransaction(
      blockFlow: BlockFlow,
      buildTransaction: BuildChainedTx,
      extraUtxosInfo: ExtraUtxosInfo
  ): Try[(BuildChainedTxResult, ExtraUtxosInfo)] = {
    buildTransaction match {
      case buildTransfer: BuildChainedTransferTx =>
        for {
          unsignedTx <- buildTransferUnsignedTransaction(
            blockFlow,
            buildTransfer.value,
            extraUtxosInfo
          )
        } yield (
          BuildChainedTransferTxResult(BuildTransferTxResult.from(unsignedTx)),
          extraUtxosInfo.updateWithUnsignedTx(unsignedTx)
        )
      case buildExecuteScript: BuildChainedExecuteScriptTx =>
        for {
          buildUnsignedTxResult <- buildExecuteScriptUnsignedTx(
            blockFlow,
            buildExecuteScript.value,
            extraUtxosInfo
          )
        } yield {
          val (unsignedTx, txScriptExecution) = buildUnsignedTxResult
          val generatedOutputs =
            Output.fromGeneratedOutputs(unsignedTx, txScriptExecution.generatedOutputs)
          val generatedAssetOutputs = generatedOutputs.collect {
            case o: model.AssetOutput =>
              val txOutputRef =
                AssetOutputRef.from(new ScriptHint(o.hint), TxOutputRef.unsafeKey(o.key))
              Some(AssetOutputInfo(txOutputRef, o.toProtocol(), MemPoolOutput))
            case _ => None
          }
          val simulationResult = SimulationResult.from(txScriptExecution)
          (
            BuildChainedExecuteScriptTxResult(
              BuildExecuteScriptTxResult.from(
                unsignedTx,
                simulationResult
              )
            ),
            extraUtxosInfo
              .updateWithUnsignedTx(unsignedTx)
              .updateWithGeneratedAssetOutputs(generatedAssetOutputs)
          )
        }
      case buildDeployContract: BuildChainedDeployContractTx =>
        for {
          unsignedTx <- buildDeployContractUnsignedTx(
            blockFlow,
            buildDeployContract.value,
            extraUtxosInfo
          )
        } yield (
          BuildChainedDeployContractTxResult(
            BuildDeployContractTxResult.from(unsignedTx)
          ),
          extraUtxosInfo.updateWithUnsignedTx(unsignedTx)
        )
    }
  }

  def getInitialAttoAlphAmount(amountOption: Option[U256], hardfork: HardFork): Try[U256] = {
    val minimalContractDeposit = minimalContractStorageDeposit(hardfork)
    amountOption match {
      case Some(amount) =>
        if (amount >= minimalContractDeposit) { Right(amount) }
        else {
          val error =
            s"Expect ${Amount.toAlphString(minimalContractDeposit)} deposit to deploy a new contract"
          Left(failed(error))
        }
      case None => Right(minimalContractDeposit)
    }
  }

  def toVmVal(values: Option[AVector[Val]]): AVector[vm.Val] = {
    values match {
      case Some(vs) => toVmVal(vs)
      case None     => AVector.empty
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def toVmVal(values: AVector[Val]): AVector[vm.Val] = {
    values.fold(AVector.ofCapacity[vm.Val](values.length)) {
      case (acc, value: Val.Primitive) => acc :+ value.toVmVal
      case (acc, value: ValArray)      => acc ++ toVmVal(value.value)
    }
  }

  def verifySignature(query: VerifySignature): Try[Boolean] = {
    Right(SignatureSchema.verify(query.data, query.signature, query.publicKey))
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def buildExecuteScriptUnsignedTx(
      blockFlow: BlockFlow,
      query: BuildExecuteScriptTx,
      extraUtxosInfo: ExtraUtxosInfo
  ): Try[(UnsignedTransaction, TxScriptExecution)] = {
    for {
      _          <- query.check().left.map(badRequest)
      multiplier <- GasEstimationMultiplier.from(query.gasEstimationMultiplier).left.map(badRequest)
      amounts <- BuildTxCommon
        .getAlphAndTokenAmounts(query.attoAlphAmount, query.tokens)
        .left
        .map(badRequest)
      lockPair <- query.getLockPair()
      script <- deserialize[StatefulScript](query.bytecode).left.map(serdeError =>
        badRequest(serdeError.getMessage)
      )
      buildUnsignedTxResult <- unsignedTxFromScript(
        blockFlow,
        script,
        amounts._1.getOrElse(U256.Zero),
        amounts._2,
        lockPair._1,
        lockPair._2,
        query.gasAmount,
        query.gasPrice,
        multiplier,
        extraUtxosInfo
      )
      (unsignedTx, inputWithAssets) = buildUnsignedTxResult
      emulationResult <- TxScriptEmulator
        .Default(blockFlow)
        .emulate(
          inputWithAssets,
          unsignedTx.fixedOutputs,
          unsignedTx.scriptOpt.get,
          Some(unsignedTx.gasAmount),
          Some(unsignedTx.gasPrice)
        )
        .left
        .map(failed)
    } yield {
      (unsignedTx, emulationResult.value)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def buildExecuteScriptTx(
      blockFlow: BlockFlow,
      query: BuildExecuteScriptTx,
      extraUtxosInfo: ExtraUtxosInfo = ExtraUtxosInfo.empty
  ): Try[BuildExecuteScriptTxResult] = {
    for {
      buildUnsignedTxResult <- buildExecuteScriptUnsignedTx(blockFlow, query, extraUtxosInfo)
    } yield BuildExecuteScriptTxResult.from(
      buildUnsignedTxResult._1,
      SimulationResult.from(buildUnsignedTxResult._2)
    )
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def compileScript(query: Compile.Script): Try[CompileScriptResult] = {
    Compiler
      .compileTxScriptFull(query.code, compilerOptions = query.getLangCompilerOptions())
      .map(CompileScriptResult.from)
      .left
      .map(error => failed(error.format(query.code)))
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def compileContract(query: Compile.Contract): Try[CompileContractResult] = {
    Compiler
      .compileContractFull(query.code, compilerOptions = query.getLangCompilerOptions())
      .map(CompileContractResult.from)
      .left
      .map(error => failed(error.format(query.code)))
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def compileProject(blockFlow: BlockFlow, query: Compile.Project): Try[CompileProjectResult] = {
    Compiler
      .compileProject(query.code, compilerOptions = query.getLangCompilerOptions())
      .left
      .map(error => failed(error.format(query.code)))
      .flatMap(result => runTests(blockFlow, query.code, result._1).map(_ => result))
      .map(p => CompileProjectResult.from(p._1, p._2, p._3, p._4))
  }

  def getContractState(
      blockFlow: BlockFlow,
      address: Address.Contract
  ): Try[ContractState] = {
    val groupIndex = address.groupIndex
    for {
      worldState <- wrapResult(blockFlow.getBestCachedWorldState(groupIndex))
      state      <- fetchContractState(worldState, address.contractId)
    } yield state
  }

  def getContractCode(blockFlow: BlockFlow, codeHash: Hash): Try[StatefulContract] = {
    // Since the contract code is not stored in the trie,
    // and all the groups share the same storage,
    // we only need to get the world state from any one group
    val groupIndex = GroupIndex.unsafe(brokerConfig.groupRange(0))
    for {
      worldState <- wrapResult(blockFlow.getBestPersistedWorldState(groupIndex))
      code <- wrapResult(worldState.getContractCode(codeHash)) match {
        case Right(None)       => Left(notFound(s"Contract code hash: ${codeHash.toHexString}"))
        case Right(Some(code)) => Right(code)
        case Left(error)       => Left(error)
      }
    } yield code
  }

  def getParentContract(
      blockFlow: BlockFlow,
      contractAddress: Address.Contract
  ): Try[ContractParent] = {
    for {
      result <- wrapResult(
        blockFlow.getParentContractId(contractAddress.contractId).map { contractIdOpt =>
          ContractParent(contractIdOpt.map(Address.contract))
        }
      )
    } yield result
  }

  def getSubContracts(
      blockFlow: BlockFlow,
      start: Int,
      limit: Int,
      contractAddress: Address.Contract
  ): Try[SubContracts] = {
    wrapResult(blockFlow.getSubContractIds(contractAddress.contractId, start, start + limit))
      .flatMap { case (nextStart, contractIds) =>
        if (contractIds.isEmpty) {
          wrapResult(blockFlow.getSubContractsCurrentCount(contractAddress.contractId)).flatMap {
            case None =>
              Left(notFound(s"Sub-contracts of ${contractAddress}"))
            case Some(currentCount) if currentCount == start =>
              Right(SubContracts(AVector.empty, currentCount))
            case Some(currentCount) =>
              Left(
                notFound(
                  s"Current count for sub-contracts of ${contractAddress} is '$currentCount', sub-contracts start from '$start' with limit '$limit'"
                )
              )
          }
        } else {
          Right(SubContracts(contractIds.map(Address.contract), nextStart))
        }
      }
  }

  def getSubContractsCurrentCount(
      blockFlow: BlockFlow,
      contractAddress: Address.Contract
  ): Try[Int] = {
    val contractId = contractAddress.contractId
    for {
      countOpt <- wrapResult(blockFlow.getSubContractsCurrentCount(contractId))
      count <- countOpt.toRight(
        notFound(s"Current sub-contracts count for contract $contractAddress")
      )
    } yield count
  }

  def getTxIdFromOutputRef(
      blockFlow: BlockFlow,
      outputRef: TxOutputRef
  ): Try[TransactionId] = {
    for {
      resultOpt <- wrapResult(blockFlow.getTxIdTxOutputLocatorsFromOutputRef(outputRef))
      result <- resultOpt.toRight(
        notFound(s"Transaction id for output ref ${outputRef.key.value.toHexString}")
      )
    } yield result.txId
  }

  private def call[P <: CallBase](
      blockFlow: BlockFlow,
      params: P,
      execute: (
          WorldState.Staging,
          GroupIndex,
          BlockHash
      ) => Try[(AVector[vm.Val], StatefulVM.TxScriptExecution)]
  ) = {
    for {
      groupIndex <- params.validate()
      _          <- checkGroup(groupIndex)
      blockHash = params.worldStateBlockHash.getOrElse(
        blockFlow.getBestDeps(groupIndex).uncleHash(groupIndex)
      )
      worldState <- wrapResult(
        blockFlow.getPersistedWorldState(blockHash).map(_.cached().staging())
      )
      resultPair <- execute(worldState, groupIndex, blockHash)
      (returns, exeResult) = resultPair
      contractsState <- params.allContractAddresses.mapE(address =>
        fetchContractState(worldState, address.contractId)
      )
      events = fetchContractEvents(worldState)
      eventsSplit <- extractDebugMessages(events)
    } yield (returns, exeResult, contractsState, eventsSplit._1, eventsSplit._2)
  }

  def callTxScript(blockFlow: BlockFlow, params: CallTxScript): Try[CallTxScriptResult] = {
    val txId        = params.txId.getOrElse(TransactionId.random)
    val inputAssets = params.inputAssets.getOrElse(AVector.empty)
    for {
      script <- deserialize[StatefulScript](params.bytecode).left.map(serdeError =>
        badRequest(serdeError.getMessage)
      )
      result <- call(blockFlow, params, callTxScript(_, _, txId, _, inputAssets, script))
    } yield {
      val (returns, exeResult, contractsState, events, debugMessages) = result
      CallTxScriptResult(
        returns.map(Val.from),
        maximalGasPerTx.subUnsafe(exeResult.gasBox).value,
        contractsState,
        exeResult.contractPrevOutputs.map(_.lockupScript).map(Address.from),
        exeResult.generatedOutputs.mapWithIndex { case (output, index) =>
          Output.from(output, txId, index)
        },
        events,
        debugMessages
      )
    }
  }

  private def callTxScript(
      worldState: WorldState.Staging,
      groupIndex: GroupIndex,
      txId: TransactionId,
      blockHash: BlockHash,
      inputAssets: AVector[TestInputAsset],
      script: StatefulScript
  ): Try[(AVector[vm.Val], StatefulVM.TxScriptExecution)] = {
    val blockEnv = mockupBlockEnv(groupIndex, blockHash, TimeStamp.now())
    val txEnv    = mockupTxEnv(txId, inputAssets)
    val context  = StatefulContext(blockEnv, txEnv, worldState, maximalGasPerTx)
    wrapExeResult(StatefulVM.runTxScriptWithOutputsTestOnly(context, script))
  }

  @inline private def mockupBlockEnv(
      groupIndex: GroupIndex,
      blockHash: BlockHash,
      blockTimeStamp: TimeStamp
  ) = {
    val consensusConfig = consensusConfigs.getConsensusConfig(blockTimeStamp)
    BlockEnv(
      ChainIndex(groupIndex, groupIndex),
      networkConfig.networkId,
      blockTimeStamp,
      consensusConfig.maxMiningTarget,
      Some(blockHash)
    )
  }

  @inline private def mockupTxEnv(txId: TransactionId, inputAssets: AVector[TestInputAsset]) = {
    TxEnv.mockup(
      txId = txId,
      signatures = Stack.popOnly(AVector.empty[Signature]),
      prevOutputs = inputAssets.map(_.toAssetOutput),
      fixedOutputs = AVector.empty[AssetOutput],
      gasPrice = nonCoinbaseMinGasPrice,
      gasAmount = maximalGasPerTx,
      isEntryMethodPayable = true
    )
  }

  def callContract(blockFlow: BlockFlow, params: CallContract): CallContractResult = {
    val txId = params.txId.getOrElse(TransactionId.random)
    val result = call(blockFlow, params, callContract(params, _, _, _, txId)).map {
      case (returns, exeResult, contractsState, events, debugMessages) =>
        CallContractSucceeded(
          returns.map(Val.from),
          maximalGasPerTx.subUnsafe(exeResult.gasBox).value,
          contractsState,
          exeResult.contractPrevOutputs.map(_.lockupScript).map(Address.from),
          exeResult.generatedOutputs.mapWithIndex { case (output, index) =>
            Output.from(output, txId, index)
          },
          events,
          debugMessages
        )
    }
    result match {
      case Right(result) => result
      case Left(error)   => CallContractFailed(error.detail)
    }
  }

  private def callContract(
      params: CallContract,
      worldState: WorldState.Staging,
      groupIndex: GroupIndex,
      blockHash: BlockHash,
      txId: TransactionId
  ): Try[(AVector[vm.Val], StatefulVM.TxScriptExecution)] = {
    val contractId = params.address.contractId
    for {
      contractObj <- wrapResult(worldState.getContractObj(contractId))
      method      <- wrapExeResult(contractObj.code.getMethod(params.methodIndex))
      result <- executeContractMethod(
        worldState,
        groupIndex,
        contractId,
        params.callerAddress.map(_.contractId),
        txId,
        blockHash,
        TimeStamp.now(),
        params.inputAssets.getOrElse(AVector.empty),
        params.methodIndex,
        params.args.getOrElse(AVector.empty),
        method,
        (_, detail) => detail
      )
    } yield result
  }

  val maxCallsInMultipleCall: Int = ServerUtils.maxCallsInMultipleCall

  def multipleCallContract(
      blockFlow: BlockFlow,
      params: MultipleCallContract
  ): Try[MultipleCallContractResult] = {
    if (params.calls.length > maxCallsInMultipleCall) {
      Left(
        failed(s"The number of contract calls exceeds the maximum limit($maxCallsInMultipleCall)")
      )
    } else {
      val bestDepss = blockFlow.brokerConfig.groupRange.map(group =>
        blockFlow.getBestDeps(GroupIndex.unsafe(group))
      )
      params.calls
        .mapE { call =>
          call.validate().map { groupIndex =>
            val blockHash = call.worldStateBlockHash.getOrElse(
              bestDepss(groupIndex.value).uncleHash(groupIndex)
            )
            callContract(blockFlow, call.copy(worldStateBlockHash = Some(blockHash)))
          }
        }
        .flatMap(results => Right(MultipleCallContractResult(results)))
    }
  }

  private def createContracts(
      worldState: WorldState.Staging,
      allContracts: Map[String, CompiledContract],
      testingContract: CompiledContract,
      test: Testing.CompiledUnitTest[StatefulContext],
      blockHash: BlockHash,
      txId: TransactionId
  ) = {
    test.before.foreachE { c =>
      val contractCode = if (c.typeId == testingContract.ast.ident) {
        val code = testingContract.debugCode
        code.copy(methods = code.methods :+ test.method)
      } else {
        allContracts(c.typeId.name).debugCode
      }
      createContract(
        worldState,
        c.contractId,
        contractCode,
        c.immFields,
        c.mutFields,
        AssetState.forTesting(c.tokens),
        blockHash,
        txId
      )
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  private def getUnitTestError(
      sourceCode: String,
      testingContract: CompiledContract,
      testName: String,
      exeFailure: vm.ExeFailure,
      debugMessages: String
  ): String = {
    val (errorCode, msg) = exeFailure match {
      case error @ vm.AssertionFailedWithErrorCode(_, errorCode) =>
        (Some(errorCode), error.getErrorMessageWithoutErrorCode)
      case _ => (None, exeFailure.toString())
    }
    val detail = s"VM execution error: $msg"
    testingContract.tests
      .getError(testName, errorCode, detail, Option.when(debugMessages.nonEmpty)(debugMessages))
      .format(sourceCode)
  }

  private def runTests(
      blockFlow: BlockFlow,
      sourceCode: String,
      contracts: AVector[CompiledContract]
  ): Try[Unit] = {
    val contractMap = contracts.map(c => (c.ast.ident.name, c)).iterator.toMap
    contracts.foreachE { testingContract =>
      testingContract.tests.tests.foreachE { test =>
        for {
          groupIndex <- test.getGroupIndex.left.map(badRequest)
          blockHash      = test.settings.flatMap(_.blockHash).getOrElse(BlockHash.random)
          blockTimeStamp = test.settings.flatMap(_.blockTimeStamp).getOrElse(TimeStamp.now())
          txId           = TransactionId.random
          worldState <- wrapResult(blockFlow.getBestCachedWorldState(groupIndex).map(_.staging()))
          _ <- createContracts(
            worldState,
            contractMap,
            testingContract,
            test,
            blockHash,
            txId
          )
          _ <- executeContractMethod(
            worldState,
            groupIndex,
            test.selfContract.contractId,
            None,
            txId,
            blockHash,
            blockTimeStamp,
            test.assets.map(TestInputAsset.from).getOrElse(AVector.empty),
            testingContract.debugCode.methodsLength,
            AVector.empty,
            test.method,
            (exeFailure, debugMessages) =>
              getUnitTestError(sourceCode, testingContract, test.name, exeFailure, debugMessages)
          )
        } yield ()
      }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def runTestContract(
      blockFlow: BlockFlow,
      testContract: TestContract.Complete
  ): Try[TestContractResult] = {
    val contractId = testContract.contractId
    for {
      groupIndex <- testContract.groupIndex
      worldState <- wrapResult(blockFlow.getBestCachedWorldState(groupIndex).map(_.staging()))
      _ <- testContract.existingContracts.foreachE(
        createContract(worldState, _, testContract.blockHash, testContract.txId)
      )
      _      <- createContract(worldState, contractId, testContract)
      method <- wrapExeResult(testContract.code.getMethod(testContract.testMethodIndex))
      executionResultPair <- executeContractMethod(
        worldState,
        groupIndex,
        contractId,
        testContract.callerContractIdOpt,
        testContract.txId,
        testContract.blockHash,
        testContract.blockTimeStamp,
        testContract.inputAssets,
        testContract.testMethodIndex,
        testContract.testArgs,
        method,
        (exeFailure, debugMessages) =>
          debugMessages ++ s"VM execution error: ${exeFailure.toString()}"
      )
      events = fetchContractEvents(worldState)
      contractIds <- getCreatedAndDestroyedContractIds(events)
      postState   <- fetchContractsState(worldState, testContract, contractIds._1, contractIds._2)
      eventsSplit <- extractDebugMessages(events)
    } yield {
      val executionOutputs = executionResultPair._1
      val executionResult  = executionResultPair._2
      val gasUsed          = maximalGasPerTx.subUnsafe(executionResult.gasBox)
      TestContractResult(
        address = Address.contract(testContract.contractId),
        codeHash = postState._2,
        returns = executionOutputs.map(Val.from),
        gasUsed = gasUsed.value,
        contracts = postState._1,
        txInputs = executionResult.contractPrevOutputs.map(_.lockupScript).map(Address.from),
        txOutputs = executionResult.generatedOutputs.mapWithIndex { case (output, index) =>
          Output.from(output, TransactionId.zero, index)
        },
        events = eventsSplit._1,
        debugMessages = eventsSplit._2
      )
    }
  }

  def extractDebugMessage(event: ContractEventByTxId): Try[DebugMessage] = {
    (event.fields.length, event.fields.headOption) match {
      case (1, Some(message: ValByteVec)) =>
        Right(DebugMessage(event.contractAddress, message.value.utf8String))
      case _ =>
        Left(failed("Invalid debug message"))
    }
  }

  def extractDebugMessages(
      events: AVector[ContractEventByTxId]
  ): Try[(AVector[ContractEventByTxId], AVector[DebugMessage])] = {
    val nonDebugEvents = events.filter(e => I256.from(e.eventIndex) != debugEventIndex.v)
    events.filter(e => I256.from(e.eventIndex) == debugEventIndex.v).mapE(extractDebugMessage).map {
      debugMessages => nonDebugEvents -> debugMessages
    }
  }

  private def getCreatedAndDestroyedContractIds(
      events: AVector[ContractEventByTxId]
  ): Try[(AVector[ContractId], AVector[ContractId])] = {
    events.foldE((AVector.empty[ContractId], AVector.empty[ContractId])) {
      case ((createdIds, destroyedIds), event) =>
        event.eventIndex match {
          case vm.createContractEventIndexInt =>
            event.getContractId() match {
              case Some(contractId) => Right((createdIds :+ contractId, destroyedIds))
              case None             => Left(failed(s"invalid create contract event $event"))
            }
          case vm.destroyContractEventIndexInt =>
            event.getContractId() match {
              case Some(contractId) => Right((createdIds, destroyedIds :+ contractId))
              case None             => Left(failed(s"invalid destroy contract event $event"))
            }
          case _ => Right((createdIds, destroyedIds))
        }
    }
  }

  private def fetchContractsState(
      worldState: WorldState.Staging,
      testContract: TestContract.Complete,
      createdContractIds: AVector[ContractId],
      destroyedContractIds: AVector[ContractId]
  ): Try[(AVector[ContractState], Hash)] = {
    val contractIds = testContract.existingContracts.fold(createdContractIds) {
      case (ids, contractState) =>
        if (destroyedContractIds.contains(contractState.id)) ids else ids :+ contractState.id
    }
    contractIds.mapE(id => fetchContractState(worldState, id)).flatMap { existingContractsState =>
      if (destroyedContractIds.contains(testContract.contractId)) {
        Right((existingContractsState, testContract.code.hash))
      } else {
        fetchTestContractState(worldState, testContract).map {
          case (testContractState, testCodeHash) =>
            (existingContractsState :+ testContractState, testCodeHash)
        }
      }
    }
  }

  private def fetchTestContractState(
      worldState: WorldState.Staging,
      testContract: TestContract.Complete
  ): Try[(ContractState, Hash)] = {
    fetchContractState(worldState, testContract.contractId) map { testContractState =>
      val codeHash = testContract.codeHash(testContractState.codeHash)
      // Note that we need to update the code hash as the contract might have been migrated
      (testContractState.copy(codeHash = codeHash), codeHash)
    }
  }

  private def fetchContractEvents(worldState: WorldState.Staging): AVector[ContractEventByTxId] = {
    val allLogStates = worldState.nodeIndexesState.logState.getNewLogs()
    allLogStates.flatMap(logStates =>
      logStates.states.flatMap(state =>
        AVector(
          ContractEventByTxId(
            logStates.blockHash,
            Address.contract(logStates.contractId),
            state.index.toInt,
            state.fields.map(Val.from)
          )
        )
      )
    )
  }

  private def fetchContractState(
      worldState: WorldState.AbstractCached,
      contractId: ContractId
  ): Try[ContractState] = {
    val result = for {
      state          <- worldState.getContractState(contractId)
      code           <- worldState.getContractCode(state)
      contract       <- code.toContract().left.map(IOError.Serde.apply)
      contractOutput <- worldState.getContractAsset(state.contractOutputRef)
    } yield ContractState(
      Address.contract(contractId),
      contract,
      contract.hash,
      Some(state.initialStateHash),
      state.immFields.map(Val.from),
      state.mutFields.map(Val.from),
      AssetState.from(contractOutput)
    )
    wrapResult(result)
  }

  // scalastyle:off method.length parameter.number
  private def executeContractMethod(
      worldState: WorldState.Staging,
      groupIndex: GroupIndex,
      contractId: ContractId,
      callerContractIdOpt: Option[ContractId],
      txId: TransactionId,
      blockHash: BlockHash,
      blockTimeStamp: TimeStamp,
      inputAssets: AVector[TestInputAsset],
      methodIndex: Int,
      args: AVector[Val],
      method: Method[StatefulContext],
      errorFormatter: (vm.ExeFailure, String) => String
  ): Try[(AVector[vm.Val], StatefulVM.TxScriptExecution)] = {
    val blockEnv   = mockupBlockEnv(groupIndex, blockHash, blockTimeStamp)
    val testGasFee = nonCoinbaseMinGasPrice * maximalGasPerTx
    val txEnv      = mockupTxEnv(txId, inputAssets)
    val context    = StatefulContext(blockEnv, txEnv, worldState, maximalGasPerTx)
    for {
      _ <- checkArgs(args, method)
      _ <- checkGasFee(testGasFee, inputAssets)
      result <- runWithDebugError(
        context,
        contractId,
        callerContractIdOpt,
        inputAssets,
        methodIndex,
        args,
        method,
        testGasFee,
        errorFormatter
      )
    } yield result
  }
  // scalastyle:on method.length parameter.number

  private def checkArgs(args: AVector[Val], method: Method[StatefulContext]): Try[Unit] = {
    if (args.sumBy(_.flattenSize()) != method.argsLength) {
      Left(
        failed(
          "The number of parameters is different from the number specified by the target method"
        )
      )
    } else {
      Right(())
    }
  }

  private def checkGasFee(testGasFee: U256, inputAssets: AVector[TestInputAsset]): Try[Unit] = {
    inputAssets.headOption match {
      case Some(inputAsset) if inputAsset.asset.attoAlphAmount < testGasFee =>
        Left(
          failed(
            s"First input asset should have at least ${ALPH.prettifyAmount(testGasFee)} to cover gas"
          )
        )
      case _ =>
        Right(())
    }
  }

  // scalastyle:off method.length parameter.number
  def runWithDebugError(
      context: StatefulContext,
      contractId: ContractId,
      callerContractIdOpt: Option[ContractId],
      inputAssets: AVector[TestInputAsset],
      methodIndex: Int,
      args: AVector[Val],
      method: Method[StatefulContext],
      testGasFee: U256,
      errorFormatter: (vm.ExeFailure, String) => String
  ): Try[(AVector[vm.Val], StatefulVM.TxScriptExecution)] = {
    val executionResult = callerContractIdOpt match {
      case None =>
        val script = StatefulScript.unsafe(
          AVector(
            Method[StatefulContext](
              isPublic = true,
              usePreapprovedAssets = inputAssets.nonEmpty,
              useContractAssets = false,
              usePayToContractOnly = false,
              argsLength = 0,
              localsLength = 0,
              returnLength = method.returnLength,
              instrs = approveAsset(inputAssets, testGasFee) ++ callExternal(
                args,
                methodIndex,
                method.argsLength,
                method.returnLength,
                contractId
              )
            )
          )
        )
        StatefulVM.runTxScriptWithOutputsTestOnly(context, script)
      case Some(callerContractId) =>
        val mockCallerContract = StatefulContract(
          0,
          AVector(
            Method[StatefulContext](
              isPublic = true,
              usePreapprovedAssets = inputAssets.nonEmpty,
              useContractAssets = false,
              usePayToContractOnly = false,
              argsLength = 0,
              localsLength = 0,
              returnLength = method.returnLength,
              instrs = approveAsset(inputAssets, testGasFee) ++ callExternal(
                args,
                methodIndex,
                method.argsLength,
                method.returnLength,
                contractId
              )
            )
          )
        )
        StatefulVM.runCallerContractWithOutputsTestOnly(
          context,
          mockCallerContract,
          callerContractId
        )
    }
    executionResult match {
      case Right(result)         => Right(result)
      case Left(Left(ioFailure)) => Left(failedInIO(ioFailure.error))
      case Left(Right(exeFailure)) =>
        val events = fetchContractEvents(context.worldState)
        extractDebugMessages(events).flatMap { case (_, debugMessages) =>
          Left(failed(errorFormatter(exeFailure, showDebugMessages(debugMessages))))
        }
    }
  }
  // scalastyle:on method.length parameter.number

  def showDebugMessages(messages: AVector[DebugMessage]): String = {
    if (messages.isEmpty) {
      ""
    } else {
      messages.mkString("", "\n", "\n")
    }
  }

  private def approveAsset(
      inputAssets: AVector[TestInputAsset],
      gasFee: U256
  ): AVector[Instr[StatefulContext]] = {
    inputAssets.flatMapWithIndex { (asset, index) =>
      val gasFeeOpt = if (index == 0) Some(gasFee) else None
      asset.approveAll(gasFeeOpt)
    }
  }

  private def callExternal(
      args: AVector[Val],
      methodIndex: Int,
      argLength: Int,
      returnLength: Int,
      contractId: ContractId
  ): AVector[Instr[StatefulContext]] = {
    toVmVal(args).map(_.toConstInstr: Instr[StatefulContext]) ++
      AVector[Instr[StatefulContext]](
        ConstInstr.u256(vm.Val.U256(U256.unsafe(argLength))),
        ConstInstr.u256(vm.Val.U256(U256.unsafe(returnLength))),
        BytesConst(vm.Val.ByteVec(contractId.bytes)),
        CallExternal(methodIndex.toByte)
      )
  }

  def createContract(
      worldState: WorldState.Staging,
      existingContract: ContractState,
      blockHash: BlockHash,
      txId: TransactionId
  ): Try[Unit] = {
    createContract(
      worldState,
      existingContract.id,
      existingContract.bytecode,
      toVmVal(existingContract.immFields),
      toVmVal(existingContract.mutFields),
      existingContract.asset,
      blockHash,
      txId
    )
  }

  def createContract(
      worldState: WorldState.Staging,
      contractId: ContractId,
      testContract: TestContract.Complete
  ): Try[Unit] = {
    createContract(
      worldState,
      contractId,
      testContract.code,
      toVmVal(testContract.initialImmFields),
      toVmVal(testContract.initialMutFields),
      testContract.initialAsset,
      testContract.blockHash,
      testContract.txId
    )
  }

  def createContract(
      worldState: WorldState.Staging,
      contractId: ContractId,
      code: StatefulContract,
      initialImmState: AVector[vm.Val],
      initialMutState: AVector[vm.Val],
      asset: AssetState,
      blockHash: BlockHash,
      txId: TransactionId
  ): Try[Unit] = {
    val outputRef = contractId.inaccurateFirstOutputRef()
    val output    = asset.toContractOutput(contractId)

    wrapResult(
      worldState.createContractLemanUnsafe(
        contractId,
        code.toHalfDecoded(),
        initialImmState,
        initialMutState,
        outputRef,
        output,
        txId,
        Some(TxOutputLocator(blockHash, 0, 0))
      )
    )
  }
}

object ServerUtils {

  val maxCallsInMultipleCall: Int = 20

  private def validateUtxInputs(
      unsignedTx: UnsignedTransaction
  ): Try[Unit] = {
    if (unsignedTx.inputs.nonEmpty) {
      Right(())
    } else {
      Left(ApiError.BadRequest("Invalid transaction: empty inputs"))
    }
  }

  private def validateUtxGasFee(
      unsignedTx: UnsignedTransaction
  )(implicit apiConfig: ApiConfig): Try[Unit] = {
    val gasFee = unsignedTx.gasPrice * unsignedTx.gasAmount
    if (gasFee <= apiConfig.gasFeeCap) {
      Right(())
    } else {
      val capAmount    = ALPH.prettifyAmount(apiConfig.gasFeeCap)
      val gasFeeAmount = ALPH.prettifyAmount(gasFee)
      Left(
        ApiError.BadRequest(
          s"Gas fee exceeds the limit: maximum allowed is $capAmount, but got $gasFeeAmount. " +
            s"Please lower the gas price or adjust the alephium.api.gas-fee-cap in your user.conf file."
        )
      )
    }
  }

  def validateUnsignedTransaction(
      unsignedTx: UnsignedTransaction
  )(implicit apiConfig: ApiConfig): Try[UnsignedTransaction] = {
    for {
      _ <- validateUtxInputs(unsignedTx)
      _ <- validateUtxGasFee(unsignedTx)
    } yield unsignedTx
  }

  def buildDeployContractTxWithParsedState(
      contract: StatefulContract,
      address: Address,
      initialImmFields: AVector[vm.Val],
      initialMutFields: AVector[vm.Val],
      initialAttoAlphAmount: U256,
      initialTokenAmounts: AVector[(TokenId, U256)],
      tokenIssuanceInfo: Option[(U256, Option[Address.Asset])]
  ): Try[StatefulScript] = {
    buildDeployContractScriptWithParsedState(
      Hex.toHexString(serialize(contract)),
      address,
      initialImmFields,
      initialMutFields,
      initialAttoAlphAmount,
      initialTokenAmounts,
      tokenIssuanceInfo
    )
  }

  def buildDeployContractScriptRawWithParsedState(
      codeRaw: String,
      address: Address,
      initialImmFields: AVector[vm.Val],
      initialMutFields: AVector[vm.Val],
      initialAttoAlphAmount: U256,
      initialTokenAmounts: AVector[(TokenId, U256)],
      tokenIssuanceInfo: Option[(U256, Option[Address.Asset])]
  ): String = {
    val immStateRaw = Hex.toHexString(serialize(initialImmFields))
    val mutStateRaw = Hex.toHexString(serialize(initialMutFields))
    def toCreate(approveAssets: String): String = tokenIssuanceInfo match {
      case Some((issueAmount, Some(issueTo))) =>
        s"""
           |createContractWithToken!$approveAssets(#$codeRaw, #$immStateRaw, #$mutStateRaw, ${issueAmount.v}, @$issueTo)
           |  transferToken!{@$address -> ALPH: dustAmount!()}(@$address, @$issueTo, ALPH, dustAmount!())
           |""".stripMargin.stripLeading.stripTrailing
      case Some((issueAmount, None)) =>
        s"createContractWithToken!$approveAssets(#$codeRaw, #$immStateRaw, #$mutStateRaw, ${issueAmount.v})"
      case None =>
        s"createContract!$approveAssets(#$codeRaw, #$immStateRaw, #$mutStateRaw)"
    }

    val create = if (initialTokenAmounts.isEmpty) {
      val approveAssets = s"{@$address -> ALPH: ${initialAttoAlphAmount.v}}"
      toCreate(approveAssets)
    } else {
      val approveTokens = initialTokenAmounts
        .map { case (tokenId, amount) =>
          s"#${tokenId.toHexString}: ${amount.v}"
        }
        .mkString(", ")
      val approveAssets = s"{@$address -> ALPH: ${initialAttoAlphAmount.v}, $approveTokens}"
      toCreate(approveAssets)
    }
    s"""
       |TxScript Main {
       |  $create
       |}
       |""".stripMargin
  }

  def buildDeployContractScriptWithParsedState(
      codeRaw: String,
      address: Address,
      initialImmFields: AVector[vm.Val],
      initialMutFields: AVector[vm.Val],
      initialAttoAlphAmount: U256,
      initialTokenAmounts: AVector[(TokenId, U256)],
      tokenIssuanceInfo: Option[(U256, Option[Address.Asset])]
  ): Try[StatefulScript] = {
    val scriptRaw = buildDeployContractScriptRawWithParsedState(
      codeRaw,
      address,
      initialImmFields,
      initialMutFields,
      initialAttoAlphAmount,
      initialTokenAmounts,
      tokenIssuanceInfo
    )

    wrapCompilerResult(Compiler.compileTxScript(scriptRaw))
  }
}
