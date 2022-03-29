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

package org.alephium.protocol.vm.lang

import scala.collection.{immutable, mutable}

import fastparse.Parsed

import org.alephium.protocol.config.CompilerConfig
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Ast.MultiTxContract
import org.alephium.util.AVector

object Compiler {
  def compileAssetScript(
      input: String
  )(implicit config: CompilerConfig): Either[Error, StatelessScript] =
    try {
      fastparse.parse(input, StatelessParser.assetScript(_)) match {
        case Parsed.Success(script, _) =>
          val state = State.buildFor(config, script)
          Right(script.genCode(state))
        case failure: Parsed.Failure =>
          Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }

  def compileTxScript(input: String)(implicit
      config: CompilerConfig
  ): Either[Error, StatefulScript] =
    compileTxScript(input, 0)

  def compileTxScript(input: String, index: Int)(implicit
      config: CompilerConfig
  ): Either[Error, StatefulScript] =
    compileTxScriptFull(input, index).map(_._1)

  def compileTxScriptFull(input: String)(implicit
      config: CompilerConfig
  ): Either[Error, (StatefulScript, Ast.TxScript)] =
    compileTxScriptFull(input, 0)

  def compileTxScriptFull(input: String, index: Int)(implicit
      config: CompilerConfig
  ): Either[Error, (StatefulScript, Ast.TxScript)] =
    compileStateful(input, _.genStatefulScript(config, index))

  def compileContract(input: String)(implicit
      config: CompilerConfig
  ): Either[Error, StatefulContract] =
    compileContract(input, 0)

  def compileContract(input: String, index: Int)(implicit
      config: CompilerConfig
  ): Either[Error, StatefulContract] =
    compileContractFull(input, index).map(_._1)

  def compileContractFull(input: String)(implicit
      config: CompilerConfig
  ): Either[Error, (StatefulContract, Ast.TxContract)] =
    compileContractFull(input, 0)

  def compileContractFull(input: String, index: Int)(implicit
      config: CompilerConfig
  ): Either[Error, (StatefulContract, Ast.TxContract)] =
    compileStateful(input, _.genStatefulContract(config, index))

  private def compileStateful[T](input: String, genCode: MultiTxContract => T): Either[Error, T] = {
    try {
      fastparse.parse(input, StatefulParser.multiContract(_)) match {
        case Parsed.Success(multiContract, _) => Right(genCode(multiContract.extendedContracts()))
        case failure: Parsed.Failure          => Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }
  }

  def compileState(stateRaw: String): Either[Error, AVector[Val]] = {
    try {
      fastparse.parse(stateRaw, StatefulParser.state(_)) match {
        case Parsed.Success(state, _) => Right(AVector.from(state.map(_.v)))
        case failure: Parsed.Failure  => Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }
  }

  trait FuncInfo[-Ctx <: StatelessContext] {
    def name: String
    def isPublic: Boolean
    def getReturnType(inputType: Seq[Type]): Seq[Type]
    def genCode(inputType: Seq[Type]): Seq[Instr[Ctx]]
    def genExternalCallCode(typeId: Ast.TypeId): Seq[Instr[StatefulContext]]
  }

  final case class Error(message: String) extends Exception(message)
  object Error {
    def parse(failure: Parsed.Failure): Error = Error(s"Parser failed: $failure")
  }

  def expectOneType(ident: Ast.Ident, tpe: Seq[Type]): Type = {
    if (tpe.length == 1) {
      tpe(0)
    } else {
      throw Error(s"Try to set types $tpe for varialbe $ident")
    }
  }

  final case class VarInfo(tpe: Type, isMutable: Boolean, index: Byte)
  final case class SimpleFunc[Ctx <: StatelessContext](
      val id: Ast.FuncId,
      val isPublic: Boolean,
      argsType: Seq[Type],
      val returnType: Seq[Type],
      index: Byte
  ) extends FuncInfo[Ctx] {
    def name: String = id.name

    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType == argsType) {
        returnType
      } else {
        throw Error(s"Invalid args type $inputType for func $name")
      }
    }

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      Seq(CallLocal(index))
    }

    override def genExternalCallCode(typeId: Ast.TypeId): Seq[Instr[StatefulContext]] = {
      if (isPublic) {
        Seq(CallExternal(index))
      } else {
        throw Error(s"Call external private function of $typeId")
      }
    }
  }
  object SimpleFunc {
    def from[Ctx <: StatelessContext](funcs: Seq[Ast.FuncDef[Ctx]]): Seq[SimpleFunc[Ctx]] = {
      funcs.view.zipWithIndex.map { case (func, index) =>
        new SimpleFunc[Ctx](
          func.id,
          func.isPublic,
          func.args.map(_.tpe),
          func.rtypes,
          index.toByte
        )
      }.toSeq
    }
  }

