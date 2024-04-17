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

package org.alephium.protocol.vm

import scala.annotation.{switch, tailrec}

import akka.util.ByteString

import org.alephium.protocol.model.{Address, ContractId, TokenId}
import org.alephium.protocol.vm.{createContractEventIndex, destroyContractEventIndex}
import org.alephium.protocol.vm.TokenIssuance
import org.alephium.serde.deserialize
import org.alephium.util.{AVector, Bytes}

// scalastyle:off number.of.methods
abstract class Frame[Ctx <: StatelessContext] {
  var pc: Int
  def obj: ContractObj[Ctx]
  def opStack: Stack[Val]
  def method: Method[Ctx]
  def locals: VarVector[Val]
  def returnTo: AVector[Val] => ExeResult[Unit]
  def ctx: Ctx

  def getCallerFrame(): ExeResult[Frame[Ctx]]
  def getCallerAddress(): ExeResult[Val.Address]
  def getCallAddress(): ExeResult[Val.Address]

  def balanceStateOpt: Option[MutBalanceState]

  def getBalanceState(): ExeResult[MutBalanceState] =
    balanceStateOpt.toRight(Right(NoBalanceAvailable))

  def pcMax: Int = method.instrs.length

  def advancePC(): Unit = pc += 1

  def offsetPC(offset: Int): ExeResult[Unit] = {
    val newPC = pc + offset
    if (newPC >= 0 && newPC < method.instrs.length) {
      pc = newPC
      okay
    } else {
      failed(InvalidInstrOffset)
    }
  }

  def complete(): Unit = pc = method.instrs.length

  def pushOpStack(v: Val): ExeResult[Unit] = opStack.push(v)

  def popOpStack(): ExeResult[Val] = opStack.pop()

  def popOpStackBool(): ExeResult[Val.Bool] =
    popOpStack().flatMap {
      case elem: Val.Bool => Right(elem)
      case elem           => failed(InvalidType(Val.Bool, elem))
    }

  def popOpStackI256(): ExeResult[Val.I256] =
    popOpStack().flatMap {
      case elem: Val.I256 => Right(elem)
      case elem           => failed(InvalidType(Val.I256, elem))
    }

  def popOpStackU256(): ExeResult[Val.U256] =
    popOpStack().flatMap {
      case elem: Val.U256 => Right(elem)
      case elem           => failed(InvalidType(Val.U256, elem))
    }

  def popOpStackByteVec(): ExeResult[Val.ByteVec] =
    popOpStack().flatMap {
      case elem: Val.ByteVec => Right(elem)
      case elem              => failed(InvalidType(Val.ByteVec, elem))
    }

  def popOpStackAddress(): ExeResult[Val.Address] =
    popOpStack().flatMap {
      case elem: Val.Address => Right(elem)
      case elem              => failed(InvalidType(Val.Address, elem))
    }

  def popAssetAddress[C <: StatefulContext](): ExeResult[LockupScript.Asset] = {
    for {
      address <- popOpStackAddress()
      lockupScript <-
        if (address.lockupScript.isAssetType) {
          Right(address.lockupScript.asInstanceOf[LockupScript.Asset])
        } else {
          Left(Right(InvalidAssetAddress(Address.from(address.lockupScript))))
        }
    } yield lockupScript
  }

  @inline
  def popContractId(): ExeResult[ContractId] = {
    for {
      byteVec    <- popOpStackByteVec()
      contractId <- ContractId.from(byteVec.bytes).toRight(Right(InvalidContractId))
    } yield contractId
  }

  @inline
  def popFields(): ExeResult[AVector[Val]] = {
    for {
      fieldsRaw <- popOpStackByteVec()
      fields <- deserialize[AVector[Val]](fieldsRaw.bytes).left.map(e =>
        Right(SerdeErrorCreateContract(e))
      )
    } yield fields
  }

  def getLocalVal(index: Int): ExeResult[Val] = {
    locals.get(index)
  }

