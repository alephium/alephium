package org.alephium.protocol.vm

import org.alephium.util.{AVector, Collection}

class Frame[Ctx <: Context](var pc: Int,
                            obj: ContractObj[Ctx],
                            val opStack: Stack[Val],
                            method: Method[Ctx],
                            locals: Array[Val],
                            val returnTo: Val => ExeResult[Unit],
                            val ctx: Ctx) {
  def currentInstr: Option[Instr[Ctx]] = method.instrs.get(pc)

  def advancePC(): Unit = pc += 1

  def complete(): Unit = pc = method.instrs.length

  def isComplete: Boolean = pc == method.instrs.length

  def push(v: Val): ExeResult[Unit] = opStack.push(v)

  def pop(): ExeResult[Val] = opStack.pop()

  def popT[T](): ExeResult[T] = pop().flatMap { elem =>
    try Right(elem.asInstanceOf[T])
    catch {
      case _: ClassCastException => Left(InvalidType(elem))
    }
  }

  def getLocal(index: Int): ExeResult[Val] = {
    if (Collection.checkIndex(locals, index)) Right(locals(index)) else Left(InvalidLocalIndex)
  }

  def setLocal(index: Int, v: Val): ExeResult[Unit] = {
    if (!Collection.checkIndex(locals, index)) {
      Left(InvalidLocalIndex)
    } else if (locals(index).tpe != v.tpe) {
      Left(InvalidLocalType)
    } else {
      Right(locals.update(index, v))
    }
  }

  def getField(index: Int): ExeResult[Val] = {
    val fields = obj.fields
    if (Collection.checkIndex(fields, index)) Right(fields(index)) else Left(InvalidFieldIndex)
  }

  def setField(index: Int, v: Val): ExeResult[Unit] = {
    val fields = obj.fields
    if (!Collection.checkIndex(fields, index)) {
      Left(InvalidFieldIndex)
    } else if (fields(index).tpe != v.tpe) {
      Left(InvalidFieldType)
    } else {
      Right(fields.update(index, v))
    }
  }

  private def getMethod(index: Int): ExeResult[Method[Ctx]] = {
    obj.code.methods.get(index).toRight(InvalidMethodIndex(index))
  }

  def methodFrame(index: Int): ExeResult[Frame[Ctx]] = {
    for {
      method <- getMethod(index)
      args   <- opStack.pop(method.localsType.length)
      _      <- method.check(args)
    } yield Frame.build(ctx, obj, method, args, opStack.push)
  }

  def execute(): ExeResult[Unit] = {
    currentInstr match {
      case Some(instr) =>
        instr.runWith(this).flatMap { _ =>
          advancePC()
          execute()
        }
      case None => Right(())
    }
  }
}

object Frame {
  def build[Ctx <: Context](ctx: Ctx,
                            obj: ScriptObj[Ctx],
                            args: AVector[Val],
                            returnTo: Val => ExeResult[Unit]): Frame[Ctx] =
    build(ctx, obj, 0, args: AVector[Val], returnTo)

  def build[Ctx <: Context](ctx: Ctx,
                            obj: ContractObj[Ctx],
                            methodIndex: Int,
                            args: AVector[Val],
                            returnTo: Val => ExeResult[Unit]): Frame[Ctx] = {
    val method = obj.code.methods(methodIndex)
    build(ctx, obj, method, args, returnTo)
  }

  def build[Ctx <: Context](ctx: Ctx,
                            obj: ContractObj[Ctx],
                            method: Method[Ctx],
                            args: AVector[Val],
                            returnTo: Val => ExeResult[Unit]): Frame[Ctx] = {
    val locals = method.localsType.mapToArray(_.default)
    args.foreachWithIndex((v, index) => locals(index) = v)
    new Frame[Ctx](0, obj, Stack.ofCapacity(stackMaxSize), method, locals, returnTo, ctx)
  }
}
