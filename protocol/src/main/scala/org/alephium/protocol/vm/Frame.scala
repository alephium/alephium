package org.alephium.protocol.vm

import scala.annotation.tailrec

import org.alephium.protocol.Hash
import org.alephium.util.{AVector, Bytes}

abstract class Frame[Ctx <: Context] {
  var pc: Int
  def obj: ContractObj[Ctx]
  def opStack: Stack[Val]
  def method: Method[Ctx]
  def locals: Array[Val]
  def returnTo: AVector[Val] => ExeResult[Unit]
  def ctx: Ctx

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
    val ctx: StatefulContext
) extends Frame[StatefulContext] {
  override def methodFrame(index: Int): ExeResult[Frame[StatefulContext]] = {
    for {
      method <- getMethod(index)
      args   <- opStack.pop(method.localsType.length)
      _      <- method.check(args)
    } yield Frame.stateful(ctx, obj, method, args, opStack, opStack.push)
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
    } yield Frame.stateful(ctx, contractObj, method, args, opStack, opStack.push)
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
               obj: ContractObj[StatefulContext],
               method: Method[StatefulContext],
               args: AVector[Val],
               operandStack: Stack[Val],
               returnTo: AVector[Val] => ExeResult[Unit]): Frame[StatefulContext] = {
    val locals = method.localsType.mapToArray(_.default)
    args.foreachWithIndex((v, index) => locals(index) = v)
    new StatefulFrame(0, obj, operandStack.subStack(), method, locals, returnTo, ctx)
  }
}
