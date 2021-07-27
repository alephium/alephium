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

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

import org.alephium.protocol.Hash
import org.alephium.protocol.model.{AssetOutput, TokenId, TxOutput}
import org.alephium.util.{AVector, Bytes, U256}

abstract class Frame[Ctx <: Context] {
  var pc: Int
  def obj: ContractObj[Ctx]
  def opStack: Stack[Val]
  def method: Method[Ctx]
  def locals: Array[Val]
  def returnTo: AVector[Val] => ExeResult[Unit]
  def ctx: Ctx

  def balanceStateOpt: Option[Frame.BalanceState]

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

  def isComplete: Boolean = pc == method.instrs.length

  def push(v: Val): ExeResult[Unit] = opStack.push(v)

  def pop(): ExeResult[Val] = opStack.pop()

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def popT[T <: Val](): ExeResult[T] =
    pop().flatMap { elem =>
      try Right(elem.asInstanceOf[T])
      catch {
        case _: ClassCastException => failed(InvalidType(elem))
      }
    }

  def getLocal(index: Int): ExeResult[Val] = {
    if (locals.isDefinedAt(index)) Right(locals(index)) else failed(InvalidLocalIndex)
  }

  def setLocal(index: Int, v: Val): ExeResult[Unit] = {
    if (!locals.isDefinedAt(index)) {
      failed(InvalidLocalIndex)
    } else {
      Right(locals.update(index, v))
    }
  }

  def getField(index: Int): ExeResult[Val] = {
    val fields = obj.fields
    if (fields.isDefinedAt(index)) Right(fields(index)) else failed(InvalidFieldIndex)
  }

  def setField(index: Int, v: Val): ExeResult[Unit] = {
    val fields = obj.fields
    if (!fields.isDefinedAt(index)) {
      failed(InvalidFieldIndex)
    } else if (fields(index).tpe != v.tpe) {
      failed(InvalidFieldType)
    } else {
      Right(fields.update(index, v))
    }
  }

  protected def getMethod(index: Int): ExeResult[Method[Ctx]] = {
    obj.getMethod(index).toRight(Right(InvalidMethodIndex(index)))
  }

  def methodFrame(index: Int): ExeResult[Frame[Ctx]]

  def callLocal(index: Byte): ExeResult[Option[Frame[Ctx]]] = {
    advancePC()
    for {
      _     <- ctx.chargeGas(GasSchedule.callGas)
      frame <- methodFrame(Bytes.toPosInt(index))
    } yield Some(frame)
  }

  def externalMethodFrame(contractKey: Hash, index: Int): ExeResult[Frame[StatefulContext]]

