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

import scala.concurrent._

import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging

import org.alephium.api._
import org.alephium.api.ApiError
import org.alephium.api.model
import org.alephium.api.model.{AssetOutput => _, Transaction => _, TransactionTemplate => _, _}
import org.alephium.crypto.Byte32
import org.alephium.flow.core.{BlockFlow, BlockFlowState, UtxoSelectionAlgo}
import org.alephium.flow.core.UtxoSelectionAlgo._
import org.alephium.flow.gasestimation._
import org.alephium.flow.handler.TxHandler
import org.alephium.io.IOError
import org.alephium.protocol.{vm, Hash, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.config._
import org.alephium.protocol.model._
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.{failed => _, ContractState => _, Val => _, _}
import org.alephium.ralph.Compiler
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util._

// scalastyle:off number.of.methods
// scalastyle:off file.size.limit number.of.types
class ServerUtils(implicit
    brokerConfig: BrokerConfig,
    consensusConfig: ConsensusConfig,
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
    getHeightedBlocks(blockFlow, timeInterval).map { heightedBlocks =>
      BlocksPerTimeStampRange(heightedBlocks.map(_._2.map { case (block, height) =>
        BlockEntry.from(block, height)
      }))
    }
  }

  def getBlocksAndEvents(
      blockFlow: BlockFlow,
      timeInterval: TimeInterval
  ): Try[BlocksAndEventsPerTimeStampRange] = {
    getHeightedBlocks(blockFlow, timeInterval).flatMap { heightedBlocks =>
      heightedBlocks
        .mapE(_._2.mapE { case (block, height) =>
          val blockEntry = BlockEntry.from(block, height)
          getEventsByBlockHash(blockFlow, blockEntry.hash).map(events =>
            BlockAndEvents(blockEntry, events.events)
          )
        })
        .map(BlocksAndEventsPerTimeStampRange)
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

  def getBalance(blockFlow: BlockFlow, address: Address): Try[Balance] = {
    val utxosLimit = apiConfig.defaultUtxosLimit
    for {
      _ <- checkGroup(address.lockupScript)
      balance <- blockFlow
        .getBalance(
          address.lockupScript,
          utxosLimit
        )
        .map(Balance.from(_, utxosLimit))
        .left
        .flatMap(failed)
    } yield balance
  }

  def getUTXOsIncludePool(blockFlow: BlockFlow, address: Address): Try[UTXOs] = {
    val utxosLimit = apiConfig.defaultUtxosLimit
    for {
      _ <- checkGroup(address.lockupScript)
      utxos <- blockFlow
        .getUTXOsIncludePool(address.lockupScript, utxosLimit)
        .map(_.map(outputInfo => UTXO.from(outputInfo.ref, outputInfo.output)))
        .left
        .flatMap(failed)
    } yield UTXOs.from(utxos, utxosLimit)
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
      val groupIndex = GroupIndex.unsafe(group)
      val txs        = blockFlow.getMemPool(groupIndex).getAll()
      val groupedTxs = txs.filter(_.chainIndex.from == groupIndex).groupBy(_.chainIndex)
      acc ++ AVector.from(
        groupedTxs.map { case (chainIndex, txs) =>
          MempoolTransactions(
            chainIndex.from.value,
            chainIndex.to.value,
            txs.map(model.TransactionTemplate.fromProtocol)
          )
        }
      )
    }
    Right(result)
  }

  def buildTransaction(
      blockFlow: BlockFlow,
      query: BuildTransaction
  ): Try[BuildTransactionResult] = {
    for {
      _ <- checkGroup(query.fromPublicKey)
      unsignedTx <- prepareUnsignedTransaction(
        blockFlow,
        query.fromPublicKey,
        query.utxos,
        query.destinations,
        query.gasAmount,
        query.gasPrice.getOrElse(nonCoinbaseMinGasPrice),
        query.targetBlockHash
      )
    } yield {
      BuildTransactionResult.from(unsignedTx)
    }
  }

  def buildMultisig(
      blockFlow: BlockFlow,
      query: BuildMultisig
  ): Try[BuildTransactionResult] = {
    for {
      _            <- checkGroup(query.fromAddress.lockupScript)
      unlockScript <- buildUnlockScript(query.fromAddress.lockupScript, query.fromPublicKeys)
      unsignedTx <- prepareUnsignedTransaction(
        blockFlow,
        query.fromAddress.lockupScript,
        unlockScript,
        query.destinations,
        query.gas,
        query.gasPrice.getOrElse(nonCoinbaseMinGasPrice),
        None
      )
    } yield {
      BuildTransactionResult.from(unsignedTx)
    }
  }

  private def buildUnlockScript(
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
        query.lockTime,
        query.gasAmount,
        query.gasPrice.getOrElse(nonCoinbaseMinGasPrice),
        query.targetBlockHash
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

  def getBlock(blockFlow: BlockFlow, hash: BlockHash): Try[BlockEntry] =
    for {
      _ <- checkHashChainIndex(hash)
      block <- blockFlow
        .getBlock(hash)
        .left
        .map(_ => failed(s"Fail fetching block with header ${hash.toHexString}"))
      height <- blockFlow
        .getHeight(block.header)
        .left
        .map(failedInIO)
    } yield BlockEntry.from(block, height)

  def getBlockAndEvents(blockFlow: BlockFlow, hash: BlockHash): Try[BlockAndEvents] =
    for {
      block  <- getBlock(blockFlow, hash)
      events <- getEventsByBlockHash(blockFlow, hash)
    } yield BlockAndEvents(block, events.events)

  def isBlockInMainChain(blockFlow: BlockFlow, blockHash: BlockHash): Try[Boolean] = {
    for {
      height <- blockFlow
        .getHeight(blockHash)
        .left
        .map(_ => failed(s"Fail fetching block height with hash ${blockHash.toHexString}"))
      hashes <- blockFlow
        .getHashes(ChainIndex.from(blockHash), height)
        .left
        .map(failedInIO)
    } yield hashes.headOption.contains(blockHash)
  }

  def getBlockHeader(blockFlow: BlockFlow, hash: BlockHash): Try[BlockHeaderEntry] =
    for {
      blockHeader <- blockFlow
        .getBlockHeader(hash)
        .left
        .map(_ => failed(s"Fail fetching block header with hash ${hash}"))
      height <- blockFlow
        .getHeight(hash)
        .left
        .map(failedInIO)
    } yield BlockHeaderEntry.from(blockHeader, height)

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
        .getMaxHeight(chainIndex)
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
      case Some(tx) => Right(model.Transaction.fromProtocol(tx))
      case None     => Left(notFound(s"Transaction ${txId.toHexString}"))
    }
  }

  def getChainIndexForTx(
      blockFlow: BlockFlow,
      txId: TransactionId
  ): Try[ChainIndex] = {
    searchLocalTransactionStatus(blockFlow, txId, brokerConfig.chainIndexes) match {
      case Right(Confirmed(blockHash, _, _, _, _)) =>
        Right(ChainIndex.from(blockHash))
      case Right(TxNotFound()) =>
        Left(notFound(s"Transaction ${txId.toHexString}"))
      case Right(MemPooled()) =>
        Left(failed(s"Transaction ${txId.toHexString} still in mempool"))
      case Left(error) =>
        Left(error)
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

  def getEventsByContractId(
      blockFlow: BlockFlow,
      start: Int,
      limit: Int,
      contractId: ContractId
  ): Try[ContractEvents] = {
    wrapResult(
      blockFlow
        .getEvents(contractId, start, start + limit)
        .map {
          case (nextStart, logStatesVec) => {
            ContractEvents.from(logStatesVec, nextStart)
          }
        }
    )
  }

  private def publishTx(txHandler: ActorRefT[TxHandler.Command], tx: TransactionTemplate)(implicit
      askTimeout: Timeout
  ): FutureTry[SubmitTxResult] = {
    val message = TxHandler.AddToMemPool(AVector(tx), isIntraCliqueSyncing = false)
    txHandler.ask(message).mapTo[TxHandler.Event].map {
      case _: TxHandler.AddSucceeded =>
        Right(SubmitTxResult(tx.id, tx.fromGroup.value, tx.toGroup.value))
      case TxHandler.AddFailed(_, reason) =>
        logger.warn(s"Failed in adding tx: $reason")
        Left(failed(reason))
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

      TxOutputInfo(
        destination.address.lockupScript,
        destination.attoAlphAmount.value,
        tokensInfo,
        destination.lockTime,
        destination.message
      )
    }
  }

  def prepareUnsignedTransaction(
      blockFlow: BlockFlow,
      fromPublicKey: PublicKey,
      outputRefsOpt: Option[AVector[OutputRef]],
      destinations: AVector[Destination],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      targetBlockHashOpt: Option[BlockHash]
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
      targetBlockHashOpt
    )
  }

  def prepareUnsignedTransaction(
      blockFlow: BlockFlow,
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      outputRefsOpt: Option[AVector[OutputRef]],
      destinations: AVector[Destination],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      targetBlockHashOpt: Option[BlockHash]
  ): Try[UnsignedTransaction] = {
    val outputInfos = prepareOutputInfos(destinations)

    val transferResult = outputRefsOpt match {
      case Some(outputRefs) =>
        val allAssetType = outputRefs.forall(outputRef => Hint.unsafe(outputRef.hint).isAssetType)
        if (allAssetType) {
          val assetOutputRefs = outputRefs.map(_.unsafeToAssetOutputRef())
          blockFlow.transfer(
            targetBlockHashOpt,
            fromLockupScript,
            fromUnlockScript,
            assetOutputRefs,
            outputInfos,
            gasOpt,
            gasPrice
          )
        } else {
          Right(Left("Selected UTXOs must be of asset type"))
        }
      case None =>
        blockFlow.transfer(
          targetBlockHashOpt,
          fromLockupScript,
          fromUnlockScript,
          outputInfos,
          gasOpt,
          gasPrice,
          apiConfig.defaultUtxosLimit
        )
    }

    transferResult match {
      case Right(Right(unsignedTransaction)) => validateUnsignedTransaction(unsignedTransaction)
      case Right(Left(error))                => Left(failed(error))
      case Left(error)                       => failed(error)
    }
  }

  def prepareSweepAddressTransaction(
      blockFlow: BlockFlow,
      fromPublicKey: PublicKey,
      toAddress: Address.Asset,
      lockTimeOpt: Option[TimeStamp],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice,
      targetBlockHashOpt: Option[BlockHash]
  ): Try[AVector[UnsignedTransaction]] = {
    blockFlow.sweepAddress(
      targetBlockHashOpt,
      fromPublicKey,
      toAddress.lockupScript,
      lockTimeOpt,
      gasOpt,
      gasPrice,
      Int.MaxValue
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
      targetBlockHashOpt: Option[BlockHash]
  ): Try[UnsignedTransaction] = {
    prepareUnsignedTransaction(
      blockFlow,
      fromLockupScript,
      fromUnlockScript,
      None,
      destinations,
      gasOpt,
      gasPrice,
      targetBlockHashOpt
    )
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
      fromPublicKey: PublicKey,
      gas: Option[GasBox],
      gasPrice: Option[GasPrice]
  ): Try[UnsignedTransaction] = {
    val lockupScript = LockupScript.p2pkh(fromPublicKey)
    val unlockScript = UnlockScript.p2pkh(fromPublicKey)
    val utxosLimit   = apiConfig.defaultUtxosLimit
    for {
      allUtxos <- blockFlow.getUsableUtxos(lockupScript, utxosLimit).left.map(failedInIO)
      allInputs = allUtxos.map(_.ref).map(TxInput(_, unlockScript))
      unsignedTx <- UtxoSelectionAlgo
        .Build(ProvidedGas(gas, gasPrice.getOrElse(nonCoinbaseMinGasPrice)))
        .select(
          AssetAmounts(amount, tokens),
          unlockScript,
          allUtxos,
          txOutputsLength = 0,
          Some(script),
          AssetScriptGasEstimator.Default(blockFlow),
          TxScriptGasEstimator.Default(allInputs, blockFlow)
        )
        .map { selectedUtxos =>
          val inputs = selectedUtxos.assets.map(_.ref).map(TxInput(_, unlockScript))
          UnsignedTransaction(Some(script), inputs, AVector.empty).copy(
            gasAmount = gas.getOrElse(selectedUtxos.gas),
            gasPrice = gasPrice.getOrElse(nonCoinbaseMinGasPrice)
          )
        }
        .left
        .map(badRequest)
      validatedUnsignedTx <- validateUnsignedTransaction(unsignedTx)
    } yield validatedUnsignedTx
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def buildDeployContractTx(
      blockFlow: BlockFlow,
      query: BuildDeployContractTx
  ): Try[BuildDeployContractTxResult] = {
    for {
      amounts <- BuildTxCommon
        .getAlphAndTokenAmounts(query.initialAttoAlphAmount, query.initialTokenAmounts)
        .left
        .map(badRequest)
      initialAttoAlphAmount <- getInitialAttoAlphAmount(amounts._1)
      code                  <- query.decodeBytecode()
      address = Address.p2pkh(query.fromPublicKey)
      script <- buildDeployContractTxWithParsedState(
        code.contract,
        address,
        code.initialImmFields,
        code.initialMutFields,
        initialAttoAlphAmount,
        amounts._2,
        query.issueTokenAmount.map(_.value)
      )
      utx <- unsignedTxFromScript(
        blockFlow,
        script,
        initialAttoAlphAmount,
        AVector.empty,
        query.fromPublicKey,
        query.gasAmount,
        query.gasPrice
      )
    } yield BuildDeployContractTxResult.from(utx)
  }

  def getInitialAttoAlphAmount(amountOption: Option[U256]): Try[U256] = {
    amountOption match {
      case Some(amount) =>
        if (amount >= minimalAlphInContract) { Right(amount) }
        else {
          val error =
            s"Expect ${Amount.toAlphString(minimalAlphInContract)} deposit to deploy a new contract"
          Left(failed(error))
        }
      case None => Right(minimalAlphInContract)
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

  def buildExecuteScriptTx(
      blockFlow: BlockFlow,
      query: BuildExecuteScriptTx
  ): Try[BuildExecuteScriptTxResult] = {
    for {
      amounts <- BuildTxCommon
        .getAlphAndTokenAmounts(query.attoAlphAmount, query.tokens)
        .left
        .map(badRequest)
      script <- deserialize[StatefulScript](query.bytecode).left.map(serdeError =>
        badRequest(serdeError.getMessage)
      )
      utx <- unsignedTxFromScript(
        blockFlow,
        script,
        amounts._1.getOrElse(U256.Zero),
        amounts._2,
        query.fromPublicKey,
        query.gasAmount,
        query.gasPrice
      )
    } yield BuildExecuteScriptTxResult.from(utx)
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def compileScript(query: Compile.Script): Try[CompileScriptResult] = {
    Compiler
      .compileTxScriptFull(query.code, compilerOptions = query.getLangCompilerOptions())
      .map(CompileScriptResult.from)
      .left
      .map(error => failed(error.toString))
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def compileContract(query: Compile.Contract): Try[CompileContractResult] = {
    Compiler
      .compileContractFull(query.code, compilerOptions = query.getLangCompilerOptions())
      .map(CompileContractResult.from)
      .left
      .map(error => failed(error.toString))
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def compileProject(query: Compile.Project): Try[CompileProjectResult] = {
    Compiler
      .compileProject(query.code, compilerOptions = query.getLangCompilerOptions())
      .map(p => CompileProjectResult.from(p._1, p._2))
      .left
      .map(error => failed(error.toString))
  }

  def getContractState(
      blockFlow: BlockFlow,
      address: Address.Contract,
      groupIndex: GroupIndex
  ): Try[ContractState] = {
    for {
      worldState <- wrapResult(blockFlow.getBestCachedWorldState(groupIndex))
      state      <- fetchContractState(worldState, address.contractId)
    } yield state
  }

  def callContract(blockFlow: BlockFlow, params: CallContract): Try[CallContractResult] = {
    for {
      groupIndex <- params.validate()
      _          <- checkGroup(groupIndex)
      blockHash = params.worldStateBlockHash.getOrElse(
        blockFlow.getBestDeps(groupIndex).uncleHash(groupIndex)
      )
      worldState <- wrapResult(
        blockFlow.getPersistedWorldState(blockHash).map(_.cached().staging())
      )
      contractId = params.address.contractId
      contractObj <- wrapResult(worldState.getContractObj(contractId))
      method      <- wrapExeResult(contractObj.code.getMethod(params.methodIndex))
      txId = params.txId.getOrElse(TransactionId.random)
      resultPair <- executeContractMethod(
        worldState,
        groupIndex,
        contractId,
        txId,
        blockHash,
        params.inputAssets.getOrElse(AVector.empty),
        params.methodIndex,
        params.args.getOrElse(AVector.empty),
        method
      )
      (returns, result) = resultPair
      contractAddresses = params.existingContracts.getOrElse(
        AVector.empty
      ) :+ params.address
      contractsState <- contractAddresses.mapE(address =>
        fetchContractState(worldState, address.contractId)
      )
    } yield {
      CallContractResult(
        returns.map(Val.from),
        maximalGasPerTx.subUnsafe(result.gasBox).value,
        contractsState,
        result.contractPrevOutputs.map(_.lockupScript).map(Address.from),
        result.generatedOutputs.mapWithIndex { case (output, index) =>
          Output.from(output, txId, index)
        },
        fetchContractEvents(worldState)
      )
    }
  }

  def runTestContract(
      blockFlow: BlockFlow,
      testContract: TestContract.Complete
  ): Try[TestContractResult] = {
    val contractId = testContract.contractId
    for {
      groupIndex <- testContract.groupIndex
      worldState <- wrapResult(blockFlow.getBestCachedWorldState(groupIndex).map(_.staging()))
      _          <- testContract.existingContracts.foreachE(createContract(worldState, _))
      _          <- createContract(worldState, contractId, testContract)
      method     <- wrapExeResult(testContract.code.getMethod(testContract.testMethodIndex))
      executionResultPair <- executeContractMethod(
        worldState,
        groupIndex,
        contractId,
        testContract.txId,
        testContract.blockHash,
        testContract.inputAssets,
        testContract.testMethodIndex,
        testContract.testArgs,
        method
      )
      events = fetchContractEvents(worldState)
      contractIds <- getCreatedAndDestroyedContractIds(events)
      postState   <- fetchContractsState(worldState, testContract, contractIds._1, contractIds._2)
      eventsSplit <- extractDebugMessages(events)
    } yield {
      val executionOutputs = executionResultPair._1
      val executionResult  = executionResultPair._2
      val gasUsed          = maximalGasPerTx.subUnsafe(executionResult.gasBox)
      logger.info("\n" + showDebugMessages(eventsSplit._2))
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
        event.contractAddress match {
          case Address.Contract(LockupScript.P2C(vm.createContractEventId)) =>
            event.getContractId() match {
              case Some(contractId) => Right((createdIds :+ contractId, destroyedIds))
              case None             => Left(failed(s"invalid create contract event $event"))
            }
          case Address.Contract(LockupScript.P2C(vm.destroyContractEventId)) =>
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
    for {
      existingContractsState <- contractIds.mapE(id => fetchContractState(worldState, id))
      testContractState <- fetchContractState(
        worldState,
        testContract.contractId
      )
    } yield {
      val codeHash = testContract.codeHash(testContractState.codeHash)
      val states = existingContractsState ++ AVector(
        testContractState.copy(codeHash = codeHash)
      )
      (states, codeHash)
    }
  }

  private def fetchContractEvents(worldState: WorldState.Staging): AVector[ContractEventByTxId] = {
    val allLogStates = worldState.logState.getNewLogs()
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
      contract       <- code.toContract().left.map(IOError.Serde)
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
      txId: TransactionId,
      blockHash: BlockHash,
      inputAssets: AVector[TestInputAsset],
      methodIndex: Int,
      args: AVector[Val],
      method: Method[StatefulContext]
  ): Try[(AVector[vm.Val], StatefulVM.TxScriptExecution)] = {
    val blockEnv = BlockEnv(
      ChainIndex(groupIndex, groupIndex),
      networkConfig.networkId,
      TimeStamp.now(),
      consensusConfig.maxMiningTarget,
      Some(blockHash)
    )
    val testGasFee = nonCoinbaseMinGasPrice * maximalGasPerTx
    val txEnv: TxEnv = TxEnv.mockup(
      txId = txId,
      signatures = Stack.popOnly(AVector.empty[Signature]),
      prevOutputs = inputAssets.map(_.toAssetOutput),
      fixedOutputs = AVector.empty[AssetOutput],
      gasPrice = nonCoinbaseMinGasPrice,
      gasAmount = maximalGasPerTx,
      isEntryMethodPayable = true
    )
    val context = StatefulContext(blockEnv, txEnv, worldState, maximalGasPerTx)
    val script = StatefulScript.unsafe(
      AVector(
        Method[StatefulContext](
          isPublic = true,
          usePreapprovedAssets = inputAssets.nonEmpty,
          useContractAssets = false,
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
    for {
      _      <- checkArgs(args, method)
      result <- runWithDebugError(context, script)
    } yield result
  }
  // scalastyle:on method.length parameter.number

  def checkArgs(args: AVector[Val], method: Method[StatefulContext]): Try[Unit] = {
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

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def runWithDebugError(
      context: StatefulContext,
      script: StatefulScript
  ): Try[(AVector[vm.Val], StatefulVM.TxScriptExecution)] = {
    StatefulVM.runTxScriptWithOutputs(context, script) match {
      case Right(result)         => Right(result)
      case Left(Left(ioFailure)) => Left(failedInIO(ioFailure.error))
      case Left(Right(exeFailure)) =>
        val errorString = s"VM execution error: ${exeFailure.toString()}"
        val events      = fetchContractEvents(context.worldState)
        extractDebugMessages(events).flatMap { case (_, debugMessages) =>
          val detail = showDebugMessages(debugMessages) ++ errorString
          logger.info("\n" + detail)
          Left(failed(detail))
        }
    }
  }

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
      existingContract: ContractState
  ): Try[Unit] = {
    createContract(
      worldState,
      existingContract.id,
      existingContract.bytecode,
      toVmVal(existingContract.immFields),
      toVmVal(existingContract.mutFields),
      existingContract.asset
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
      testContract.initialAsset
    )
  }

  def createContract(
      worldState: WorldState.Staging,
      contractId: ContractId,
      code: StatefulContract,
      initialImmState: AVector[vm.Val],
      initialMutState: AVector[vm.Val],
      asset: AssetState
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
        output
      )
    )
  }
}

object ServerUtils {

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
      Left(ApiError.BadRequest(s"Too much gas fee, cap at ${apiConfig.gasFeeCap}, got $gasFee"))
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

  def buildDeployContractTx(
      codeRaw: String,
      address: Address,
      _immFields: Option[String],
      _mutFields: Option[String],
      initialAttoAlphAmount: U256,
      initialTokenAmounts: AVector[(TokenId, U256)],
      newTokenAmount: Option[U256]
  ): Try[StatefulScript] = {
    for {
      immFields <- parseState(_immFields)
      mutFields <- parseState(_mutFields)
      script <- buildDeployContractScriptWithParsedState(
        codeRaw,
        address,
        immFields,
        mutFields,
        initialAttoAlphAmount,
        initialTokenAmounts,
        newTokenAmount
      )
    } yield script
  }

  def buildDeployContractTxWithParsedState(
      contract: StatefulContract,
      address: Address,
      initialImmFields: AVector[vm.Val],
      initialMutFields: AVector[vm.Val],
      initialAttoAlphAmount: U256,
      initialTokenAmounts: AVector[(TokenId, U256)],
      newTokenAmount: Option[U256]
  ): Try[StatefulScript] = {
    buildDeployContractScriptWithParsedState(
      Hex.toHexString(serialize(contract)),
      address,
      initialImmFields,
      initialMutFields,
      initialAttoAlphAmount,
      initialTokenAmounts,
      newTokenAmount
    )
  }

  def buildDeployContractScriptRawWithParsedState(
      codeRaw: String,
      address: Address,
      initialImmFields: AVector[vm.Val],
      initialMutFields: AVector[vm.Val],
      initialAttoAlphAmount: U256,
      initialTokenAmounts: AVector[(TokenId, U256)],
      newTokenAmount: Option[U256]
  ): String = {
    val immStateRaw = Hex.toHexString(serialize(initialImmFields))
    val mutStateRaw = Hex.toHexString(serialize(initialMutFields))
    def toCreate(approveAssets: String): String = newTokenAmount match {
      case Some(amount) =>
        s"createContractWithToken!$approveAssets(#$codeRaw, #$immStateRaw, #$mutStateRaw, ${amount.v})"
      case None => s"createContract!$approveAssets(#$codeRaw, #$immStateRaw, #$mutStateRaw)"
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
      newTokenAmount: Option[U256]
  ): Try[StatefulScript] = {
    val scriptRaw = buildDeployContractScriptRawWithParsedState(
      codeRaw,
      address,
      initialImmFields,
      initialMutFields,
      initialAttoAlphAmount,
      initialTokenAmounts,
      newTokenAmount
    )

    wrapCompilerResult(Compiler.compileTxScript(scriptRaw))
  }

  def parseState(str: Option[String]): Try[AVector[vm.Val]] = {
    str match {
      case None        => Right(AVector.empty[vm.Val])
      case Some(state) => wrapCompilerResult(Compiler.compileState(state))
    }
  }
}
