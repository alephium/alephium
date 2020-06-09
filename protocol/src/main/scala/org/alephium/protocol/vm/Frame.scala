package org.alephium.protocol.vm

import org.alephium.util.{AVector, Collection}

class Frame[Ctx <: Context](var pc: Int,
                            obj: ContractObj[Ctx],
                            val opStack: Stack[Val],
                            method: Method[Ctx],
                            locals: Array[Val],
                            val returnTo: Val => Unit) {
  def currentInstr: Option[Instr[Ctx]] = method.instrs.get(pc)

  def advancePC(): Unit = pc += 1

  def complete(): Unit = pc = method.instrs.length

  def isComplete: Boolean = pc == method.instrs.length

  def push(v: Val): ExeResult[Unit] = opStack.push(v)

  def pop(): ExeResult[Val] = opStack.pop()

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
  def build[Ctx <: Context](obj: ScriptObj[Ctx],
                            args: AVector[Val],
                            returnTo: Val => Unit): Frame[Ctx] =
    build(obj, 0, args: AVector[Val], returnTo)

  def build[Ctx <: Context](obj: ContractObj[Ctx],
                            methodIndex: Int,
                            args: AVector[Val],
                            returnTo: Val => Unit): Frame[Ctx] = {
    val method = obj.code.methods(methodIndex)
    val locals = method.localsType.mapToArray(_.default)
    args.foreachWithIndex((v, index) => locals(index) = v)
    new Frame[Ctx](0, obj, Stack.ofCapacity(stackMaxSize), method, locals, returnTo)
  }
}