  def execute(): ExeResult[Option[Frame[Ctx]]]
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
      args   <- opStack.pop(method.argsType.length)
      _      <- method.check(args)
    } yield Frame.stateless(ctx, obj, method, args, opStack, opStack.push)
  }

  // Should not be used in stateless context
  def balanceStateOpt: Option[Frame.BalanceState]                                           = ???
  def externalMethodFrame(contractKey: Hash, index: Int): ExeResult[Frame[StatefulContext]] = ???

  @tailrec
  override def execute(): ExeResult[Option[Frame[StatelessContext]]] = {
    if (pc < pcMax) {
      method.instrs(pc) match {
        case CallLocal(index) => callLocal(index)
        case Return           => runReturn()
        case instr            =>
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

  private def runReturn(): ExeResult[Option[Frame[StatelessContext]]] =
    Return.runWith(this).map(_ => None)
}

final class StatefulFrame(
    var pc: Int,
    val obj: ContractObj[StatefulContext],
    val opStack: Stack[Val],
    val method: Method[StatefulContext],
    val locals: Array[Val],
    val returnTo: AVector[Val] => ExeResult[Unit],
    val ctx: StatefulContext,
    val balanceStateOpt: Option[Frame.BalanceState]
) extends Frame[StatefulContext] {
  private def getNewFrameBalancesState(
      contractObj: ContractObj[StatefulContext],
      method: Method[StatefulContext]
  ): ExeResult[Option[Frame.BalanceState]] = {
    if (method.isPayable) {
      for {
        state <- balanceStateOpt.toRight(Right(EmptyBalanceForPayableMethod))
        balanceStateOpt <- {
          val newFrameBalances = state.useApproved()
          contractObj.addressOpt match {
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

  override def methodFrame(index: Int): ExeResult[Frame[StatefulContext]] = {
    for {
      method             <- getMethod(index)
      args               <- opStack.pop(method.argsType.length)
      _                  <- method.check(args)
      newBalanceStateOpt <- getNewFrameBalancesState(obj, method)
    } yield {
      Frame.stateful(ctx, newBalanceStateOpt, obj, method, args, opStack, opStack.push)
    }
  }

  override def externalMethodFrame(
      contractKey: Hash,
      index: Int
  ): ExeResult[Frame[StatefulContext]] = {
    for {
      contractObj        <- ctx.loadContract(contractKey)
      method             <- contractObj.getMethod(index).toRight(Right(InvalidMethodIndex(index)))
      _                  <- if (method.isPublic) okay else failed(ExternalPrivateMethodCall)
      args               <- opStack.pop(method.argsType.length)
      _                  <- method.check(args)
      newBalanceStateOpt <- getNewFrameBalancesState(contractObj, method)
    } yield {
      Frame.stateful(ctx, newBalanceStateOpt, contractObj, method, args, opStack, opStack.push)
    }
  }

  def callExternal(index: Byte): ExeResult[Option[Frame[StatefulContext]]] = {
    advancePC()
    for {
      _           <- ctx.chargeGas(GasSchedule.callGas)
      byteVec     <- popT[Val.ByteVec]()
      contractKey <- Hash.from(byteVec.a).toRight(Right(InvalidContractAddress))
      newFrame    <- externalMethodFrame(contractKey, Bytes.toPosInt(index))
    } yield Some(newFrame)
  }

  @tailrec
  override def execute(): ExeResult[Option[Frame[StatefulContext]]] = {
    if (pc < pcMax) {
      method.instrs(pc) match {
        case CallLocal(index)    => callLocal(index)
        case CallExternal(index) => callExternal(index)
        case Return              => runReturn()
        case instr               =>
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

  private def runReturn(): ExeResult[Option[Frame[StatefulContext]]] =
    for {
      _ <- Return.runWith(this)
    } yield None
}

object StatefulFrame {}

object Frame {
  def stateless(
      ctx: StatelessContext,
      obj: ContractObj[StatelessContext],
      method: Method[StatelessContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): Frame[StatelessContext] = {
    val locals = Array.fill[Val](method.localsLength)(Val.False)
    method.argsType.foreachWithIndex { case (tpe, index) =>
      locals(index) = tpe.default
    }
    args.foreachWithIndex((v, index) => locals(index) = v)
    new StatelessFrame(0, obj, operandStack.subStack(), method, locals, returnTo, ctx)
  }

  def stateful(
      ctx: StatefulContext,
      balanceStateOpt: Option[Frame.BalanceState],
      obj: ContractObj[StatefulContext],
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): Frame[StatefulContext] = {
    val locals = Array.fill[Val](method.localsLength)(Val.False)
    args.foreachWithIndex((v, index) => locals(index) = v)
    new StatefulFrame(
      0,
      obj,
      operandStack.subStack(),
      method,
      locals,
      returnTo,
      ctx,
      balanceStateOpt
    )
  }

  /*
   * For each stateful frame, users could put a set of assets.
   * Contracts could move funds, generate outputs by using vm's instructions.
   * `remaining` is the current usable balances
   * `approved` is the balances for payable function call
   */
  final case class BalanceState(remaining: Balances, approved: Balances) {
    def approveALF(lockupScript: LockupScript, amount: U256): Option[Unit] = {
      for {
        _ <- remaining.subAlf(lockupScript, amount)
        _ <- approved.addAlf(lockupScript, amount)
      } yield ()
    }

    def approveToken(lockupScript: LockupScript, tokenId: TokenId, amount: U256): Option[Unit] = {
      for {
        _ <- remaining.subToken(lockupScript, tokenId, amount)
        _ <- approved.addToken(lockupScript, tokenId, amount)
      } yield ()
    }

    def alfRemaining(lockupScript: LockupScript): Option[U256] = {
      remaining.getBalances(lockupScript).map(_.alfAmount)
    }

    def tokenRemaining(lockupScript: LockupScript, tokenId: TokenId): Option[U256] = {
      remaining.getTokenAmount(lockupScript, tokenId)
    }

    def useApproved(): BalanceState = {
      val toUse = approved.use()
      BalanceState(toUse, Balances.empty)
    }

    def useAlf(lockupScript: LockupScript, amount: U256): Option[Unit] = {
      remaining.subAlf(lockupScript, amount)
    }

    def useToken(lockupScript: LockupScript, tokenId: TokenId, amount: U256): Option[Unit] = {
      remaining.subToken(lockupScript, tokenId, amount)
    }
  }

  object BalanceState {
    def from(balances: Balances): BalanceState = BalanceState(balances, Balances.empty)
  }

  final case class Balances(all: ArrayBuffer[(LockupScript, BalancesPerLockup)]) {
    def getBalances(lockupScript: LockupScript): Option[BalancesPerLockup] = {
      all.collectFirst { case (ls, balance) if ls == lockupScript => balance }
    }

    def getAlfAmount(lockupScript: LockupScript): Option[U256] = {
      getBalances(lockupScript).map(_.alfAmount)
    }

    def getTokenAmount(lockupScript: LockupScript, tokenId: TokenId): Option[U256] = {
      getBalances(lockupScript).flatMap(_.getTokenAmount(tokenId))
    }

    def addAlf(lockupScript: LockupScript, amount: U256): Option[Unit] = {
      getBalances(lockupScript) match {
        case Some(balances) =>
          balances.addAlf(amount)
        case None =>
          all.addOne(lockupScript -> BalancesPerLockup.alf(amount))
          Some(())
      }
    }

    def addToken(lockupScript: LockupScript, tokenId: TokenId, amount: U256): Option[Unit] = {
      getBalances(lockupScript) match {
        case Some(balances) =>
          balances.addToken(tokenId, amount)
        case None =>
          all.addOne(lockupScript -> BalancesPerLockup.token(tokenId, amount))
          Some(())
      }
    }

    def subAlf(lockupScript: LockupScript, amount: U256): Option[Unit] = {
      getBalances(lockupScript).flatMap(_.subAlf(amount))
    }

    def subToken(lockupScript: LockupScript, tokenId: TokenId, amount: U256): Option[Unit] = {
      getBalances(lockupScript).flatMap(_.subToken(tokenId, amount))
    }

    def add(lockupScript: LockupScript, balancesPerLockup: BalancesPerLockup): Option[Unit] = {
      getBalances(lockupScript) match {
        case Some(balances) =>
          balances.add(balancesPerLockup)
        case None =>
          all.addOne(lockupScript -> balancesPerLockup)
          Some(())
      }
    }

    def sub(lockupScript: LockupScript, balancesPerLockup: BalancesPerLockup): Option[Unit] = {
      getBalances(lockupScript).flatMap(_.sub(balancesPerLockup))
    }

    def use(): Balances = {
      val newAll = all.map { case (lockupScript, balancesPerLockup) =>
        lockupScript -> balancesPerLockup.copy(scopeDepth = balancesPerLockup.scopeDepth + 1)
      }
      all.clear()
      Balances(newAll)
    }

    def useForNewContract(): Option[BalancesPerLockup] = {
      Option.when(all.nonEmpty) {
        val accumulator = BalancesPerLockup.empty
        all.foreach { balances => accumulator.add(balances._2) }
        all.clear()
        accumulator
      }
    }

    def merge(balances: Balances): Option[Unit] = {
      @tailrec
      def iter(index: Int): Option[Unit] = {
        if (index >= balances.all.length) {
          Some(())
        } else {
          val (lockupScript, balancesPerLockup) = balances.all(index)
          add(lockupScript, balancesPerLockup) match {
            case Some(_) => iter(index + 1)
            case None    => None
          }
        }
      }

      iter(0)
    }
  }

  object Balances {
    // TODO: optimize this
    def from(inputs: AVector[TxOutput], outputs: AVector[AssetOutput]): Option[Balances] = {
      val inputBalances = inputs.fold(Option(empty)) {
        case (Some(balances), input) =>
          balances.add(input.lockupScript, BalancesPerLockup.from(input)).map(_ => balances)
        case (None, _) => None
      }
      val finalBalances = outputs.fold(inputBalances) {
        case (Some(balances), output) =>
          balances.sub(output.lockupScript, BalancesPerLockup.from(output)).map(_ => balances)
        case (None, _) => None
      }
      finalBalances
    }

    // Need to be `def` as it's mutable
    def empty: Balances = Balances(ArrayBuffer.empty)
  }

  final case class BalancesPerLockup(
      var alfAmount: U256,
      tokenAmounts: mutable.Map[TokenId, U256],
      scopeDepth: Int
  ) {
    def tokenVector: AVector[(TokenId, U256)] = {
      import org.alephium.protocol.model.tokenIdOrder
      AVector.from(tokenAmounts.filter(_._2.nonZero)).sortBy(_._1)
    }

    def getTokenAmount(tokenId: TokenId): Option[U256] = tokenAmounts.get(tokenId)

    def addAlf(amount: U256): Option[Unit] = {
      alfAmount.add(amount).map(alfAmount = _)
    }

    def addToken(tokenId: TokenId, amount: U256): Option[Unit] = {
      tokenAmounts.get(tokenId) match {
        case Some(currentAmount) =>
          currentAmount.add(amount).map(tokenAmounts(tokenId) = _)
        case None =>
          tokenAmounts(tokenId) = amount
          Some(())
      }
    }

    def subAlf(amount: U256): Option[Unit] = {
      alfAmount.sub(amount).map(alfAmount = _)
    }

    def subToken(tokenId: TokenId, amount: U256): Option[Unit] = {
      tokenAmounts.get(tokenId).flatMap { currentAmount =>
        currentAmount.sub(amount).map(tokenAmounts(tokenId) = _)
      }
    }

    def add(another: BalancesPerLockup): Option[Unit] =
      Try {
        alfAmount = alfAmount.add(another.alfAmount).getOrElse(throw BalancesPerLockup.error)
        another.tokenAmounts.foreach { case (tokenId, amount) =>
          tokenAmounts.get(tokenId) match {
            case Some(currentAmount) =>
              tokenAmounts(tokenId) =
                currentAmount.add(amount).getOrElse(throw BalancesPerLockup.error)
            case None =>
              tokenAmounts(tokenId) = amount
          }
        }
      }.toOption

    def sub(another: BalancesPerLockup): Option[Unit] =
      Try {
        alfAmount = alfAmount.sub(another.alfAmount).getOrElse(throw BalancesPerLockup.error)
        another.tokenAmounts.foreach { case (tokenId, amount) =>
          tokenAmounts.get(tokenId) match {
            case Some(currentAmount) =>
              tokenAmounts(tokenId) =
                currentAmount.sub(amount).getOrElse(throw BalancesPerLockup.error)
            case None => throw BalancesPerLockup.error
          }
        }
      }.toOption

    def toTxOutput(lockupScript: LockupScript): ExeResult[Option[TxOutput]] = {
      val tokens = tokenVector
      if (alfAmount.isZero) {
        if (tokens.isEmpty) Right(None) else failed(InvalidOutputBalances)
      } else {
        Right(Some(TxOutput.from(alfAmount, tokens, lockupScript)))
      }
    }
  }

  object BalancesPerLockup {
    val error: ArithmeticException = new ArithmeticException("Balance amount")

    // Need to be `def` as it's mutable
    def empty: BalancesPerLockup = BalancesPerLockup(U256.Zero, mutable.Map.empty, 0)

    def alf(amount: U256): BalancesPerLockup = {
      BalancesPerLockup(amount, mutable.Map.empty, 0)
    }

    def token(id: TokenId, amount: U256): BalancesPerLockup = {
      BalancesPerLockup(U256.Zero, mutable.Map(id -> amount), 0)
    }

    def from(output: TxOutput): BalancesPerLockup =
      BalancesPerLockup(output.amount, mutable.Map.from(output.tokens.toIterable), 0)
  }
}