  final case class EventInfo(typeId: Ast.TypeId, fieldTypes: Seq[Type]) {
    def checkFieldTypes(argTypes: Seq[Type]): Unit = {
      if (fieldTypes != argTypes) {
        val eventAbi = s"""${typeId.name}${fieldTypes.mkString("(", ", ", ")")}"""
        throw Error(s"Invalid args type $argTypes for event $eventAbi")
      }
    }
  }

  object State {
    private val maxVarIndex: Int = 0xff

    def buildFor(config: CompilerConfig, script: Ast.AssetScript): State[StatelessContext] =
      StateForScript(
        config,
        mutable.HashMap.empty,
        Ast.FuncId.empty,
        0,
        script.funcTable,
        immutable.Map(script.ident -> script.funcTable)
      )

    def buildFor(
        config: CompilerConfig,
        multiContract: MultiTxContract,
        contractIndex: Int
    ): State[StatefulContext] = {
      val contractsTable = multiContract.contracts.map(c => c.ident -> c.funcTable).toMap
      val contract       = multiContract.get(contractIndex)
      StateForContract(
        config,
        mutable.HashMap.empty,
        Ast.FuncId.empty,
        0,
        contract.funcTable,
        contract.eventsInfo(),
        contractsTable
      )
    }
  }

  trait State[Ctx <: StatelessContext] {
    def config: CompilerConfig
    def varTable: mutable.HashMap[String, VarInfo]
    var scope: Ast.FuncId
    var varIndex: Int
    def funcIdents: immutable.Map[Ast.FuncId, SimpleFunc[Ctx]]
    def contractTable: immutable.Map[Ast.TypeId, immutable.Map[Ast.FuncId, SimpleFunc[Ctx]]]
    private var freshNameIndex: Int                               = 0
    val arrayRefs: mutable.Map[String, ArrayTransformer.ArrayRef] = mutable.Map.empty
    def eventsInfo: Seq[EventInfo]

    @inline final def freshName(): String = {
      val name = s"_generated#$freshNameIndex"
      freshNameIndex += 1
      name
    }

    @SuppressWarnings(
      Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Recursion")
    )
    def getOrCreateArrayRef(
        expr: Ast.Expr[Ctx],
        isMutable: Boolean
    ): (ArrayTransformer.ArrayRef, Seq[Instr[Ctx]]) = {
      expr match {
        case Ast.ArrayElement(array, index) =>
          val idx               = Ast.getConstantArrayIndex(index)
          val (arrayRef, codes) = getOrCreateArrayRef(array, isMutable)
          val subArrayRef       = arrayRef.subArray(idx)
          (subArrayRef, codes)
        case Ast.Variable(ident)  => (getArrayRef(ident), Seq.empty)
        case Ast.ParenExpr(inner) => getOrCreateArrayRef(inner, isMutable)
        case _ =>
          val arrayType = expr.getType(this)(0).asInstanceOf[Type.FixedSizeArray]
          val arrayRef  = ArrayTransformer.ArrayRef.init(this, arrayType, freshName(), isMutable)
          val codes     = expr.genCode(this) ++ arrayRef.vars.map(genStoreCode).reverse
          (arrayRef, codes)
      }
    }

