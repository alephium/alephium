package org.alephium.protocol.vm.lang

import scala.collection.{immutable, mutable}

import fastparse.Parsed

import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Ast.MultiTxContract

object Compiler {
  def compileAssetScript(input: String): Either[Error, StatelessScript] =
    try {
      fastparse.parse(input, StatelessParser.assetScript(_)) match {
        case Parsed.Success(script, _) =>
          val state = State.buildFor(script)
          Right(script.genCode(state))
        case failure: Parsed.Failure =>
          Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }

  def compileTxScript(input: String, index: Int): Either[Error, StatefulScript] = {
    compileOneOfContract(input, index).flatMap { contract =>
      if (contract.fields.nonEmpty) {
        Left(Error(s"Tx script does not have any fields"))
      } else {
        Right(StatefulScript(contract.methods))
      }
    }
  }

  def compileContract(input: String): Either[Error, StatefulContract] =
    compileOneOfContract(input, 0)

  def compileOneOfContract(input: String, index: Int): Either[Error, StatefulContract] =
    try {
      fastparse.parse(input, StatefulParser.multiContract(_)) match {
        case Parsed.Success(multiContract, _) =>
          val state = State.buildFor(multiContract, index)
          Right(multiContract.genCode(state, index))
        case failure: Parsed.Failure =>
          Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }

  trait FuncInfo[-Ctx <: StatelessContext] {
    def name: String
    def getReturnType(inputType: Seq[Type]): Seq[Type]
    def genCode(inputType: Seq[Type]): Seq[Instr[Ctx]]
    def genCode(objId: Ast.Ident): Seq[Instr[Ctx]]
  }

  final case class Error(message: String) extends Exception(message)
  object Error {
    def parse(failure: Parsed.Failure): Error = Error(s"Parser failed: $failure")
  }

  def expectOneType(ident: Ast.Ident, tpe: Seq[Type]): Type = {
    if (tpe.length == 1) tpe(0)
    else throw Error(s"Try to set types $tpe for varialbe $ident")
  }

  final case class VarInfo(tpe: Type, isMutable: Boolean, index: Byte)
  class SimpleFunc[Ctx <: StatelessContext](
      val id: Ast.FuncId,
      argsType: Seq[Type],
      val returnType: Seq[Type],
      index: Byte
  ) extends FuncInfo[Ctx] {
    def name: String = id.name

    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType == argsType) returnType
      else throw Error(s"Invalid args type $inputType for builtin func $name")
    }

    override def genCode(inputType: Seq[Type]): Seq[Instr[Ctx]] = {
      Seq(CallLocal(index))
    }

    override def genCode(objId: Ast.Ident): Seq[Instr[Ctx]] = {
      Seq(CallExternal(index))
    }
  }
  object SimpleFunc {
    def from[Ctx <: StatelessContext](funcs: Seq[Ast.FuncDef[Ctx]]): Seq[SimpleFunc[Ctx]] = {
      funcs.view.zipWithIndex.map {
        case (func, index) =>
          new SimpleFunc[Ctx](func.id, func.args.map(_.tpe), func.rtypes, index.toByte)
      }.toSeq
    }
  }

  object State {
    def buildFor(script: Ast.AssetScript): State[StatelessContext] =
      StateForScript(mutable.HashMap.empty,
                     Ast.FuncId.empty,
                     0,
                     script.funcTable,
                     immutable.Map(script.ident -> script.funcTable))

    def buildFor(multiContract: MultiTxContract, contractIndex: Int): State[StatefulContext] = {
      val contractTable = multiContract.contracts.map(c => c.ident -> c.funcTable).toMap
      StateForContract(mutable.HashMap.empty,
                       Ast.FuncId.empty,
                       0,
                       multiContract.get(contractIndex).funcTable,
                       contractTable)
    }
  }

  trait State[Ctx <: StatelessContext] {
    def varTable: mutable.HashMap[String, VarInfo]
    var scope: Ast.FuncId
    var varIndex: Int
    def funcIdents: immutable.Map[Ast.FuncId, SimpleFunc[Ctx]]
    def contractTable: immutable.Map[Ast.TypeId, immutable.Map[Ast.FuncId, SimpleFunc[Ctx]]]

    def setFuncScope(funcId: Ast.FuncId): Unit = {
      scope    = funcId
      varIndex = 0
    }

    def addVariable(ident: Ast.Ident, tpe: Seq[Type], isMutable: Boolean): Unit = {
      addVariable(ident, expectOneType(ident, tpe), isMutable)
    }

    private def scopedName(name: String): String = {
      if (scope == Ast.FuncId.empty) name else s"${scope.name}.$name"
    }

