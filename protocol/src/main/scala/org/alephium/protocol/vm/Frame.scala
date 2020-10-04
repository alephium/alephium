package org.alephium.protocol.vm

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

import akka.util.ByteString

import org.alephium.protocol.Hash
import org.alephium.protocol.model.{AssetOutput, TokenId, TxOutput}
import org.alephium.util.{AVector, Bytes, U64}

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
      Right(())
    } else Left(InvalidInstrOffset)
  }

  def complete(): Unit = pc = method.instrs.length

  def isComplete: Boolean = pc == method.instrs.length

  def push(v: Val): ExeResult[Unit] = opStack.push(v)

  def pop(): ExeResult[Val] = opStack.pop()

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def popT[T <: Val](): ExeResult[T] = pop().flatMap { elem =>
    try Right(elem.asInstanceOf[T])
    catch {
      case _: ClassCastException => Left(InvalidType(elem))
    }
  }

  def getLocal(index: Int): ExeResult[Val] = {
    if (locals.isDefinedAt(index)) Right(locals(index)) else Left(InvalidLocalIndex)
  }

  def setLocal(index: Int, v: Val): ExeResult[Unit] = {
    if (!locals.isDefinedAt(index)) {
      Left(InvalidLocalIndex)
    } else if (locals(index).tpe != v.tpe) {
      Left(InvalidLocalType)
    } else {
      Right(locals.update(index, v))
    }
  }

  def getField(index: Int): ExeResult[Val] = {
    val fields = obj.fields
    if (fields.isDefinedAt(index)) Right(fields(index)) else Left(InvalidFieldIndex)
  }

  def reloadFields(): ExeResult[Unit] = obj.reloadFields(ctx)

  def setField(index: Int, v: Val): ExeResult[Unit] = {
    val fields = obj.fields
    if (!fields.isDefinedAt(index)) {
      Left(InvalidFieldIndex)
    } else if (fields(index).tpe != v.tpe) {
      Left(InvalidFieldType)
    } else {
      Right(fields.update(index, v))
    }
  }

  protected def getMethod(index: Int): ExeResult[Method[Ctx]] = {
    obj.getMethod(index).toRight(InvalidMethodIndex(index))
  }

  def methodFrame(index: Int): ExeResult[Frame[Ctx]]

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
      args   <- opStack.pop(method.localsType.length)
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
        case CallLocal(index) =>
          advancePC()
          methodFrame(Bytes.toPosInt(index)).map(Some.apply)
        case Return =>
          runReturn()
        case instr =>
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
      Left(PcOverflow)
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
  override def methodFrame(index: Int): ExeResult[Frame[StatefulContext]] = {
    for {
      method <- getMethod(index)
      args   <- opStack.pop(method.localsType.length)
      _      <- method.check(args)
      newBalanceStateOpt <- if (method.isPayable) {
        balanceStateOpt
          .toRight(EmptyBalanceForPayableMethod)
          .map(state => Some(state.useApproved()))
      } else Right(None)
    } yield {
      Frame.stateful(ctx, newBalanceStateOpt, obj, method, args, opStack, opStack.push)
    }
  }

  override def externalMethodFrame(contractKey: Hash,
                                   index: Int): ExeResult[Frame[StatefulContext]] = {
    for {
      contractObj <- ctx.worldState
        .getContractObj(contractKey)
        .left
        .map[ExeFailure](IOErrorLoadContract)
      method <- contractObj.getMethod(index).toRight[ExeFailure](InvalidMethodIndex(index))
      _      <- if (method.isPublic) Right(()) else Left(PrivateExternalMethodCall)
      args   <- opStack.pop(method.localsType.length)
      _      <- method.check(args)
      newBalanceStateOpt <- if (method.isPayable) {
        balanceStateOpt
          .toRight(EmptyBalanceForPayableMethod)
          .map(state => Some(state.useApproved()))
      } else Right(None)
    } yield {
      Frame.stateful(ctx, newBalanceStateOpt, contractObj, method, args, opStack, opStack.push)
    }
  }

  @tailrec
  override def execute(): ExeResult[Option[Frame[StatefulContext]]] = {
    if (pc < pcMax) {
      method.instrs(pc) match {
        case CallLocal(index) =>
          advancePC()
          methodFrame(Bytes.toPosInt(index)).map(Some.apply)
        case CallExternal(index) =>
          advancePC()
          for {
            _           <- obj.commitFields(ctx)
            byteVec     <- popT[Val.ByteVec]()
            contractKey <- Hash.from(byteVec.a).toRight(InvalidContractAddress)
            newFrame    <- externalMethodFrame(contractKey, Bytes.toPosInt(index))
          } yield Some(newFrame)
        case Return =>
          runReturn()
        case instr =>
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
      Left(PcOverflow)
    }
  }

  private def runReturn(): ExeResult[Option[Frame[StatefulContext]]] =
    for {
      _ <- Return.runWith(this)
      _ <- obj.commitFields(ctx)
    } yield None
}