    def addArrayRef(ident: Ast.Ident, arrayRef: ArrayTransformer.ArrayRef): Unit = {
      val sname = scopedName(ident.name)
      assume(!arrayRefs.contains(sname))
      arrayRefs(sname) = arrayRef
    }

    def getArrayRef(ident: Ast.Ident): ArrayTransformer.ArrayRef = {
      val sname = scopedName(ident.name)
      arrayRefs.getOrElse(
        ident.name,
        arrayRefs.getOrElse(sname, throw Error(s"Array $ident does not exist"))
      )
    }

    def setFuncScope(funcId: Ast.FuncId): Unit = {
      scope = funcId
      varIndex = 0
    }

    protected def scopedName(name: String): String = {
      if (scope == Ast.FuncId.empty) name else s"${scope.name}.$name"
    }

    def addVariable(ident: Ast.Ident, tpe: Type, isMutable: Boolean): Unit = {
      val name  = ident.name
      val sname = scopedName(name)
      if (varTable.contains(name)) {
        throw Error(s"Global variable has the same name as local variable: $name")
      } else if (varTable.contains(sname)) {
        throw Error(s"Local variables have the same name: $name")
      } else if (varIndex >= State.maxVarIndex) {
        throw Error(s"Number of variables more than ${State.maxVarIndex}")
      } else {
        tpe match {
          case _: Type.FixedSizeArray =>
            varTable(sname) = VarInfo(tpe, isMutable, State.maxVarIndex.toByte)
          case c: Type.Contract =>
            val varType = Type.Contract.local(c.id, ident)
            varTable(sname) = VarInfo(varType, isMutable, varIndex.toByte)
            varIndex += 1
          case _ =>
            varTable(sname) = VarInfo(tpe, isMutable, varIndex.toByte)
            varIndex += 1
        }
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
      varTable.view
        .filterKeys(_.startsWith(func.name))
        .values
        .filter(_.index != State.maxVarIndex.toByte)
        .toSeq
        .sortBy(_.index)
    }

    def genLoadCode(ident: Ast.Ident): Seq[Instr[Ctx]]

    def genStoreCode(ident: Ast.Ident): Instr[Ctx]

    def isField(ident: Ast.Ident): Boolean = varTable.contains(ident.name)

    def getType(ident: Ast.Ident): Type = getVariable(ident).tpe

    def getFunc(call: Ast.FuncId): FuncInfo[Ctx] = {
      if (call.isBuiltIn) {
        getBuiltInFunc(call)
      } else {
        getNewFunc(call)
      }
    }

    def getContract(objId: Ast.Ident): Ast.TypeId = {
      getVariable(objId).tpe match {
        case c: Type.Contract => c.id
        case _                => throw Error(s"Invalid contract object id ${objId.name}")
      }
    }

    def getFunc(typeId: Ast.TypeId, callId: Ast.FuncId): FuncInfo[Ctx] = {
      contractTable
        .getOrElse(typeId, throw Error(s"Contract ${typeId.name} does not exist"))
        .getOrElse(callId, throw Error(s"Function ${typeId}.${callId.name} does not exist"))
    }

    def getEvent(typeId: Ast.TypeId): EventInfo = {
      eventsInfo
        .find(_.typeId == typeId)
        .getOrElse(
          throw Error(s"Event ${typeId.name} does not exist")
        )
    }

    protected def getBuiltInFunc(call: Ast.FuncId): FuncInfo[Ctx]

    private def getNewFunc(call: Ast.FuncId): FuncInfo[Ctx] = {
      funcIdents.getOrElse(call, throw Error(s"Function ${call.name} does not exist"))
    }

    def checkArguments(args: Seq[Ast.Argument]): Unit = {
      args.foreach(_.tpe match {
        case c: Type.Contract => checkContractType(c.id)
        case _                =>
      })
    }

    def checkContractType(typeId: Ast.TypeId): Unit = {
      if (!contractTable.contains(typeId)) {
        throw Error(s"Contract ${typeId.name} does not exist")
      }
    }

    def checkAssign(ident: Ast.Ident, tpe: Seq[Type]): Unit = {
      checkAssign(ident, expectOneType(ident, tpe))
    }

    def checkAssign(ident: Ast.Ident, tpe: Type): Unit = {
      val varInfo = getVariable(ident)
      if (varInfo.tpe != tpe) throw Error(s"Assign $tpe value to $ident: ${varInfo.tpe.toVal}")
      if (!varInfo.isMutable) throw Error(s"Assign value to immutable variable $ident")
    }

    def checkReturn(returnType: Seq[Type]): Unit = {
      val rtype = funcIdents(scope).returnType
      if (returnType != rtype) {
        throw Error(s"Invalid return types: expected $rtype, got $returnType")
      }
    }
  }

