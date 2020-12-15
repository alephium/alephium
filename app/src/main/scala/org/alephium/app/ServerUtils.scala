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
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.TxHandler
import org.alephium.flow.model.DataOrigin
import org.alephium.protocol.{BlockHash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.{ActorRefT, AVector, Hex, TimeStamp, U256}

class ServerUtils(networkType: NetworkType) {
  def getBlockflow(blockFlow: BlockFlow, fetchRequest: FetchRequest): Try[FetchResponse] = {
    val entriesEither = for {
      headers <- blockFlow.getHeightedBlockHeaders(fetchRequest.fromTs, fetchRequest.toTs)
    } yield headers.map { case (header, height) => BlockEntry.from(header, height) }

    entriesEither match {
      case Right(entries) => Right(FetchResponse(entries.toArray.toIndexedSeq))
      case Left(_)        => failedInIO[FetchResponse]
    }
  }

  def getBalance(blockFlow: BlockFlow, balanceRequest: GetBalance): Try[Balance] =
    for {
      _ <- checkGroup(blockFlow, balanceRequest.address.lockupScript)
      balance <- blockFlow
        .getBalance(balanceRequest.address.lockupScript)
        .map(Balance(_))
        .left
        .flatMap(_ => failedInIO)
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
    val txEither = for {
      txByteString <- Hex.from(query.tx).toRight(failed(s"Invalid hex"))
      unsignedTx <- deserialize[UnsignedTransaction](txByteString).left.map(serdeError =>
        failed(serdeError.getMessage))
    } yield {
      TransactionTemplate(unsignedTx,
                          AVector.fill(unsignedTx.inputs.length)(query.signature),
                          contractSignatures = AVector.empty)
    }
    txEither match {
      case Right(tx)   => publishTx(txHandler, tx)
      case Left(error) => Future.successful(Left(error))
    }
  }

  def getBlock(blockFlow: BlockFlow, query: GetBlock)(implicit cfg: GroupConfig): Try[BlockEntry] =
    for {
      _ <- checkChainIndex(blockFlow, query.hash)
      block <- blockFlow
        .getBlock(query.hash)
        .left
        .map(_ => failed(s"Fail fetching block with header ${query.hash.toHexString}"))
      height <- blockFlow
        .getHeight(block.header)
        .left
        .map(_ => failed("Failed in IO"))
    } yield BlockEntry.from(block, height, networkType)

  def getHashesAtHeight(blockFlow: BlockFlow,
                        chainIndex: ChainIndex,
                        query: GetHashesAtHeight): Try[HashesAtHeight] =
    for {
      hashes <- blockFlow
        .getHashes(chainIndex, query.height)
        .left
        .map(_ => failed("Failed in IO"))
    } yield HashesAtHeight(hashes.map(_.toHexString).toArray.toIndexedSeq)

  def getChainInfo(blockFlow: BlockFlow, chainIndex: ChainIndex): Try[ChainInfo] =
    for {
      maxHeight <- blockFlow
        .getMaxHeight(chainIndex)
        .left
        .map(_ => failed("Failed in IO"))
    } yield ChainInfo(maxHeight)

  private def publishTx(txHandler: ActorRefT[TxHandler.Command], tx: TransactionTemplate)(
      implicit config: GroupConfig,
      askTimeout: Timeout,
      executionContext: ExecutionContext): FutureTry[TxResult] = {
    val message = TxHandler.AddTx(tx, DataOrigin.Local)
    txHandler.ask(message).mapTo[TxHandler.Event].map {
      case _: TxHandler.AddSucceeded =>
        Right(TxResult(tx.hash.toHexString, tx.fromGroup.value, tx.toGroup.value))
      case _: TxHandler.AddFailed =>
        Left(failed("Failed in adding transaction"))
    }
  }

  def prepareUnsignedTransaction(blockFlow: BlockFlow,
                                 fromKey: PublicKey,
                                 toLockupScript: LockupScript,
                                 lockTimeOpt: Option[TimeStamp],
                                 value: U256): Try[UnsignedTransaction] = {
    val fromLockupScript = LockupScript.p2pkh(fromKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromKey)
    blockFlow.prepareUnsignedTx(fromLockupScript,
                                fromUnlockScript,
                                toLockupScript,
                                lockTimeOpt,
                                value) match {
      case Right(Some(unsignedTransaction)) => Right(unsignedTransaction)
      case Right(None)                      => Left(failed("Not enough balance"))
      case Left(_)                          => failedInIO
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
      Left(failed(s"Address ${Address(networkType, lockupScript)} belongs to other groups"))
    }
  }

  def checkChainIndex(blockFlow: BlockFlow, hash: BlockHash)(
      implicit groupConfig: GroupConfig): Try[Unit] = {
    val chainIndex = ChainIndex.from(hash)
    if (blockFlow.brokerConfig.contains(chainIndex.from) ||
        blockFlow.brokerConfig.contains(chainIndex.to)) {
      Right(())
    } else {
      Left(failed(s"${hash.toHexString} belongs to other groups"))
    }
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
        .toRight(failed("Cannot decode code hex string"))
      script <- deserialize[StatefulScript](codeBytestring).left.map(serdeError =>
        failed(serdeError.getMessage))
      utx <- unignedTxFromScript(blockFlow,
                                 script,
                                 LockupScript.p2pkh(query.fromKey),
                                 query.fromKey).left.map(error => failed(error.toString))
    } yield utx).map(BuildContractResult.from))
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def sendContract(txHandler: ActorRefT[TxHandler.Command], query: SendContract)(
      implicit groupConfig: GroupConfig,
      askTimeout: Timeout,
      executionContext: ExecutionContext): FutureTry[TxResult] = {
    (for {
      txByteString <- Hex.from(query.tx).toRight(failed(s"Invalid hex"))
      unsignedTx <- deserialize[UnsignedTransaction](txByteString).left.map(serdeError =>
        failed(serdeError.getMessage))
    } yield {
      TransactionTemplate(unsignedTx,
                          AVector.fill(unsignedTx.inputs.length)(query.signature),
                          AVector.empty)
    }) match {
      case Left(error)       => Future.successful(Left(error))
      case Right(txTemplate) => publishTx(txHandler, txTemplate)
    }
  }

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
      }).map(script => CompileResult(Hex.toHexString(serialize(script))))
        .left
        .map(error => failed(error.toString))
    )
  }

  private def failed(error: String): ApiModel.Error = ApiModel.Error.server(error)
  private def failedInIO[T]: Try[T]                 = Left(ApiModel.Error.server("Failed in IO"))

  type Try[T]       = Either[ApiModel.Error, T]
  type FutureTry[T] = Future[Try[T]]
}
