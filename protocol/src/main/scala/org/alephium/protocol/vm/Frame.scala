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

import org.alephium.protocol.Hash
import org.alephium.protocol.model.ContractId
import org.alephium.serde.deserialize
import org.alephium.util.{AVector, Bytes}

abstract class Frame[Ctx <: StatelessContext] {
  var pc: Int
  def obj: ContractObj[Ctx]
  def opStack: Stack[Val]
  def method: Method[Ctx]
  def locals: Array[Val]
  def returnTo: AVector[Val] => ExeResult[Unit]
  def ctx: Ctx

  def getCallerFrame(): ExeResult[Frame[Ctx]]

  def balanceStateOpt: Option[BalanceState]

  def getBalanceState(): ExeResult[BalanceState] =
    balanceStateOpt.toRight(Right(EmptyBalanceForPayableMethod))

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

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def popOpStackT[T <: Val](): ExeResult[T] =
    popOpStack().flatMap { elem =>
      try Right(elem.asInstanceOf[T])
      catch {
        case _: ClassCastException => failed(InvalidType(elem))
      }
    }

  @inline
  def popContractId(): ExeResult[ContractId] = {
    for {
      byteVec     <- popOpStackT[Val.ByteVec]()
      contractKey <- Hash.from(byteVec.a).toRight(Right(InvalidContractAddress))
    } yield contractKey
  }

  @inline
  def popFields(): ExeResult[AVector[Val]] = {
    for {
      fieldsRaw <- popOpStackT[Val.ByteVec]()
      fields <- deserialize[AVector[Val]](ByteString(fieldsRaw.a)).left.map(e =>
        Right(SerdeErrorCreateContract(e))
      )
    } yield fields
  }

  def getLocalVal(index: Int): ExeResult[Val] = {
    if (locals.isDefinedAt(index)) Right(locals(index)) else failed(InvalidLocalIndex)
  }

  def setLocalVal(index: Int, v: Val): ExeResult[Unit] = {
    if (!locals.isDefinedAt(index)) {
      failed(InvalidLocalIndex)
    } else {
      Right(locals.update(index, v))
    }
  }

  def getField(index: Int): ExeResult[Val] = {
    obj.getField(index)
  }

  def setField(index: Int, v: Val): ExeResult[Unit] = {
    obj.setField(index, v)
  }

  protected def getMethod(index: Int): ExeResult[Method[Ctx]] = {
    obj.getMethod(index)
  }

  def methodFrame(index: Int): ExeResult[Frame[Ctx]]

  def createContract(code: StatefulContract, fields: AVector[Val]): ExeResult[Unit]

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
    val locals: Array[Val],
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
  def balanceStateOpt: Option[BalanceState]                                         = ???
  def createContract(code: StatefulContract, fields: AVector[Val]): ExeResult[Unit] = ???
  def getCallerFrame(): ExeResult[Frame[StatelessContext]]                          = ???
  def callExternal(index: Byte): ExeResult[Option[Frame[StatelessContext]]]         = ???
}

final class StatefulFrame(
    var pc: Int,
    val obj: ContractObj[StatefulContext],
    val opStack: Stack[Val],
    val method: Method[StatefulContext],
    val locals: Array[Val],
    val returnTo: AVector[Val] => ExeResult[Unit],
    val ctx: StatefulContext,
    val callerFrameOpt: Option[Frame[StatefulContext]],
    val balanceStateOpt: Option[BalanceState]
) extends Frame[StatefulContext] {
  def getCallerFrame(): ExeResult[Frame[StatefulContext]] = {
    callerFrameOpt.toRight(Right(NoCaller))
  }

  private def getNewFrameBalancesState(
      contractObj: ContractObj[StatefulContext],
      method: Method[StatefulContext]
  ): ExeResult[Option[BalanceState]] = {
    if (method.isPayable) {
      for {
        currentBalances <- getBalanceState()
        balanceStateOpt <- {
          val newFrameBalances = currentBalances.useApproved()
          contractObj.contractIdOpt match {
            case Some(contractId) =>
              ctx
                .useContractAsset(contractId)
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

  def createContract(code: StatefulContract, fields: AVector[Val]): ExeResult[Unit] = {
    for {
      balanceState <- getBalanceState()
      balances     <- balanceState.approved.useForNewContract().toRight(Right(InvalidBalances))
      _            <- ctx.createContract(code, balances, fields)
    } yield ()
  }

  override def methodFrame(index: Int): ExeResult[Frame[StatefulContext]] = {
    for {
      method             <- getMethod(index)
      newBalanceStateOpt <- getNewFrameBalancesState(obj, method)
      frame <-
        Frame.stateful(ctx, Some(this), newBalanceStateOpt, obj, method, opStack, opStack.push)
    } yield frame
  }

  def externalMethodFrame(
      contractKey: Hash,
      index: Int
  ): ExeResult[Frame[StatefulContext]] = {
    for {
      contractObj        <- ctx.loadContract(contractKey)
      method             <- contractObj.getMethod(index)
      _                  <- if (method.isPublic) okay else failed(ExternalPrivateMethodCall)
      newBalanceStateOpt <- getNewFrameBalancesState(contractObj, method)
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
    build(operandStack, method, new StatelessFrame(0, obj, _, method, _, returnTo, ctx))
  }

  def stateless(
      ctx: StatelessContext,
      obj: ContractObj[StatelessContext],
      method: Method[StatelessContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatelessContext]] = {
    build(method, args, new StatelessFrame(0, obj, operandStack, method, _, returnTo, ctx))
  }

  def stateful(
      ctx: StatefulContext,
      callerFrame: Option[Frame[StatefulContext]],
      balanceStateOpt: Option[BalanceState],
      obj: ContractObj[StatefulContext],
      method: Method[StatefulContext],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatefulContext]] = {
    build(
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
      callerFrame: Option[Frame[StatefulContext]],
      balanceStateOpt: Option[BalanceState],
      obj: ContractObj[StatefulContext],
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatefulContext]] = {
    build(
      method,
      args,
      new StatefulFrame(
        0,
        obj,
        operandStack,
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
      operandStack: Stack[Val],
      method: Method[Ctx],
      frameBuilder: (Stack[Val], Array[Val]) => Frame[Ctx]
  ): ExeResult[Frame[Ctx]] = {
    operandStack.pop(method.argsLength) match {
      case Right(args) =>
        val newStack = operandStack.remainingStack()
        Right(frameBuilder(newStack, prepareLocals(method, args)))
      case _ => failed(InsufficientArgs)
    }
  }

  @inline
  private def build[Ctx <: StatelessContext](
      method: Method[Ctx],
      args: AVector[Val],
      frameBuilder: Array[Val] => Frame[Ctx]
  ): ExeResult[Frame[Ctx]] = {
    if (args.length != method.argsLength) {
      failed(InvalidMethodArgLength(args.length, method.argsLength))
    } else {
      Right(frameBuilder(prepareLocals(method, args)))
    }
  }

  @inline
  private def prepareLocals[Ctx <: StatelessContext](
      method: Method[Ctx],
      args: AVector[Val]
  ): Array[Val] = {
    val locals = Array.ofDim[Val](method.localsLength)
    args.foreachWithIndex((v, index) => locals(index) = v)
    (method.argsLength until method.localsLength).foreach { index => locals(index) = Val.False }
    locals
  }
}