object StatefulFrame {}

object Frame {
  def stateless(ctx: StatelessContext,
                obj: ContractObj[StatelessContext],
                method: Method[StatelessContext],
                args: AVector[Val],
                operandStack: Stack[Val],
                returnTo: AVector[Val] => ExeResult[Unit]): Frame[StatelessContext] = {
    val locals = method.localsType.mapToArray(_.default)
    args.foreachWithIndex((v, index) => locals(index) = v)
    new StatelessFrame(0, obj, operandStack.subStack(), method, locals, returnTo, ctx)
  }

  def stateful(ctx: StatefulContext,
               balanceStateOpt: Option[Frame.BalanceState],
               obj: ContractObj[StatefulContext],
               method: Method[StatefulContext],
               args: AVector[Val],
               operandStack: Stack[Val],
               returnTo: AVector[Val] => ExeResult[Unit]): Frame[StatefulContext] = {
    val locals = method.localsType.mapToArray(_.default)
    args.foreachWithIndex((v, index) => locals(index) = v)
    new StatefulFrame(0,
                      obj,
                      operandStack.subStack(),
                      method,
                      locals,
                      returnTo,
                      ctx,
                      balanceStateOpt)
  }

  /*
   * For each stateful frame, users could put a set of assets.
   * Contracts could move funds, generate outputs by using vm's instructions.
   * `remaining` is the current usable balances
   * `approved` is the balances for payable function call
   */
  final case class BalanceState(remaining: Balances, approved: Balances) {
    def approveALF(lockupScript: LockupScript, amount: U64): Option[Unit] = {
      for {
        _ <- remaining.subAlf(lockupScript, amount)
        _ <- approved.addAlf(lockupScript, amount)
      } yield ()
    }

    def approveToken(lockupScript: LockupScript, tokenId: TokenId, amount: U64): Option[Unit] = {
      for {
        _ <- remaining.subToken(lockupScript, tokenId, amount)
        _ <- approved.addToken(lockupScript, tokenId, amount)
      } yield ()
    }

    def alfRemaining(lockupScript: LockupScript): Option[U64] = {
      remaining.getBalances(lockupScript).map(_.alfAmount)
    }

    def tokenRemaining(lockupScript: LockupScript, tokenId: TokenId): Option[U64] = {
      remaining.getTokenAmount(lockupScript, tokenId)
    }

    def useApproved(): BalanceState = {
      val toUse = approved.use()
      BalanceState(toUse, Balances.empty)
    }

    def useAlf(lockupScript: LockupScript, amount: U64): Option[Unit] = {
      remaining.subAlf(lockupScript, amount)
    }

    def useToken(lockupScript: LockupScript, tokenId: TokenId, amount: U64): Option[Unit] = {
      remaining.subToken(lockupScript, tokenId, amount)
    }
  }

  object BalanceState {
    def from(balances: Balances): BalanceState = BalanceState(balances, Balances.empty)
  }

