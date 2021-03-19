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

import org.alephium.api.ApiModel
import org.alephium.api.model._
import org.alephium.flow.core.{BlockFlow, BlockFlowState}
import org.alephium.flow.handler.TxHandler
import org.alephium.flow.model.DataOrigin
import org.alephium.io.IOError
import org.alephium.protocol.{BlockHash, Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util._

// scalastyle:off number.of.methods
class ServerUtils(networkType: NetworkType) {
  def getBlockflow(blockFlow: BlockFlow, fetchRequest: FetchRequest): Try[FetchResponse] = {
    val entriesEither = for {
      headers <- blockFlow.getHeightedBlockHeaders(fetchRequest.fromTs, fetchRequest.toTs)
    } yield headers.map { case (header, height) => BlockEntry.from(header, height) }

    entriesEither match {
      case Right(entries) => Right(FetchResponse(entries))
      case Left(error)    => failed[FetchResponse](error)
    }
  }

  def getBalance(blockFlow: BlockFlow, balanceRequest: GetBalance): Try[Balance] =
    for {
      _ <- checkGroup(blockFlow, balanceRequest.address.lockupScript)
      balance <- blockFlow
        .getBalance(balanceRequest.address.lockupScript)
        .map(Balance(_))
        .left
        .flatMap(failed)
    } yield balance

  def getGroup(blockFlow: BlockFlow, query: GetGroup): Try[Group] = {
    Right(Group(query.address.groupIndex(blockFlow.brokerConfig).value))
  }

  def listUnconfirmedTransactions(blockFlow: BlockFlow,
                                  chainIndex: ChainIndex): Try[AVector[Tx]] = {
    Right(
      blockFlow
        .getPool(chainIndex)
        .getAll(chainIndex)
        .map(Tx.fromTemplate(_, networkType)))
  }

  def buildTransaction(blockFlow: BlockFlow, query: BuildTransaction)(
      implicit groupConfig: GroupConfig): Try[BuildTransactionResult] = {
    val resultEither = for {
      _ <- checkGroup(blockFlow, query.fromKey)
      unsignedTx <- prepareUnsignedTransaction(blockFlow,
                                               query.fromKey,
                                               query.toAddress.lockupScript,
                                               query.lockTime,
                                               query.value)
    } yield {
      BuildTransactionResult.from(unsignedTx)
    }
    resultEither match {
      case Right(result) => Right(result)
      case Left(error)   => Left(error)
    }
  }

  def sendTransaction(txHandler: ActorRefT[TxHandler.Command], query: SendTransaction)(
      implicit config: GroupConfig,
      askTimeout: Timeout,
      executionContext: ExecutionContext): FutureTry[TxResult] = {
    createTxTemplate(query) match {
      case Right(tx)   => publishTx(txHandler, tx)
      case Left(error) => Future.successful(Left(error))
    }
  }

  def createTxTemplate(query: SendTransaction): Try[TransactionTemplate] = {
    for {
      txByteString <- Hex.from(query.unsignedTx).toRight(apiError(s"Invalid hex"))
      unsignedTx <- deserialize[UnsignedTransaction](txByteString).left.map(serdeError =>
        apiError(serdeError.getMessage))
    } yield {
      TransactionTemplate(unsignedTx,
                          AVector.fill(unsignedTx.inputs.length)(query.signature),
                          contractSignatures = AVector.empty)
    }
  }

  def convert(status: BlockFlowState.TxStatus): TxStatus =
    Confirmed(status.index.hash,
              status.index.index,
              status.chainConfirmations,
              status.fromGroupConfirmations,
              status.toGroupConfirmations)

  def getTransactionStatus(blockFlow: BlockFlow, txId: Hash, fromGroup: Int, toGroup: Int)(
      implicit groupConfig: GroupConfig): Try[TxStatus] = {
    for {
      chainIndex <- checkTxChainIndex(blockFlow, fromGroup, toGroup)
      status     <- getTransactionStatus(blockFlow, txId, chainIndex)
    } yield status
  }

  def getTransactionStatus(blockFlow: BlockFlow,
                           txId: Hash,
                           chainIndex: ChainIndex): Try[TxStatus] = {
    blockFlow.getTxStatus(txId, chainIndex).left.map(apiError).map {
      case Some(status) => convert(status)
      case None         => if (isInMemPool(blockFlow, txId, chainIndex)) MemPooled else NotFound
    }
  }

  def isInMemPool(blockFlow: BlockFlow, txId: Hash, chainIndex: ChainIndex): Boolean = {
    blockFlow.getPool(chainIndex).contains(chainIndex, txId)
  }

  def getBlock(blockFlow: BlockFlow, query: GetBlock)(implicit cfg: GroupConfig): Try[BlockEntry] =
    for {
      _ <- checkChainIndex(blockFlow, query.hash)
      block <- blockFlow
        .getBlock(query.hash)
        .left
        .map(_ => apiError(s"Fail fetching block with header ${query.hash.toHexString}"))
      height <- blockFlow
        .getHeight(block.header)
        .left
        .map(error => apiError(s"Failed in IO: $error"))
    } yield BlockEntry.from(block, height, networkType)

  def getHashesAtHeight(blockFlow: BlockFlow,
                        chainIndex: ChainIndex,
                        query: GetHashesAtHeight): Try[HashesAtHeight] =
    for {
      hashes <- blockFlow
        .getHashes(chainIndex, query.height)
        .left
        .map(_ => apiError("Failed in IO"))
    } yield HashesAtHeight(hashes)

  def getChainInfo(blockFlow: BlockFlow, chainIndex: ChainIndex): Try[ChainInfo] =
    for {
      maxHeight <- blockFlow
        .getMaxHeight(chainIndex)
        .left
        .map(_ => apiError("Failed in IO"))
    } yield ChainInfo(maxHeight)

  private def publishTx(txHandler: ActorRefT[TxHandler.Command], tx: TransactionTemplate)(
      implicit config: GroupConfig,
      askTimeout: Timeout,
      executionContext: ExecutionContext): FutureTry[TxResult] = {
    val message = TxHandler.AddTx(tx, DataOrigin.Local)
    txHandler.ask(message).mapTo[TxHandler.Event].map {
      case _: TxHandler.AddSucceeded =>
        Right(TxResult(tx.id, tx.fromGroup.value, tx.toGroup.value))
      case _: TxHandler.AddFailed =>
        Left(apiError("Failed in adding transaction"))
    }
  }

  def prepareUnsignedTransaction(blockFlow: BlockFlow,
                                 fromKey: PublicKey,
                                 toLockupScript: LockupScript,
                                 lockTimeOpt: Option[TimeStamp],
                                 value: U256): Try[UnsignedTransaction] = {
    blockFlow.prepareUnsignedTx(fromKey, toLockupScript, lockTimeOpt, value) match {
      case Right(Right(unsignedTransaction)) => Right(unsignedTransaction)
      case Right(Left(error))                => Left(apiError(error))
      case Left(error)                       => failed(error)
    }
  }

  def checkGroup(group: Int)(implicit groupConfig: GroupConfig): Try[Unit] = {
    if (group >= 0 && group < groupConfig.groups) {
      Right(())
    } else {
      Left(apiError(s"Invalid group: $group"))
    }
  }

  def checkGroup(blockFlow: BlockFlow, publicKey: PublicKey): Try[Unit] = {
    checkGroup(blockFlow, LockupScript.p2pkh(publicKey))
  }

  def checkGroup(blockFlow: BlockFlow, lockupScript: LockupScript): Try[Unit] = {
    val groupIndex = lockupScript.groupIndex(blockFlow.brokerConfig)
    if (blockFlow.brokerConfig.contains(groupIndex)) {
      Right(())
    } else {
      Left(apiError(s"Address ${Address(networkType, lockupScript)} belongs to other groups"))
    }
  }

  def checkChainIndex(blockFlow: BlockFlow, hash: BlockHash)(
      implicit groupConfig: GroupConfig): Try[Unit] = {
    val chainIndex = ChainIndex.from(hash)
    if (blockFlow.brokerConfig.contains(chainIndex.from) ||
        blockFlow.brokerConfig.contains(chainIndex.to)) {
      Right(())
    } else {
      Left(apiError(s"${hash.toHexString} belongs to other groups"))
    }
  }

  def checkTxChainIndex(blockFlow: BlockFlow, fromGroup: Int, toGroup: Int)(
      implicit groupConfig: GroupConfig): Try[ChainIndex] = {
    val chainIndex = ChainIndex.unsafe(fromGroup, toGroup)
    for {
      _ <- checkGroup(fromGroup)
      _ <- checkGroup(toGroup)
      _ <- {
        if (blockFlow.brokerConfig.contains(chainIndex.from) ||
            blockFlow.brokerConfig.contains(chainIndex.to)) {
          Right(())
        } else {
          Left(apiError(s"$chainIndex belongs to other groups"))
        }
      }
    } yield chainIndex
  }

  def execute(f: => Unit)(implicit ec: ExecutionContext): FutureTry[Boolean] =
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

  private def buildContract(contract: StatefulContract,
                            address: Address,
                            initialState: AVector[Val],
                            alfAmount: U256): Either[Compiler.Error, StatefulScript] = {

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
      lockupScript: LockupScript,
      publicKey: PublicKey
  ): ExeResult[UnsignedTransaction] = {
    val unlockScript = UnlockScript.p2pkh(publicKey)
    for {
      balances <- blockFlow.getUtxos(lockupScript).left.map[ExeFailure](IOErrorLoadOutputs)
      inputs = balances.map(_._1).map(TxInput(_, unlockScript))
    } yield UnsignedTransaction(Some(script), inputs, AVector.empty)
  }

  def txFromScript(
      blockFlow: BlockFlow,
      script: StatefulScript,
      fromGroup: GroupIndex,
      contractTx: TransactionAbstract
  ): ExeResult[Transaction] = {
    for {
      worldState <- blockFlow
        .getBestCachedWorldState(fromGroup)
        .left
        .map[ExeFailure](error => NonCategorized(error.getMessage))
      result <- StatefulVM.runTxScript(worldState, contractTx, script, contractTx.unsigned.startGas)
    } yield {
      val contractInputs  = result.contractInputs
      val generateOutputs = result.generatedOutputs
      Transaction(contractTx.unsigned,
                  contractInputs,
                  generateOutputs,
                  contractTx.inputSignatures,
                  contractTx.contractSignatures)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def buildContract(blockFlow: BlockFlow, query: BuildContract)(
      implicit groupConfig: GroupConfig): FutureTry[BuildContractResult] = {
    Future.successful((for {
      codeBytestring <- Hex
        .from(query.code)
        .toRight(apiError("Cannot decode code hex string"))
      script <- deserialize[StatefulScript](codeBytestring).left.map(serdeError =>
        apiError(serdeError.getMessage))
      utx <- unignedTxFromScript(blockFlow,
                                 script,
                                 LockupScript.p2pkh(query.fromKey),
                                 query.fromKey).left.map(error => apiError(error.toString))
    } yield utx).map(BuildContractResult.from))
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def sendContract(txHandler: ActorRefT[TxHandler.Command], query: SendContract)(
      implicit groupConfig: GroupConfig,
      askTimeout: Timeout,
      executionContext: ExecutionContext): FutureTry[TxResult] = {
    (for {
      txByteString <- Hex.from(query.tx).toRight(apiError(s"Invalid hex"))
      unsignedTx <- deserialize[UnsignedTransaction](txByteString).left.map(serdeError =>
        apiError(serdeError.getMessage))
    } yield {
      TransactionTemplate(unsignedTx,
                          AVector.fill(unsignedTx.inputs.length)(query.signature),
                          AVector.empty)
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
            script <- buildContract(code, query.address, state, U256.One)
          } yield script
        case tpe => Left(Compiler.Error(s"Invalid code type: $tpe"))
      }).map(script => CompileResult(Hex.toHexString(serialize(script))))
        .left
        .map(error => apiError(error.toString))
    )
  }

  private def apiError(error: IOError): ApiModel.Error =
    ApiModel.Error.server(s"Failed in IO: $error")
  private def apiError(error: String): ApiModel.Error = ApiModel.Error.server(error)
  private def failed[T](error: IOError): Try[T]       = Left(apiError(error))

  type Try[T]       = Either[ApiModel.Error, T]
  type FutureTry[T] = Future[Try[T]]
}