  type Contract[Ctx <: StatelessContext] = immutable.Map[Ast.FuncId, SimpleFunc[Ctx]]
  final case class StateForScript(
      config: CompilerConfig,
      varTable: mutable.HashMap[String, VarInfo],
      var scope: Ast.FuncId,
      var varIndex: Int,
      funcIdents: immutable.Map[Ast.FuncId, SimpleFunc[StatelessContext]],
      contractTable: immutable.Map[Ast.TypeId, Contract[StatelessContext]]
  ) extends State[StatelessContext] {
    override def eventsInfo: Seq[EventInfo] = Seq.empty

    protected def getBuiltInFunc(call: Ast.FuncId): FuncInfo[StatelessContext] = {
      BuiltIn.statelessFuncs
        .getOrElse(call.name, throw Error(s"Built-in function ${call.name} does not exist"))
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genLoadCode(ident: Ast.Ident): Seq[Instr[StatelessContext]] = {
      val varInfo = getVariable(ident)
      if (varInfo.index == -1) { // variable for array
        getArrayRef(ident).vars.flatMap(genLoadCode)
      } else {
        if (isField(ident)) {
          throw Error(s"Loading state by ${ident.name} in a stateless context")
        } else {
          Seq(LoadLocal(varInfo.index))
        }
      }
    }

    def genStoreCode(ident: Ast.Ident): Instr[StatelessContext] = {
      val varInfo = getVariable(ident)
      if (isField(ident)) {
        throw Error(s"Storing state by ${ident.name} in a stateless context")
      } else {
        StoreLocal(varInfo.index)
      }
    }
  }

  final case class StateForContract(
      config: CompilerConfig,
      varTable: mutable.HashMap[String, VarInfo],
      var scope: Ast.FuncId,
      var varIndex: Int,
      funcIdents: immutable.Map[Ast.FuncId, SimpleFunc[StatefulContext]],
      eventsInfo: Seq[EventInfo],
      contractTable: immutable.Map[Ast.TypeId, Contract[StatefulContext]]
  ) extends State[StatefulContext] {
    protected def getBuiltInFunc(call: Ast.FuncId): FuncInfo[StatefulContext] = {
      BuiltIn.statefulFuncs
        .getOrElse(call.name, throw Error(s"Built-in function ${call.name} does not exist"))
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genLoadCode(ident: Ast.Ident): Seq[Instr[StatefulContext]] = {
      val varInfo = getVariable(ident)
      if (varInfo.index == -1) { // variable for array
        getArrayRef(ident).vars.flatMap(genLoadCode)
      } else {
        if (isField(ident)) {
          Seq(LoadField(varInfo.index))
        } else {
          Seq(LoadLocal(varInfo.index))
        }
      }
    }

    def genStoreCode(ident: Ast.Ident): Instr[StatefulContext] = {
      val varInfo = getVariable(ident)
      if (isField(ident)) {
        StoreField(varInfo.index)
      } else {
        StoreLocal(varInfo.index)
      }
    }
  }
}