  final case class Balances(all: ArrayBuffer[(LockupScript, BalancesPerLockup)]) {
    def getBalances(lockupScript: LockupScript): Option[BalancesPerLockup] = {
      all.find(_._1 == lockupScript).map(_._2)
    }

    def getAlfAmount(lockupScript: LockupScript): Option[U64] = {
      getBalances(lockupScript).map(_.alfAmount)
    }

    def getTokenAmount(lockupScript: LockupScript, tokenId: TokenId): Option[U64] = {
      getBalances(lockupScript).flatMap(_.getTokenAmount(tokenId))
    }

    def addAlf(lockupScript: LockupScript, amount: U64): Option[Unit] = {
      getBalances(lockupScript) match {
        case Some(balances) =>
          balances.addAlf(amount)
        case None =>
          all.addOne(lockupScript -> BalancesPerLockup.alf(amount))
          Some(())
      }
    }

    def addToken(lockupScript: LockupScript, tokenId: TokenId, amount: U64): Option[Unit] = {
      getBalances(lockupScript) match {
        case Some(balances) =>
          balances.addToken(tokenId, amount)
        case None =>
          all.addOne(lockupScript -> BalancesPerLockup.token(tokenId, amount))
          Some(())
      }
    }

    def subAlf(lockupScript: LockupScript, amount: U64): Option[Unit] = {
      getBalances(lockupScript).flatMap(_.subAlf(amount))
    }

    def subToken(lockupScript: LockupScript, tokenId: TokenId, amount: U64): Option[Unit] = {
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
      val newAll = ArrayBuffer.from(all)
      all.clear()
      Balances(newAll)
    }

    def pool(): Option[BalancesPerLockup] = {
      Option.when(all.nonEmpty) {
        val accumulator = BalancesPerLockup.empty
        all.foreach { balances =>
          accumulator.add(balances._2)
        }
        accumulator
      }
    }

    def merge(balances: Balances): Option[Unit] = {
      @tailrec
      def iter(index: Int): Option[Unit] = {
        if (index >= balances.all.length) Some(())
        else {
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

    def empty: Balances = Balances(ArrayBuffer.empty)
  }

  final case class BalancesPerLockup(var alfAmount: U64, tokenAmounts: mutable.Map[TokenId, U64]) {
    def tokenVector: AVector[(TokenId, U64)] = {
      import org.alephium.protocol.model.tokenIdOrder
      AVector.from(tokenAmounts).sortBy(_._1)
    }

    def getTokenAmount(tokenId: TokenId): Option[U64] = tokenAmounts.get(tokenId)

    def addAlf(amount: U64): Option[Unit] = {
      alfAmount.add(amount).map(alfAmount = _)
    }

    def addToken(tokenId: TokenId, amount: U64): Option[Unit] = {
      tokenAmounts.get(tokenId) match {
        case Some(currentAmount) =>
          currentAmount.add(amount).map(tokenAmounts(tokenId) = _)
        case None =>
          tokenAmounts(tokenId) = amount
          Some(())
      }
    }

    def subAlf(amount: U64): Option[Unit] = {
      alfAmount.sub(amount).map(alfAmount = _)
    }

    def subToken(tokenId: TokenId, amount: U64): Option[Unit] = {
      tokenAmounts.get(tokenId).flatMap { currentAmount =>
        currentAmount.sub(amount).map(tokenAmounts(tokenId) = _)
      }
    }

    def add(another: BalancesPerLockup): Option[Unit] =
      Try {
        alfAmount = alfAmount.add(another.alfAmount).getOrElse(throw BalancesPerLockup.error)
        another.tokenAmounts.foreach {
          case (tokenId, amount) =>
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
        another.tokenAmounts.foreach {
          case (tokenId, amount) =>
            tokenAmounts.get(tokenId) match {
              case Some(currentAmount) =>
                tokenAmounts(tokenId) =
                  currentAmount.sub(amount).getOrElse(throw BalancesPerLockup.error)
              case None => throw BalancesPerLockup.error
            }
        }
      }.toOption

    def toAssetOutput(lockupScript: LockupScript): Option[AssetOutput] = {
      Option.when(alfAmount != U64.Zero)(
        AssetOutput(alfAmount, 0, lockupScript, tokenVector, ByteString.empty)
      )
    }
  }

  object BalancesPerLockup {
    val error: ArithmeticException = new ArithmeticException("U64")

    val empty: BalancesPerLockup = BalancesPerLockup(U64.Zero, mutable.Map.empty)

    def alf(amount: U64): BalancesPerLockup = {
      BalancesPerLockup(amount, mutable.Map.empty)
    }

    def token(id: TokenId, amount: U64): BalancesPerLockup = {
      BalancesPerLockup(U64.Zero, mutable.Map(id -> amount))
    }

    def from(output: TxOutput): BalancesPerLockup =
      BalancesPerLockup(output.amount, mutable.Map.from(output.tokens.toIterable))
  }
}