  def setLocalVal(index: Int, v: Val): ExeResult[Unit] = {
    locals.set(index, v)
  }

  def getImmField(index: Int): ExeResult[Val] = {
    obj.getImmField(index)
  }

  def getMutField(index: Int): ExeResult[Val] = {
    obj.getMutField(index)
  }

  def setMutField(index: Int, v: Val): ExeResult[Unit] = {
    obj.setMutField(index, v)
  }

  protected def getMethod(index: Int): ExeResult[Method[Ctx]] = {
    obj.getMethod(index)
  }

  def methodFrame(index: Int): ExeResult[Frame[Ctx]]

  def createContract(
      contractId: ContractId,
      parentContractId: Option[ContractId],
      code: StatefulContract.HalfDecoded,
      immFields: AVector[Val],
      mutFields: AVector[Val],
      tokenIssuanceInfo: Option[TokenIssuance.Info]
  ): ExeResult[ContractId]

  def destroyContract(refundAddress: LockupScript): ExeResult[Unit]

  def checkPayToContractAddressInCallerTrace(address: LockupScript.P2C): ExeResult[Unit]

  def migrateContract(
      newContractCode: StatefulContract,
      newImmFieldsOpt: Option[AVector[Val]],
      newMutFieldsOpt: Option[AVector[Val]]
  ): ExeResult[Unit]

  def callLocal(index: Byte): ExeResult[Option[Frame[Ctx]]] = {
    advancePC()
    for {
      _     <- ctx.chargeGas(GasSchedule.callGas)
      frame <- methodFrame(Bytes.toPosInt(index))
    } yield Some(frame)
  }

  def callExternal(index: Byte): ExeResult[Option[Frame[Ctx]]]

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  @tailrec final def execute(): ExeResult[Option[Frame[Ctx]]] = {
    if (pc < pcMax) {
      val instr = method.instrs(pc)
      (instr.code: @switch) match {
        case 0 => callLocal(instr.asInstanceOf[CallLocal].index)
        case 1 => callExternal(instr.asInstanceOf[CallExternal].index)
        case 2 => runReturn()
        case _ =>
          // No flatMap for tailrec
          instr.runWith(this) match {
            case Right(_) =>
              advancePC()
              execute()
            case Left(e) => Left(e)
          }
      }
    } else if (pc == pcMax) {
      runReturn()
    } else {
      failed(PcOverflow)
    }
  }

  protected def runReturn(): ExeResult[Option[Frame[Ctx]]] =
    Return.runWith(this).map(_ => None)
}

final class StatelessFrame(
    var pc: Int,
    val obj: ContractObj[StatelessContext],
    val opStack: Stack[Val],
    val method: Method[StatelessContext],
    val locals: VarVector[Val],
    val returnTo: AVector[Val] => ExeResult[Unit],
    val ctx: StatelessContext
) extends Frame[StatelessContext] {
  def methodFrame(index: Int): ExeResult[Frame[StatelessContext]] = {
    for {
      method <- getMethod(index)
      frame  <- Frame.stateless(ctx, obj, method, opStack, opStack.push)
    } yield frame
  }

  // the following should not be used in stateless context
  def balanceStateOpt: Option[MutBalanceState] = None
  def createContract(
      contractId: ContractId,
      parentContractId: Option[ContractId],
      code: StatefulContract.HalfDecoded,
      immFields: AVector[Val],
      mutFields: AVector[Val],
      tokenIssuanceInfo: Option[TokenIssuance.Info]
  ): ExeResult[ContractId] = StatelessFrame.notAllowed
  def destroyContract(refundAddress: LockupScript): ExeResult[Unit] = StatelessFrame.notAllowed
  def checkPayToContractAddressInCallerTrace(address: LockupScript.P2C): ExeResult[Unit] =
    StatelessFrame.notAllowed
  def migrateContract(
      newContractCode: StatefulContract,
      newImmFieldsOpt: Option[AVector[Val]],
      newMutFieldsOpt: Option[AVector[Val]]
  ): ExeResult[Unit] = StatelessFrame.notAllowed
  def getCallerFrame(): ExeResult[Frame[StatelessContext]] = StatelessFrame.notAllowed
  def getCallerAddress(): ExeResult[Val.Address]           = StatelessFrame.notAllowed
  def getCallAddress(): ExeResult[Val.Address]             = StatelessFrame.notAllowed
  def callExternal(index: Byte): ExeResult[Option[Frame[StatelessContext]]] =
    StatelessFrame.notAllowed
}

