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
import org.alephium.api.model.{AssetOutput => _, TransactionTemplate => _, _}
import org.alephium.flow.core.{BlockFlow, BlockFlowState, UtxoSelectionAlgo}
import org.alephium.flow.core.UtxoSelectionAlgo._
import org.alephium.flow.gasestimation._
import org.alephium.flow.handler.TxHandler
import org.alephium.io.IOError
import org.alephium.protocol.{vm, BlockHash, Hash, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.config._
import org.alephium.protocol.model._
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.{failed => _, ContractState => _, Val => _, _}
import org.alephium.protocol.vm.lang.Compiler
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

  def getBlockflow(blockFlow: BlockFlow, timeInterval: TimeInterval): Try[FetchResponse] = {
    getHeightedBlocks(blockFlow, timeInterval).map { heightedBlocks =>
      FetchResponse(heightedBlocks.map(_._2.map { case (block, height) =>
        BlockEntry.from(block, height)
      }))
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

  def getBalance(blockFlow: BlockFlow, balanceRequest: GetBalance): Try[Balance] = {
    val utxosLimit = apiConfig.defaultUtxosLimit
    for {
      _ <- checkGroup(balanceRequest.address.lockupScript)
      balance <- blockFlow
        .getBalance(
          balanceRequest.address.lockupScript,
          utxosLimit
        )
        .map(Balance.from(_, utxosLimit))
        .left
        .flatMap(failed)
    } yield balance
  }

  def getUTXOsIncludePool(
      blockFlow: BlockFlow,
      address: Address.Asset
  ): Try[UTXOs] = {
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

  def listUnconfirmedTransactions(
      blockFlow: BlockFlow
  ): Try[AVector[UnconfirmedTransactions]] = {
    Right(
      brokerConfig.chainIndexes
        .map { chainIndex =>
          UnconfirmedTransactions(
            chainIndex.from.value,
            chainIndex.to.value,
            blockFlow
              .getMemPool(chainIndex)
              .getAll(chainIndex)
              .map(model.TransactionTemplate.fromProtocol(_))
          )
        }
        .filter(_.unconfirmedTransactions.nonEmpty)
    )
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
        query.gasPrice.getOrElse(defaultGasPrice)
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
        query.gasPrice.getOrElse(defaultGasPrice)
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
        query.gasPrice.getOrElse(defaultGasPrice)
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
        MemPooled
      case None =>
        TxNotFound
    }
  }

  def getTransactionStatus(
      blockFlow: BlockFlow,
      txId: Hash,
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

  def isInMemPool(blockFlow: BlockFlow, txId: Hash, chainIndex: ChainIndex): Boolean = {
    blockFlow.getMemPool(chainIndex).contains(chainIndex, txId)
  }

  def getEventsForContractCurrentCount(
      blockFlow: BlockFlow,
      contractAddress: Address.Contract
  ): Try[Int] = {
    val contractId = contractAddress.lockupScript.contractId
    for {
      groupIndex <- blockFlow.getGroupForContract(contractId).left.map(failed)
      chainIndex = ChainIndex(groupIndex, groupIndex)
      countOpt <- wrapResult(blockFlow.getEventsCurrentCount(chainIndex, contractId))
      count    <- countOpt.toRight(notFound(s"Current events count for contract $contractAddress"))
    } yield count
  }

  def getBlock(blockFlow: BlockFlow, query: GetBlock): Try[BlockEntry] =
    for {
      _ <- checkHashChainIndex(query.hash)
      block <- blockFlow
        .getBlock(query.hash)
        .left
        .map(_ => failed(s"Fail fetching block with header ${query.hash.toHexString}"))
      height <- blockFlow
        .getHeight(block.header)
        .left
        .map(failedInIO)
    } yield BlockEntry.from(block, height)

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
        .map(_ => failedInIO)
    } yield HashesAtHeight(hashes)

  def getChainInfo(blockFlow: BlockFlow, chainIndex: ChainIndex): Try[ChainInfo] =
    for {
      maxHeight <- blockFlow
        .getMaxHeight(chainIndex)
        .left
        .map(_ => failedInIO)
    } yield ChainInfo(maxHeight)

  def searchLocalTransactionStatus(
      blockFlow: BlockFlow,
      txId: Hash,
      chainIndexes: AVector[ChainIndex]
  ): Try[TxStatus] = {
    blockFlow.searchLocalTransactionStatus(txId, chainIndexes).left.map(failed).map(convert)
  }

  def getChainIndexForTx(
      blockFlow: BlockFlow,
      txId: Hash
  ): Try[ChainIndex] = {
    searchLocalTransactionStatus(blockFlow, txId, brokerConfig.chainIndexes) match {
      case Right(Confirmed(blockHash, _, _, _, _)) =>
        Right(ChainIndex.from(blockHash))
      case Right(TxNotFound) =>
        Left(notFound(s"Transaction ${txId.toHexString}"))
      case Right(MemPooled) =>
        Left(failed(s"Transaction ${txId.toHexString} still in mempool"))
      case Left(error) =>
        Left(error)
    }
  }

  def getEventsByTxId(
      blockFlow: BlockFlow,
      txId: Hash
  ): Try[ContractEventsByTxId] = {
    wrapResult(
      for {
        result <- blockFlow.getEvents(txId, 0, CounterRange.MaxCounterRange)
        (nextStart, logStatesVec) = result
        events <- logStatesVec.flatMapE { logStates =>
          logStates.states
            .mapE { state =>
              if (state.isRef) {
                LogStateRef
                  .fromFields(state.fields)
                  .toRight(IOError.Other(new Throwable(s"Invalid state ref: ${state.fields}")))
                  .flatMap(blockFlow.getEventByRef(_))
                  .map(p => ContractEventByTxId.from(p._1, p._2, p._3))
              } else {
                Right(
                  ContractEventByTxId(
                    logStates.blockHash,
                    Address.contract(logStates.eventKey),
                    state.index.toInt,
                    state.fields.map(Val.from)
                  )
                )
              }
            }
        }
      } yield {
        ContractEventsByTxId(events, nextStart)
      }
    )
  }

  def getEventsByContractId(
      blockFlow: BlockFlow,
      start: Int,
      endOpt: Option[Int],
      contractId: ContractId
  ): Try[ContractEvents] = {
    wrapResult(
      blockFlow
        .getEvents(
          contractId,
          start,
          endOpt.getOrElse(start + CounterRange.MaxCounterRange)
        )
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
    val message = TxHandler.AddToGrandPool(AVector(tx))
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
        destination.alphAmount.value,
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
      gasPrice: GasPrice
  ): Try[UnsignedTransaction] = {
    val outputInfos = prepareOutputInfos(destinations)

    val transferResult = outputRefsOpt match {
      case Some(outputRefs) =>
        val allAssetType = outputRefs.forall(outputRef => Hint.unsafe(outputRef.hint).isAssetType)
        if (allAssetType) {
          val assetOutputRefs = outputRefs.map(_.unsafeToAssetOutputRef())
          blockFlow.transfer(fromPublicKey, assetOutputRefs, outputInfos, gasOpt, gasPrice)
        } else {
          Right(Left("Selected UTXOs must be of asset type"))
        }
      case None =>
        blockFlow.transfer(
          fromPublicKey,
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
      gasPrice: GasPrice
  ): Try[AVector[UnsignedTransaction]] = {
    blockFlow.sweepAddress(
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
      gasPrice: GasPrice
  ): Try[UnsignedTransaction] = {
    val outputInfos = prepareOutputInfos(destinations)

    blockFlow.transfer(
      fromLockupScript,
      fromUnlockScript,
      outputInfos,
      gasOpt,
      gasPrice,
      apiConfig.defaultUtxosLimit
    ) match {
      case Right(Right(unsignedTransaction)) => validateUnsignedTransaction(unsignedTransaction)
      case Right(Left(error))                => Left(failed(error))
      case Left(error)                       => failed(error)
    }
  }

  def checkGroup(lockupScript: LockupScript.Asset): Try[Unit] = {
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
        .Build(dustUtxoAmount, ProvidedGas(gas, gasPrice.getOrElse(defaultGasPrice)))
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
            gasPrice = gasPrice.getOrElse(defaultGasPrice)
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
      initialAlphAmount <- getInitialAlphAmount(query.initialAlphAmount)
      code              <- BuildDeployContractTx.decode(query.bytecode)
      address = Address.p2pkh(query.fromPublicKey)
      script <- buildDeployContractTxWithParsedState(
        code.contract,
        address,
        code.initialFields,
        initialAlphAmount,
        query.initialTokenAmounts,
        query.issueTokenAmount.map(_.value)
      )
      utx <- unsignedTxFromScript(
        blockFlow,
        script,
        initialAlphAmount,
        AVector.empty,
        query.fromPublicKey,
        query.gasAmount,
        query.gasPrice
      )
    } yield BuildDeployContractTxResult.from(utx)
  }

  def getInitialAlphAmount(amountOption: Option[Amount]): Try[U256] = {
    amountOption match {
      case Some(amount) =>
        if (amount.value >= minimalAlphInContract) { Right(amount.value) }
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
    values.fold(AVector.ofSize[vm.Val](values.length)) {
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
    val alphAmount = query.alphAmount.map(_.value).getOrElse(U256.Zero)
    val tokens     = query.tokens.getOrElse(AVector.empty).map(token => (token.id, token.amount))
    for {
      script <- deserialize[StatefulScript](query.bytecode).left.map(serdeError =>
        badRequest(serdeError.getMessage)
      )
      utx <- unsignedTxFromScript(
        blockFlow,
        script,
        alphAmount,
        tokens,
        query.fromPublicKey,
        query.gasAmount,
        query.gasPrice
      )
    } yield BuildExecuteScriptTxResult.from(utx)
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def compileScript(query: Compile.Script): Try[CompileScriptResult] = {
    Compiler
      .compileTxScriptFull(query.code)
      .map(p => CompileScriptResult.from(p._1, p._2))
      .left
      .map(error => failed(error.toString))
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def compileContract(query: Compile.Contract): Try[CompileContractResult] = {
    Compiler
      .compileContractFull(query.code)
      .map(p => CompileContractResult.from(p._1, p._2))
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
      returnLength <- wrapExeResult(
        testContract.code
          .getMethod(testContract.testMethodIndex)
          .map(_.returnLength)
      )
      executionResultPair <- executeTestContract(worldState, contractId, testContract, returnLength)
      postState           <- fetchContractsState(worldState, testContract)
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
          Output.from(output, Hash.zero, index)
        },
        events = fetchContractEvents(worldState)
      )
    }
  }

  private def fetchContractsState(
      worldState: WorldState.Staging,
      testContract: TestContract.Complete
  ): Try[(AVector[ContractState], Hash)] = {
    for {
      existingContractsState <- testContract.existingContracts.mapE(contract =>
        fetchContractState(worldState, contract.id)
      )
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

  private def fetchContractEvents(
      worldState: WorldState.Staging
  ): AVector[ContractEventByTxId] = {
    val allLogStates = worldState.logState.getNewLogs()
    allLogStates.flatMap(logStates =>
      logStates.states.flatMap(state =>
        if (state.isRef) {
          AVector.empty
        } else {
          AVector(
            ContractEventByTxId(
              logStates.blockHash,
              Address.contract(logStates.eventKey),
              state.index.toInt,
              state.fields.map(Val.from)
            )
          )
        }
      )
    )
  }

  private def fetchContractState(
      worldState: WorldState.AbstractCached,
      contractId: ContractId
  ): Try[ContractState] = {
    val result = for {
      state          <- worldState.getContractState(contractId)
      codeRecord     <- worldState.getContractCode(state.codeHash)
      contract       <- codeRecord.code.toContract().left.map(IOError.Serde)
      contractOutput <- worldState.getContractAsset(state.contractOutputRef)
    } yield ContractState(
      Address.contract(contractId),
      contract,
      contract.hash,
      Some(state.initialStateHash),
      state.fields.map(Val.from),
      AssetState.from(contractOutput)
    )
    wrapResult(result)
  }

  private def executeTestContract(
      worldState: WorldState.Staging,
      contractId: ContractId,
      testContract: TestContract.Complete,
      returnLength: Int
  ): Try[(AVector[vm.Val], StatefulVM.TxScriptExecution)] = {
    val blockEnv = BlockEnv(
      networkConfig.networkId,
      TimeStamp.now(),
      consensusConfig.maxMiningTarget,
      Some(testContract.blockHash)
    )
    val testGasFee = defaultGasPrice * maximalGasPerTx
    val txEnv: TxEnv = TxEnv.mockup(
      txId = testContract.txId,
      signatures = Stack.popOnly(AVector.empty[Signature]),
      prevOutputs = testContract.inputAssets.map(_.toAssetOutput),
      fixedOutputs = AVector.empty[AssetOutput],
      gasFeeUnsafe = testGasFee,
      isEntryMethodPayable = true
    )
    val context = StatefulContext(blockEnv, txEnv, worldState, maximalGasPerTx)
    val script = StatefulScript.unsafe(
      AVector(
        Method[StatefulContext](
          isPublic = true,
          usePreapprovedAssets = testContract.inputAssets.nonEmpty,
          useContractAssets = false,
          argsLength = 0,
          localsLength = 0,
          returnLength = returnLength,
          instrs = approveAsset(testContract, testGasFee) ++ callExternal(testContract, contractId)
        )
      )
    )
    wrapExeResult(StatefulVM.runTxScriptWithOutputs(context, script))
  }

  private def approveAsset(
      testContract: TestContract.Complete,
      gasFee: U256
  ): AVector[Instr[StatefulContext]] = {
    testContract.inputAssets.flatMapWithIndex { (asset, index) =>
      val gasFeeOpt = if (index == 0) Some(gasFee) else None
      asset.approveAll(gasFeeOpt)
    }
  }

  private def callExternal(
      testContract: TestContract.Complete,
      contractId: ContractId
  ): AVector[Instr[StatefulContext]] = {
    toVmVal(testContract.testArgs).map(_.toConstInstr: Instr[StatefulContext]) ++
      AVector[Instr[StatefulContext]](
        BytesConst(vm.Val.ByteVec(contractId.bytes)),
        CallExternal(testContract.testMethodIndex.toByte)
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
      toVmVal(existingContract.fields),
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
      toVmVal(testContract.initialFields),
      testContract.initialAsset
    )
  }

  def createContract(
      worldState: WorldState.Staging,
      contractId: ContractId,
      code: StatefulContract,
      initialState: AVector[vm.Val],
      asset: AssetState
  ): Try[Unit] = {
    val outputHint = Hint.ofContract(LockupScript.p2c(contractId).scriptHint)
    val outputRef  = ContractOutputRef.unsafe(outputHint, contractId)
    val output     = asset.toContractOutput(contractId)
    wrapResult(
      worldState.createContractUnsafe(
        code.toHalfDecoded(),
        initialState,
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
      initialState: Option[String],
      initialAlphAmount: U256,
      initialTokenAmounts: AVector[Token],
      newTokenAmount: Option[U256]
  ): Try[StatefulScript] = {
    parseState(initialState).flatMap { state =>
      buildDeployContractScriptWithParsedState(
        codeRaw,
        address,
        state,
        initialAlphAmount,
        initialTokenAmounts,
        newTokenAmount
      )
    }
  }

  def buildDeployContractTxWithParsedState(
      contract: StatefulContract,
      address: Address,
      initialFields: AVector[vm.Val],
      initialAlphAmount: U256,
      initialTokenAmounts: AVector[Token],
      newTokenAmount: Option[U256]
  ): Try[StatefulScript] = {
    buildDeployContractScriptWithParsedState(
      Hex.toHexString(serialize(contract)),
      address,
      initialFields,
      initialAlphAmount,
      initialTokenAmounts,
      newTokenAmount
    )
  }

  def buildDeployContractScriptRawWithParsedState(
      codeRaw: String,
      address: Address,
      initialFields: AVector[vm.Val],
      initialAlphAmount: U256,
      initialTokenAmounts: AVector[Token],
      newTokenAmount: Option[U256]
  ): String = {
    val stateRaw    = Hex.toHexString(serialize(initialFields))
    val approveAlph = s"approveAlph!(@${address.toBase58}, ${initialAlphAmount.v})"
    val creation = newTokenAmount match {
      case Some(amount) => s"createContractWithToken!(#$codeRaw, #$stateRaw, ${amount.v})"
      case None         => s"createContract!(#$codeRaw, #$stateRaw)"
    }

    if (initialTokenAmounts.isEmpty) {
      s"""
         |TxScript Main {
         |  $approveAlph
         |  $creation
         |}
         |""".stripMargin
    } else {
      val approveTokenStmts = initialTokenAmounts
        .map { token =>
          s"approveToken!(@${address.toBase58}, #${token.id.toHexString}, ${token.amount.v})"
        }
        .mkString(s"${System.lineSeparator()}  ")

      s"""
         |TxScript Main {
         |  $approveAlph
         |  $approveTokenStmts
         |  $creation
         |}
         |""".stripMargin
    }
  }

  def buildDeployContractScriptWithParsedState(
      codeRaw: String,
      address: Address,
      initialFields: AVector[vm.Val],
      initialAlphAmount: U256,
      initialTokenAmounts: AVector[Token],
      newTokenAmount: Option[U256]
  ): Try[StatefulScript] = {
    val scriptRaw = buildDeployContractScriptRawWithParsedState(
      codeRaw,
      address,
      initialFields,
      initialAlphAmount,
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
