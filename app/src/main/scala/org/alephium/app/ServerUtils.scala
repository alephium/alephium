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
import sttp.model.StatusCode

import org.alephium.api.ApiError
import org.alephium.api.model._
import org.alephium.flow.core.{BlockFlow, BlockFlowState}
import org.alephium.flow.handler.TxHandler
import org.alephium.flow.model.DataOrigin
import org.alephium.io.IOError
import org.alephium.protocol.{BlockHash, Hash, PublicKey}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util._

// scalastyle:off number.of.methods
class ServerUtils(networkType: NetworkType)(implicit
    brokerConfig: BrokerConfig,
    executionContext: ExecutionContext
) {
  import ServerUtils._

  def getBlockflow(blockFlow: BlockFlow, fetchRequest: FetchRequest): Try[FetchResponse] = {
    val entriesEither = for {
      blocks <- blockFlow.getHeightedBlocks(fetchRequest.fromTs, fetchRequest.toTs)
    } yield blocks.map(_.map { case (block, height) =>
      BlockEntry.from(block, height, networkType)
    })

    entriesEither match {
      case Right(entries) => Right(FetchResponse(entries))
      case Left(error)    => failed[FetchResponse](error)
    }
  }

  def getBalance(blockFlow: BlockFlow, balanceRequest: GetBalance): Try[Balance] =
    for {
      _ <- checkGroup(balanceRequest.address.lockupScript)
      balance <- blockFlow
        .getBalance(balanceRequest.address.lockupScript)
        .map(Balance(_))
        .left
        .flatMap(failed)
    } yield balance

  def getGroup(query: GetGroup): Try[Group] = {
    Right(Group(query.address.groupIndex(brokerConfig).value))
  }

  def listUnconfirmedTransactions(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex
  ): Try[AVector[Tx]] = {
    Right(
      blockFlow
        .getMemPool(chainIndex)
        .getAll(chainIndex)
        .map(Tx.fromTemplate(_, networkType))
    )
  }

  def buildTransaction(
      blockFlow: BlockFlow,
      query: BuildTransaction
  ): Try[BuildTransactionResult] = {
    val resultEither = for {
      _ <- checkGroup(query.fromPublicKey)
      unsignedTx <- prepareUnsignedTransaction(
        blockFlow,
        query.fromPublicKey,
        query.destinations,
        query.gas,
        query.gasPrice.getOrElse(defaultGasPrice)
      )
    } yield {
      BuildTransactionResult.from(unsignedTx)
    }
    resultEither match {
      case Right(result) => Right(result)
      case Left(error)   => Left(error)
    }
  }

  def buildSweepAllTransaction(
      blockFlow: BlockFlow,
      query: BuildSweepAllTransaction
  ): Try[BuildTransactionResult] = {
    val resultEither = for {
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
    resultEither match {
      case Right(result) => Right(result)
      case Left(error)   => Left(error)
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
      TransactionTemplate(
        unsignedTx,
        AVector.fill(unsignedTx.inputs.length)(query.signature),
        contractSignatures = AVector.empty
      )
    }
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
    Hex.from(unsignedTx).toRight(badRequest(s"Invalid hex")).flatMap { txByteString =>
      deserialize[UnsignedTransaction](txByteString).left
        .map(serdeError => badRequest(serdeError.getMessage))
    }
  }

  def validateUnsignedTransaction(
      unsignedTx: UnsignedTransaction
  ): Try[Unit] = {
    if (unsignedTx.inputs.nonEmpty) {
      Right(())
    } else {
      Left(ApiError.BadRequest("Invalid transaction: empty inputs"))
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
    } yield BlockEntry.from(block, height, networkType)

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
    val message = TxHandler.AddToGrandPool(AVector(tx), DataOrigin.Local)
    txHandler.ask(message).mapTo[TxHandler.Event].map {
      case _: TxHandler.AddSucceeded =>
        Right(TxResult(tx.id, tx.fromGroup.value, tx.toGroup.value))
      case _: TxHandler.AddFailed =>
        Left(failed("Failed in adding transaction"))
    }
  }

  def prepareUnsignedTransaction(
      blockFlow: BlockFlow,
      fromPublicKey: PublicKey,
      destinations: AVector[Destination],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): Try[UnsignedTransaction] = {
    val outputInfos = destinations.map { destination =>
      (destination.address.lockupScript, destination.amount, destination.lockTime)
    }

    blockFlow.transfer(fromPublicKey, outputInfos, gasOpt, gasPrice) match {
      case Right(Right(unsignedTransaction)) => Right(unsignedTransaction)
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
      case Right(Right(unsignedTransaction)) => Right(unsignedTransaction)
      case Right(Left(error))                => Left(failed(error))
      case Left(error)                       => failed(error)
    }
  }

  def checkGroup(lockupScript: LockupScript): Try[Unit] = {
    checkGroup(
      lockupScript.groupIndex(brokerConfig),
      Some(s"Address ${Address.from(networkType, lockupScript)}")
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

  private def parseState(str: Option[String]): Either[Compiler.Error, AVector[Val]] = {
    str match {
      case None => Right(AVector[Val](Val.U256(U256.Zero)))
      case Some(state) =>
        val res = Compiler.compileState(state)
        res
    }
  }

  private def buildContract(
      contract: StatefulContract,
      address: Address,
      initialState: AVector[Val],
      alfAmount: U256
  ): Either[Compiler.Error, StatefulScript] = {

    val codeRaw  = Hex.toHexString(serialize(contract))
    val stateRaw = Hex.toHexString(serialize(initialState))

    val scriptRaw = s"""
      |TxScript Main {
      |  pub payable fn main() -> () {
      |    approveAlf!(@${address.toBase58}, ${alfAmount.v})
      |    createContract!(#$codeRaw, #$stateRaw)
      |  }
      |}
      |""".stripMargin

    Compiler.compileTxScript(scriptRaw)
  }

  private def unignedTxFromScript(
      blockFlow: BlockFlow,
      script: StatefulScript,
      lockupScript: LockupScript.Asset,
      publicKey: PublicKey
  ): ExeResult[UnsignedTransaction] = {
    val unlockScript = UnlockScript.p2pkh(publicKey)
    for {
      balances <- blockFlow.getUsableUtxos(lockupScript).left.map(e => Left(IOErrorLoadOutputs(e)))
      inputs = balances.map(_.ref).map(TxInput(_, unlockScript))
    } yield UnsignedTransaction(Some(script), inputs, AVector.empty)
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def buildContract(blockFlow: BlockFlow, query: BuildContract): FutureTry[BuildContractResult] = {
    Future.successful((for {
      codeBytestring <- Hex
        .from(query.code)
        .toRight(badRequest("Cannot decode code hex string"))
      script <- deserialize[StatefulScript](codeBytestring).left.map(serdeError =>
        badRequest(serdeError.getMessage)
      )
      utx <- unignedTxFromScript(
        blockFlow,
        script,
        LockupScript.p2pkh(query.fromPublicKey),
        query.fromPublicKey
      ).left.map(error => badRequest(error.toString))
    } yield utx).map(BuildContractResult.from))
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def submitContract(txHandler: ActorRefT[TxHandler.Command], query: SubmitContract)(implicit
      askTimeout: Timeout
  ): FutureTry[TxResult] = {
    (for {
      txByteString <- Hex.from(query.tx).toRight(badRequest(s"Invalid hex"))
      unsignedTx <- deserialize[UnsignedTransaction](txByteString).left.map(serdeError =>
        badRequest(serdeError.getMessage)
      )
    } yield {
      TransactionTemplate(
        unsignedTx,
        AVector.fill(unsignedTx.inputs.length)(query.signature),
        AVector.empty
      )
    }) match {
      case Left(error)       => Future.successful(Left(error))
      case Right(txTemplate) => publishTx(txHandler, txTemplate)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def compile(query: Compile): FutureTry[CompileResult] = {
    Future.successful(
      (query.`type` match {
        case "script" =>
          Compiler.compileTxScript(query.code)
        case "contract" =>
          for {
            code   <- Compiler.compileContract(query.code)
            state  <- parseState(query.state)
            script <- buildContract(code, query.address, state, dustUtxoAmount)
          } yield script
        case tpe => Left(Compiler.Error(s"Invalid code type: $tpe"))
      }).map(script => CompileResult(Hex.toHexString(serialize(script))))
        .left
        .map(error => failed(error.toString))
    )
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
}