object StatelessFrame {
  val notAllowed: ExeResult[Nothing] = failed(ExpectStatefulFrame)
}

final case class StatefulFrame(
    var pc: Int,
    obj: ContractObj[StatefulContext],
    opStack: Stack[Val],
    method: Method[StatefulContext],
    locals: VarVector[Val],
    returnTo: AVector[Val] => ExeResult[Unit],
    ctx: StatefulContext,
    callerFrameOpt: Option[StatefulFrame],
    balanceStateOpt: Option[MutBalanceState]
) extends Frame[StatefulContext] {
  def getCallerFrame(): ExeResult[StatefulFrame] = {
    callerFrameOpt.toRight(Right(NoCaller))
  }

  def getCallerAddress(): ExeResult[Val.Address] = {
    callerFrameOpt match {
      case Some(frame) => frame.getCallAddress()
      case None        => ctx.getUniqueTxInputAddress()
    }
  }

  def getCallAddress(): ExeResult[Val.Address] = {
    obj.contractIdOpt match {
      case Some(contractId) => // frame for contract method
        Right(Val.Address(LockupScript.p2c(contractId)))
      case None => // frame for script
        ctx.getUniqueTxInputAddress()
    }
  }

  def getNewFrameBalancesState(
      contractObj: ContractObj[StatefulContext],
      method: Method[StatefulContext],
      methodIndex: Int
  ): ExeResult[Option[MutBalanceState]] = {
    if (ctx.getHardFork().isLemanEnabled()) {
      getNewFrameBalancesStateSinceLeman(contractObj, method, methodIndex)
    } else {
      getNewFrameBalancesStatePreLeman(contractObj, method)
    }
  }

  private def getNewFrameBalancesStateSinceLeman(
      contractObj: ContractObj[StatefulContext],
      method: Method[StatefulContext],
      methodIndex: Int
  ): ExeResult[Option[MutBalanceState]] = {
    if (method.usePreapprovedAssets) {
      for {
        currentBalances <- getBalanceState()
        balanceStateOpt <- {
          val newFrameBalances = currentBalances.useApproved()
          handleContractAssetsForNewFrame(
            newFrameBalances,
            contractObj.contractIdOpt,
            method,
            methodIndex,
            useApprovedAssets = true
          )
        }
      } yield balanceStateOpt
    } else if (method.useContractAssets || method.usePayToContractOnly) {
      handleContractAssetsForNewFrame(
        MutBalanceState.empty,
        contractObj.contractIdOpt,
        method,
        methodIndex,
        useApprovedAssets = false
      )
    } else {
      // Note that we don't check there is no approved assets for this branch
      Right(None)
    }
  }

  private def handleContractAssetsForNewFrame(
      currentBalance: MutBalanceState,
      contractIdOpt: Option[ContractId],
      method: Method[StatefulContext],
      methodIndex: Int,
      useApprovedAssets: Boolean
  ): ExeResult[Option[MutBalanceState]] = {
    val noContractAssetsReturn = if (useApprovedAssets) Right(Some(currentBalance)) else Right(None)

    contractIdOpt match {
      case Some(contractId) =>
        if (method.useContractAssets) {
          assume(!method.usePayToContractOnly, "Must be true")
          ctx
            .useContractAssets(contractId, methodIndex)
            .map { balancesPerLockup =>
              currentBalance.remaining
                .add(LockupScript.p2c(contractId), balancesPerLockup)
                .map(_ => currentBalance)
            }
        } else if (method.usePayToContractOnly) {
          assume(ctx.getHardFork().isGhostEnabled(), "Must be true")
          ctx.prepareForPayToContractOnly(contractId).map(_ => Some(currentBalance))
        } else {
          // Dead branch
          noContractAssetsReturn
        }
      case _ =>
        noContractAssetsReturn
    }
  }

  // TODO: remove this once Leman fork is activated
  private def getNewFrameBalancesStatePreLeman(
      contractObj: ContractObj[StatefulContext],
      method: Method[StatefulContext]
  ): ExeResult[Option[MutBalanceState]] = {
    if (method.usePreapprovedAssets) {
      for {
        currentBalances <- getBalanceState()
        balanceStateOpt <- {
          val newFrameBalances = currentBalances.useApproved()
          contractObj.contractIdOpt match {
            case Some(contractId) =>
              ctx
                .useContractAssetsPreRhone(contractId)
                .map { balancesPerLockup =>
                  newFrameBalances.remaining.add(LockupScript.p2c(contractId), balancesPerLockup)
                  Some(newFrameBalances)
                }
            case None =>
              Right(Some(newFrameBalances))
          }
        }
      } yield balanceStateOpt
    } else {
      Right(None)
    }
  }

  def checkContractId(contractId: ContractId): ExeResult[Unit] = {
    if (ctx.getHardFork().isLemanEnabled() && contractId.bytes == TokenId.alph.bytes) {
      failed(ZeroContractId)
    } else {
      Right(())
    }
  }

  def createContract(
      contractId: ContractId,
      parentContractId: Option[ContractId],
      code: StatefulContract.HalfDecoded,
      immFields: AVector[Val],
      mutFields: AVector[Val],
      tokenIssuanceInfo: Option[TokenIssuance.Info]
  ): ExeResult[ContractId] = {
    for {
      _            <- checkContractId(contractId)
      balanceState <- getBalanceState()
      balances     <- balanceState.approved.useForNewContract().toRight(Right(InvalidBalances))
      _ <- ctx.createContract(contractId, code, immFields, balances, mutFields, tokenIssuanceInfo)
      _ <- ctx.writeLog(
        Some(createContractEventId(ctx.blockEnv.chainIndex.from.value)),
        contractCreationEventFields(contractId, parentContractId, immFields),
        systemEvent = true
      )
    } yield contractId
  }

  def contractCreationEventFields(
      createdContract: ContractId,
      parentContract: Option[ContractId],
      immFields: AVector[Val]
  ): AVector[Val] = {
    AVector(
      createContractEventIndex,
      Val.Address(LockupScript.p2c(createdContract)),
      parentContract match {
        case Some(parent) => Val.Address(LockupScript.p2c(parent))
        case None         => Val.ByteVec(ByteString.empty)
      },
      contractInterfaceIdGuessed(immFields)
    )
  }

  def contractInterfaceIdGuessed(immFields: AVector[Val]): Val = {
    immFields.lastOption match {
      case Some(bytes: Val.ByteVec) if bytes.bytes.startsWith(createContractInterfaceIdPrefix) =>
        Val.ByteVec(bytes.bytes.drop(createContractInterfaceIdPrefix.length))
      case _ => Val.ByteVec(ByteString.empty)
    }
  }

  def destroyContract(refundAddress: LockupScript): ExeResult[Unit] = {
    for {
      _           <- checkDestroyContractRecipientAddress(refundAddress)
      contractId  <- obj.getContractId()
      callerFrame <- getCallerFrame()
      _ <- callerFrame.checkNonRecursive(contractId, ContractDestructionShouldNotBeCalledFromSelf)
      balanceState <- getBalanceState()
      contractAssets <- balanceState
        .useAll(LockupScript.p2c(contractId))
        .toRight(Right(InvalidBalances))
      _ <- ctx.destroyContract(contractId, contractAssets, refundAddress)
      _ <- ctx.writeLog(
        Some(destroyContractEventId(ctx.blockEnv.chainIndex.from.value)),
        AVector(destroyContractEventIndex, Val.Address(LockupScript.p2c(contractId))),
        systemEvent = true
      )
      _ <- runReturn()
    } yield {
      pc -= 1 // because of the `advancePC` call following this instruction
    }
  }

  def checkDestroyContractRecipientAddress(refundAddress: LockupScript): ExeResult[Unit] = {
    refundAddress match {
      case _: LockupScript.Asset => okay
      case contractAddr: LockupScript.P2C =>
        if (ctx.getHardFork().isLemanEnabled()) {
          checkPayToContractAddressInCallerTrace(contractAddr)
        } else {
          failed(InvalidAddressTypeInContractDestroy)
        }
    }
  }

  private def checkNonRecursive(
      targetContractId: ContractId,
      error: ExeFailure
  ): ExeResult[Unit] = {
    if (checkNonRecursive(targetContractId)) {
      okay
    } else {
      failed(error)
    }
  }

  @tailrec
  private def checkNonRecursive(
      targetContractId: ContractId
  ): Boolean = {
    obj.contractIdOpt match {
      case Some(contractId) =>
        if (contractId == targetContractId) {
          false
        } else {
          callerFrameOpt match {
            case Some(frame) => frame.checkNonRecursive(targetContractId)
            case None        => true
          }
        }
      case None => true // Frame for TxScript
    }
  }

  def checkPayToContractAddressInCallerTrace(address: LockupScript.P2C): ExeResult[Unit] = {
    val notInCallerStrace = checkNonRecursive(address.contractId)
    if (notInCallerStrace) {
      failed(PayToContractAddressNotInCallerTrace(Address.Contract(address)))
    } else {
      okay
    }
  }

  def migrateContract(
      newContractCode: StatefulContract,
      newImmFieldsOpt: Option[AVector[Val]],
      newMutFieldsOpt: Option[AVector[Val]]
  ): ExeResult[Unit] = {
    for {
      contractId  <- obj.getContractId()
      callerFrame <- getCallerFrame()
      _           <- callerFrame.checkNonRecursive(contractId, UnexpectedRecursiveCallInMigration)
      _ <- ctx.migrateContract(contractId, obj, newContractCode, newImmFieldsOpt, newMutFieldsOpt)
      _ <- runReturn() // return immediately as the code is upgraded
    } yield {
      pc -= 1
    }
  }

  override def methodFrame(index: Int): ExeResult[Frame[StatefulContext]] = {
    for {
      method             <- getMethod(index)
      newBalanceStateOpt <- getNewFrameBalancesState(obj, method, index)
      frame <-
        Frame.stateful(ctx, Some(this), newBalanceStateOpt, obj, method, opStack, opStack.push)
    } yield frame
  }

  def externalMethodFrame(
      contractId: ContractId,
      index: Int
  ): ExeResult[Frame[StatefulContext]] = {
    for {
      contractObj <- ctx.loadContractObj(contractId)
      method      <- contractObj.getMethod(index)
      _ <- checkLength(method.returnLength, InvalidReturnLength, InvalidExternalMethodReturnLength)
      _ <- checkLength(method.argsLength, InvalidArgLength, InvalidExternalMethodArgLength)
      _ <- if (method.isPublic) okay else failed(ExternalPrivateMethodCall)
      newBalanceStateOpt <- getNewFrameBalancesState(contractObj, method, index)
      frame <-
        Frame.stateful(
          ctx,
          Some(this),
          newBalanceStateOpt,
          contractObj,
          method,
          opStack,
          opStack.push
        )
    } yield frame
  }

  @inline private def checkLength(
      expected: Int,
      paramError: ExeFailure,
      checkError: (Int, Int) => ExeFailure
  ): ExeResult[Unit] = {
    if (ctx.getHardFork().isLemanEnabled()) {
      popOpStackU256().flatMap(_.v.toInt match {
        case Some(length) => if (length == expected) okay else failed(checkError(expected, length))
        case None         => failed(paramError)
      })
    } else {
      okay
    }
  }

  def callExternal(index: Byte): ExeResult[Option[Frame[StatefulContext]]] = {
    advancePC()
    for {
      _          <- ctx.chargeGas(GasSchedule.callGas)
      contractId <- popContractId()
      newFrame   <- externalMethodFrame(contractId, Bytes.toPosInt(index))
    } yield Some(newFrame)
  }
}