    def addVariable(ident: Ast.Ident, tpe: Type, isMutable: Boolean): Unit = {
      val name  = ident.name
      val sname = scopedName(name)
      if (varTable.contains(name)) {
        throw Error(s"Global variable has the same name as local variable: $name")
      } else if (varTable.contains(sname)) {
        throw Error(s"Local variables have the same name: $name")
      } else if (varIndex >= 0xFF) {
        throw Error(s"Number of variables more than ${0xFF}")
      } else {
        val varType = tpe match {
          case c: Type.Contract => Type.Contract.local(c.id, ident)
          case _                => tpe
        }
        varTable(sname) = VarInfo(varType, isMutable, varIndex.toByte)
        varIndex += 1
      }
    }

    def getVariable(ident: Ast.Ident): VarInfo = {
      val name  = ident.name
      val sname = scopedName(ident.name)
      varTable.getOrElse(
        sname,
        varTable.getOrElse(name, throw Error(s"Variable $sname does not exist"))
      )
    }

    def getLocalVars(func: Ast.FuncId): Seq[VarInfo] = {
      varTable.view.filterKeys(_.startsWith(func.name)).values.toSeq.sortBy(_.index)
    }

    def genCode(ident: Ast.Ident): Instr[StatelessContext] = {
      val varInfo = getVariable(ident)
      if (isField(ident)) StoreField(varInfo.index.toByte)
      else StoreLocal(varInfo.index.toByte)
    }

    def isField(ident: Ast.Ident): Boolean = varTable.contains(ident.name)

    def getType(ident: Ast.Ident): Type = getVariable(ident).tpe

    def getFunc(call: Ast.FuncId): FuncInfo[Ctx] = {
      if (call.isBuiltIn) getBuiltInFunc(call)
      else getNewFunc(call)
    }

    def getContract(objId: Ast.Ident): Ast.TypeId = {
      getVariable(objId).tpe match {
        case c: Type.Contract => c.id
        case _                => throw Error(s"Invalid contract object id ${objId.name}")
      }
    }

    def getFunc(objId: Ast.Ident, callId: Ast.FuncId): FuncInfo[Ctx] = {
      val contract = getContract(objId)
      contractTable(contract)
        .getOrElse(callId, throw Error(s"Function ${objId.name}.${callId.name} does not exist"))
    }

    protected def getBuiltInFunc(call: Ast.FuncId): FuncInfo[Ctx]

    private def getNewFunc(call: Ast.FuncId): FuncInfo[Ctx] = {
      funcIdents.getOrElse(call, throw Error(s"Function ${call.name} does not exist"))
    }

    def checkAssign(ident: Ast.Ident, tpe: Seq[Type]): Unit = {
      checkAssign(ident, expectOneType(ident, tpe))
    }

    def checkAssign(ident: Ast.Ident, tpe: Type): Unit = {
      val varInfo = getVariable(ident)
      if (varInfo.tpe != tpe) throw Error(s"Assign $tpe value to $ident: ${varInfo.tpe}")
      if (!varInfo.isMutable) throw Error(s"Assign value to immutable variable $ident")
    }

    def checkReturn(returnType: Seq[Type]): Unit = {
      val rtype = funcIdents(scope).returnType
      if (returnType != rtype)
        throw Compiler.Error(s"Invalid return types: expected $rtype, got $returnType")
    }
  }

  final case class StateForScript(
      varTable: mutable.HashMap[String, VarInfo],
      var scope: Ast.FuncId,
      var varIndex: Int,
      val funcIdents: immutable.Map[Ast.FuncId, SimpleFunc[StatelessContext]],
      val contractTable: immutable.Map[Ast.TypeId,
                                       immutable.Map[Ast.FuncId, SimpleFunc[StatelessContext]]])
      extends State[StatelessContext] {
    protected def getBuiltInFunc(call: Ast.FuncId): FuncInfo[StatelessContext] = {
      BuiltIn.funcs
        .getOrElse(call.name, throw Error(s"Built-in function ${call.name} does not exist"))
    }
  }

  final case class StateForContract(
      varTable: mutable.HashMap[String, VarInfo],
      var scope: Ast.FuncId,
      var varIndex: Int,
      val funcIdents: immutable.Map[Ast.FuncId, SimpleFunc[StatefulContext]],
      val contractTable: immutable.Map[Ast.TypeId,
                                       immutable.Map[Ast.FuncId, SimpleFunc[StatefulContext]]])
      extends State[StatefulContext] {
    protected def getBuiltInFunc(call: Ast.FuncId): FuncInfo[StatefulContext] = {
      BuiltIn.funcs
        .getOrElse(call.name, throw Error(s"Built-in function ${call.name} does not exist"))
    }
  }
}
