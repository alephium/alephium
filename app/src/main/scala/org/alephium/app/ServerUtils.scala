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

import akka.util.{ByteString, Timeout}
import com.typesafe.scalalogging.StrictLogging
import sttp.model.StatusCode

import org.alephium.api.ApiError
import org.alephium.api.model
import org.alephium.api.model._
import org.alephium.flow.core.{BlockFlow, BlockFlowState}
import org.alephium.flow.handler.TxHandler
import org.alephium.io.IOError
import org.alephium.protocol.{BlockHash, Hash, PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.config.{BrokerConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util._

// scalastyle:off number.of.methods
class ServerUtils(implicit
    brokerConfig: BrokerConfig,
    networkConfig: NetworkConfig,
    apiConfig: ApiConfig,
    executionContext: ExecutionContext
) extends StrictLogging {
  import ServerUtils._

  private val defaultUtxosLimit: Int = 5000

  def getBlockflow(blockFlow: BlockFlow, fetchRequest: FetchRequest): Try[FetchResponse] = {
    val entriesEither = for {
      blocks <- blockFlow.getHeightedBlocks(fetchRequest.fromTs, fetchRequest.toTs)
    } yield blocks.map(_.map { case (block, height) =>
      BlockEntry.from(block, height)
    })

    entriesEither match {
      case Right(entries) => Right(FetchResponse(entries))
      case Left(error)    => failed[FetchResponse](error)
    }
  }

  def getBalance(blockFlow: BlockFlow, balanceRequest: GetBalance): Try[Balance] = {
    val utxosLimit = balanceRequest.utxosLimit.getOrElse(defaultUtxosLimit)
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
      address: Address.Asset,
      utxosLimitOpt: Option[Int]
  ): Try[UTXOs] = {
    val utxosLimit = utxosLimitOpt.getOrElse(defaultUtxosLimit)
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
      contractId: Hash,
      groupIndex: GroupIndex
  ): Try[Group] = {
    val searchResult = for {
      worldState <- blockFlow.getBestPersistedWorldState(groupIndex)
      existed    <- worldState.contractState.exist(contractId)
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
    case GetGroup(Address.Contract(LockupScript.P2C(contractId))) => {
      val failure: Try[Group] = Left(failed("Group not found.")).withRight[Group]
      brokerConfig.groupRange.foldLeft(failure) { case (prevResult, currentGroup: Int) =>
        prevResult match {
          case Right(prevResult) => Right(prevResult)
          case Left(_) =>
            getContractGroup(blockFlow, contractId, GroupIndex.unsafe(currentGroup))
        }
      }
    }
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
              .map(Tx.fromTemplate(_))
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
        query.gas,
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
                case None             => Left(ApiError.BadRequest(s"Invalid public key: ${pub.toHexString}"))

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

  def buildSweepAllTransaction(
      blockFlow: BlockFlow,
      query: BuildSweepAllTransaction
  ): Try[BuildTransactionResult] = {
    for {
      _ <- checkGroup(query.fromPublicKey)
      unsignedTx <- prepareUnsignedTransaction(
        blockFlow,
        query.fromPublicKey,
        query.toAddress,
        query.lockTime,
        query.gas,
        query.gasPrice.getOrElse(defaultGasPrice)
      )
    } yield {
      BuildTransactionResult.from(unsignedTx)
    }
  }

  def submitTransaction(txHandler: ActorRefT[TxHandler.Command], tx: TransactionTemplate)(implicit
      askTimeout: Timeout
  ): FutureTry[TxResult] = {
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

  def convert(status: BlockFlowState.TxStatus): TxStatus =
    Confirmed(
      status.index.hash,
      status.index.index,
      status.chainConfirmations,
      status.fromGroupConfirmations,
      status.toGroupConfirmations
    )

  def getTransactionStatus(
      blockFlow: BlockFlow,
      txId: Hash,
      chainIndex: ChainIndex
  ): Try[TxStatus] = {
    for {
      _ <- checkTxChainIndex(chainIndex, txId)
      status <- blockFlow.getTxStatus(txId, chainIndex).left.map(failedInIO).map {
        case Some(status) => convert(status)
        case None         => if (isInMemPool(blockFlow, txId, chainIndex)) MemPooled else NotFound
      }
    } yield status
  }

  def decodeUnsignedTransaction(
      unsignedTx: String
  ): Try[UnsignedTransaction] = {
    for {
      txByteString <- Hex.from(unsignedTx).toRight(badRequest(s"Invalid hex"))
      unsignedTx <- deserialize[UnsignedTransaction](txByteString).left
        .map(serdeError => badRequest(serdeError.getMessage))
      _ <- validateUnsignedTransaction(unsignedTx)
    } yield unsignedTx
  }

  def decodeUnlockScript(
      unlockScript: String
  ): Try[UnlockScript] = {
    Hex.from(unlockScript).toRight(badRequest(s"Invalid hex")).flatMap { unlockScriptBytes =>
      deserialize[UnlockScript](unlockScriptBytes).left
        .map(serdeError => badRequest(serdeError.getMessage))
    }
  }

  def isInMemPool(blockFlow: BlockFlow, txId: Hash, chainIndex: ChainIndex): Boolean = {
    blockFlow.getMemPool(chainIndex).contains(chainIndex, txId)
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

  private def publishTx(txHandler: ActorRefT[TxHandler.Command], tx: TransactionTemplate)(implicit
      askTimeout: Timeout
  ): FutureTry[TxResult] = {
    val message = TxHandler.AddToGrandPool(AVector(tx))
    txHandler.ask(message).mapTo[TxHandler.Event].map {
      case _: TxHandler.AddSucceeded =>
        Right(TxResult(tx.id, tx.fromGroup.value, tx.toGroup.value))
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
            (token.id -> token.amount)
          }
        case None =>
          AVector.empty[(TokenId, U256)]
      }

      TxOutputInfo(
        destination.address.lockupScript,
        destination.amount.value,
        tokensInfo,
        destination.lockTime
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
        blockFlow.transfer(fromPublicKey, outputInfos, gasOpt, gasPrice)
    }

    transferResult match {
      case Right(Right(unsignedTransaction)) => validateUnsignedTransaction(unsignedTransaction)
      case Right(Left(error))                => Left(failed(error))
      case Left(error)                       => failed(error)
    }
  }

  def prepareUnsignedTransaction(
      blockFlow: BlockFlow,
      fromPublicKey: PublicKey,
      toAddress: Address.Asset,
      lockTimeOpt: Option[TimeStamp],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): Try[UnsignedTransaction] = {
    blockFlow.sweepAll(fromPublicKey, toAddress.lockupScript, lockTimeOpt, gasOpt, gasPrice) match {
      case Right(Right(unsignedTransaction)) => validateUnsignedTransaction(unsignedTransaction)
      case Right(Left(error))                => Left(failed(error))
      case Left(error)                       => failed(error)
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

    blockFlow.transfer(fromLockupScript, fromUnlockScript, outputInfos, gasOpt, gasPrice) match {
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

  def checkTxChainIndex(chainIndex: ChainIndex, tx: Hash): Try[Unit] = {
    if (brokerConfig.contains(chainIndex.from)) {
      Right(())
    } else {
      Left(badRequest(s"${tx.toHexString} belongs to other groups"))
    }
  }

  def execute(f: => Unit): FutureTry[Boolean] =
    Future {
      f
      Right(true)
    }

  private def parseState(str: Option[String]): Either[Compiler.Error, AVector[vm.Val]] = {
    str match {
      case None => Right(AVector.empty[vm.Val])
      case Some(state) =>
        val res = Compiler.compileState(state)
        res
    }
  }

  def buildMultisigAddress(
      keys: AVector[PublicKey],
      mrequired: Int
  ): Either[String, BuildMultisigAddress.Result] = {
    LockupScript.p2mpkh(keys, mrequired) match {
      case Some(lockupScript) =>
        Right(
          BuildMultisigAddress.Result(
            Address.Asset(lockupScript)
          )
        )
      case None => Left(s"Invalid m-of-n multisig")
    }
  }

  private def buildContract(
      codeRaw: String,
      address: Address,
      initialState: AVector[vm.Val],
      alfAmount: U256,
      newTokenAmount: Option[U256]
  ): Either[Compiler.Error, StatefulScript] = {

    val stateRaw = Hex.toHexString(serialize(initialState))
    val creation = newTokenAmount match {
      case Some(amount) => s"createContractWithToken!(#$codeRaw, #$stateRaw, ${amount.v})"
      case None         => s"createContract!(#$codeRaw, #$stateRaw)"
    }

    val scriptRaw = s"""
      |TxScript Main {
      |  pub payable fn main() -> () {
      |    approveAlf!(@${address.toBase58}, ${alfAmount.v})
      |    $creation
      |  }
      |}
      |""".stripMargin
    Compiler.compileTxScript(scriptRaw)
  }

  private def unsignedTxFromScript(
      blockFlow: BlockFlow,
      script: StatefulScript,
      fromPublicKey: PublicKey,
      gas: Option[GasBox],
      gasPrice: Option[GasPrice]
  ): Try[UnsignedTransaction] = {
    val lockupScript = LockupScript.p2pkh(fromPublicKey)
    val unlockScript = UnlockScript.p2pkh(fromPublicKey)
    (for {
      balances <- blockFlow.getUsableUtxos(lockupScript).left.map(e => failedInIO(e))
    } yield {
      val inputs = balances.map(_.ref).map(TxInput(_, unlockScript))
      UnsignedTransaction(Some(script), inputs, AVector.empty).copy(
        gasAmount = gas.getOrElse(minimalGas),
        gasPrice = gasPrice.getOrElse(defaultGasPrice)
      )
    }).flatMap(validateUnsignedTransaction)
  }

  private def validateStateLength(
      contract: StatefulContract,
      state: AVector[vm.Val]
  ): Either[String, Unit] = {
    if (contract.validate(state)) {
      Right(())
    } else {
      Left(s"Invalid state length, expect ${contract.fieldLength}, have ${state.length}")
    }
  }

  @inline private def decodeCodeHexString(str: String): Try[ByteString] =
    Hex.from(str).toRight(badRequest("Cannot decode code hex string"))

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def buildContract(
      blockFlow: BlockFlow,
      query: BuildContract
  ): Try[BuildContractResult] = {
    for {
      codeByteString <- decodeCodeHexString(query.code)
      contract <- deserialize[StatefulContract](codeByteString).left.map(serdeError =>
        badRequest(serdeError.getMessage)
      )
      state <- parseState(query.state).left.map(error => badRequest(error.message))
      _     <- validateStateLength(contract, state).left.map(badRequest)
      address = Address.p2pkh(query.fromPublicKey)
      script <- buildContract(
        query.code,
        address,
        state,
        dustUtxoAmount,
        query.issueTokenAmount.map(_.value)
      ).left.map(error => badRequest(error.message))
      utx <- unsignedTxFromScript(
        blockFlow,
        script,
        query.fromPublicKey,
        query.gas,
        query.gasPrice
      )
    } yield BuildContractResult.from(utx)
  }

  def verifySignature(query: VerifySignature): Try[Boolean] = {
    Right(SignatureSchema.verify(query.data, query.signature, query.publicKey))
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def buildScript(blockFlow: BlockFlow, query: BuildScript): Try[BuildScriptResult] = {
    for {
      codeByteString <- decodeCodeHexString(query.code)
      script <- deserialize[StatefulScript](codeByteString).left.map(serdeError =>
        badRequest(serdeError.getMessage)
      )
      utx <- unsignedTxFromScript(
        blockFlow,
        script,
        query.fromPublicKey,
        query.gas,
        query.gasPrice
      )
    } yield BuildScriptResult.from(utx)
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def compileScript(query: Compile.Script): Try[CompileResult] = {
    Compiler
      .compileTxScript(query.code)
      .map(script => CompileResult(Hex.toHexString(serialize(script))))
      .left
      .map(error => failed(error.toString))
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def compileContract(query: Compile.Contract): Try[CompileResult] = {
    Compiler
      .compileContract(query.code)
      .map(contract => CompileResult(Hex.toHexString(serialize(contract))))
      .left
      .map(error => failed(error.toString))
  }

  def getContractState(
      blockFlow: BlockFlow,
      address: Address.Contract,
      groupIndex: GroupIndex
  ): Try[ContractStateResult] = {
    val result = for {
      worldState <- blockFlow.getBestCachedWorldState(groupIndex)
      state      <- worldState.getContractState(address.lockupScript.contractId)
    } yield {
      val convertedFields = state.fields.map(model.Val.from)
      ContractStateResult(convertedFields)
    }
    result.left.map(failedInIO(_))
  }
  private def badRequest(error: String): ApiError[_ <: StatusCode] = ApiError.BadRequest(error)
  private def failed(error: String): ApiError[_ <: StatusCode] =
    ApiError.InternalServerError(error)
  private val failedInIO: ApiError[_ <: StatusCode] = ApiError.InternalServerError("Failed in IO")
  private def failedInIO(error: IOError): ApiError[_ <: StatusCode] =
    ApiError.InternalServerError(s"Failed in IO: $error")
  private def failed[T](error: IOError): Try[T] = Left(failedInIO(error))

}

object ServerUtils {
  type Try[T]       = Either[ApiError[_ <: StatusCode], T]
  type FutureTry[T] = Future[Try[T]]

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

}