object Frame {
  def stateless(
      ctx: StatelessContext,
      obj: ContractObj[StatelessContext],
      method: Method[StatelessContext],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatelessContext]] = {
    build(ctx, operandStack, method, new StatelessFrame(0, obj, _, method, _, returnTo, ctx))
  }

  def stateless(
      ctx: StatelessContext,
      obj: ContractObj[StatelessContext],
      method: Method[StatelessContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatelessContext]] = {
    build(ctx, operandStack, method, args, new StatelessFrame(0, obj, _, method, _, returnTo, ctx))
  }

  def stateful(
      ctx: StatefulContext,
      callerFrame: Option[StatefulFrame],
      balanceStateOpt: Option[MutBalanceState],
      obj: ContractObj[StatefulContext],
      method: Method[StatefulContext],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatefulContext]] = {
    build(
      ctx,
      operandStack,
      method,
      new StatefulFrame(
        0,
        obj,
        _,
        method,
        _,
        returnTo,
        ctx,
        callerFrame,
        balanceStateOpt
      )
    )
  }

  def stateful(
      ctx: StatefulContext,
      callerFrame: Option[StatefulFrame],
      balanceStateOpt: Option[MutBalanceState],
      obj: ContractObj[StatefulContext],
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatefulContext]] = {
    build(
      ctx,
      operandStack,
      method,
      args,
      new StatefulFrame(
        0,
        obj,
        _,
        method,
        _,
        returnTo,
        ctx,
        callerFrame,
        balanceStateOpt
      )
    )
  }

  @inline
  private def build[Ctx <: StatelessContext](
      ctx: Ctx,
      operandStack: Stack[Val],
      method: Method[Ctx],
      frameBuilder: (Stack[Val], VarVector[Val]) => Frame[Ctx]
  ): ExeResult[Frame[Ctx]] = {
    for {
      args   <- operandStack.pop(method.argsLength)
      result <- build(ctx, operandStack, method, args, frameBuilder)
    } yield result
  }

  @inline
  private def build[Ctx <: StatelessContext](
      ctx: Ctx,
      operandStack: Stack[Val],
      method: Method[Ctx],
      args: AVector[Val],
      frameBuilder: (Stack[Val], VarVector[Val]) => Frame[Ctx]
  ): ExeResult[Frame[Ctx]] = {
    if (args.length != method.argsLength) {
      failed(InvalidMethodArgLength(args.length, method.argsLength))
    } else {
      // already validated in script validation and contract creation
      assume(method.localsLength >= args.length)
      if (method.localsLength == 0) {
        if (ctx.getHardFork().isLemanEnabled()) {
          Right(frameBuilder(operandStack.remainingStack(), VarVector.emptyVal))
        } else {
          Right(frameBuilder(operandStack, VarVector.emptyVal))
        }
      } else {
        operandStack.reserveForVars(method.localsLength).map { case (localsVector, newStack) =>
          args.foreachWithIndex((v, index) => localsVector.setUnsafe(index, v))
          (method.argsLength until method.localsLength).foreach { index =>
            localsVector.setUnsafe(index, Val.False)
          }
          newStack -> localsVector
          frameBuilder(newStack, localsVector)
        }
      }
    }
  }
}
