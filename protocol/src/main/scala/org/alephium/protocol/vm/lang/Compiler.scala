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
      compileMultiContract(input).map(genCode)
    } catch {
      case e: Error => Left(e)
    }
  }

  def compileMultiContract[T](input: String): Either[Error, MultiTxContract] = {
    try {
      fastparse.parse(input, StatefulParser.multiContract(_)) match {
        case Parsed.Success(multiContract, _) => Right(multiContract.extendedContracts())
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
    def isVariadic: Boolean = false
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

  sealed trait VarInfo {
    def tpe: Type
    def isMutable: Boolean
  }
  object VarInfo {
    final case class Local(tpe: Type, isMutable: Boolean, index: Byte) extends VarInfo
    final case class Field(tpe: Type, isMutable: Boolean, index: Byte) extends VarInfo
    final case class Template(tpe: Type, index: Int) extends VarInfo {
      def isMutable: Boolean = false
    }
    final case class ArrayRef(isMutable: Boolean, ref: ArrayTransformer.ArrayRef) extends VarInfo {
      def tpe: Type = ref.tpe
    }
  }
  trait ContractFunc[Ctx <: StatelessContext] extends FuncInfo[Ctx] {
    def returnType: Seq[Type]
  }
  final case class SimpleFunc[Ctx <: StatelessContext](
      id: Ast.FuncId,
      isPublic: Boolean,
      argsType: Seq[Type],
      returnType: Seq[Type],
      index: Byte
  ) extends ContractFunc[Ctx] {
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

    @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
    def buildFor(
        config: CompilerConfig,
        multiContract: MultiTxContract,
        contractIndex: Int
    ): State[StatefulContext] = {
      val contractsTable = multiContract.contracts.map(c => c.ident -> c.funcTable).toMap
      val contract       = multiContract.get(contractIndex)
      StateForContract(
        contract.isInstanceOf[Ast.TxScript],
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

  // scalastyle:off number.of.methods
  sealed trait State[Ctx <: StatelessContext] {
    def config: CompilerConfig
    def varTable: mutable.HashMap[String, VarInfo]
    var scope: Ast.FuncId
    var varIndex: Int
    def funcIdents: immutable.Map[Ast.FuncId, ContractFunc[Ctx]]
    def contractTable: immutable.Map[Ast.TypeId, immutable.Map[Ast.FuncId, ContractFunc[Ctx]]]
    private var freshNameIndex: Int = 0
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
          val arrayRef =
            ArrayTransformer.init(this, arrayType, freshName(), isMutable, VarInfo.Local)
          val codes = expr.genCode(this) ++ arrayRef.vars.flatMap(genStoreCode).reverse
          (arrayRef, codes)
      }
    }

    def addArrayRef(
        ident: Ast.Ident,
        isMutable: Boolean,
        arrayRef: ArrayTransformer.ArrayRef
    ): Unit = {
      val sname = checkNewVariable(ident)
      varTable(sname) = VarInfo.ArrayRef(isMutable, arrayRef)
    }

    def getArrayRef(ident: Ast.Ident): ArrayTransformer.ArrayRef = {
      getVariable(ident) match {
        case info: VarInfo.ArrayRef => info.ref
        case _                      => throw Error(s"Array $ident does not exist")
      }
    }

    def setFuncScope(funcId: Ast.FuncId): Unit = {
      scope = funcId
      varIndex = 0
    }

    protected def scopedName(name: String): String = {
      if (scope == Ast.FuncId.empty) name else s"${scope.name}.$name"
    }

    def addTemplateVariable(ident: Ast.Ident, tpe: Type, index: Int): Unit = {
      val sname = checkNewVariable(ident)
      tpe match {
        case _: Type.FixedSizeArray =>
          throw Error("Template variable does not support Array yet")
        case c: Type.Contract =>
          val varType = Type.Contract.local(c.id, ident)
          varTable(sname) = VarInfo.Template(varType, index)
        case _ =>
          varTable(sname) = VarInfo.Template(tpe, index)
      }
    }
    def addFieldVariable(ident: Ast.Ident, tpe: Type, isMutable: Boolean): Unit = {
      addVariable(ident, tpe, isMutable, VarInfo.Field)
    }
    def addLocalVariable(ident: Ast.Ident, tpe: Type, isMutable: Boolean): Unit = {
      addVariable(ident, tpe, isMutable, VarInfo.Local)
    }
    def addVariable(
        ident: Ast.Ident,
        tpe: Type,
        isMutable: Boolean,
        varInfoBuild: (Type, Boolean, Byte) => VarInfo
    ): Unit = {
      val sname = checkNewVariable(ident)
      tpe match {
        case t: Type.FixedSizeArray =>
          ArrayTransformer.init(this, t, ident.name, isMutable, varInfoBuild)
          ()
        case c: Type.Contract =>
          val varType = Type.Contract.local(c.id, ident)
          varTable(sname) = varInfoBuild(varType, isMutable, varIndex.toByte)
          varIndex += 1
        case _ =>
          varTable(sname) = varInfoBuild(tpe, isMutable, varIndex.toByte)
          varIndex += 1
      }
    }

    private def checkNewVariable(ident: Ast.Ident): String = {
      val name  = ident.name
      val sname = scopedName(name)
      if (varTable.contains(name)) {
        throw Error(s"Global variable has the same name as local variable: $name")
      } else if (varTable.contains(sname)) {
        throw Error(s"Local variables have the same name: $name")
      } else if (varIndex >= State.maxVarIndex) {
        throw Error(s"Number of variables more than ${State.maxVarIndex}")
      }
      sname
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
        .filter(_.isInstanceOf[VarInfo.Local])
        .map(_.asInstanceOf[VarInfo.Local])
        .toSeq
        .sortBy(_.index)
    }

    def genLoadCode(ident: Ast.Ident): Seq[Instr[Ctx]]

    def genStoreCode(ident: Ast.Ident): Seq[Instr[Ctx]]

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
  // scalastyle:on number.of.methods

  type Contract[Ctx <: StatelessContext] = immutable.Map[Ast.FuncId, ContractFunc[Ctx]]
  final case class StateForScript(
      config: CompilerConfig,
      varTable: mutable.HashMap[String, VarInfo],
      var scope: Ast.FuncId,
      var varIndex: Int,
      funcIdents: immutable.Map[Ast.FuncId, ContractFunc[StatelessContext]],
      contractTable: immutable.Map[Ast.TypeId, Contract[StatelessContext]]
  ) extends State[StatelessContext] {
    override def eventsInfo: Seq[EventInfo] = Seq.empty

    protected def getBuiltInFunc(call: Ast.FuncId): FuncInfo[StatelessContext] = {
      BuiltIn.statelessFuncs
        .getOrElse(call.name, throw Error(s"Built-in function ${call.name} does not exist"))
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genLoadCode(ident: Ast.Ident): Seq[Instr[StatelessContext]] = {
      getVariable(ident) match {
        case _: VarInfo.Field    => throw Error("Script should not have fields")
        case v: VarInfo.Local    => Seq(LoadLocal(v.index))
        case v: VarInfo.Template => Seq(TemplateVariable(ident.name, v.tpe.toVal, v.index))
        case _: VarInfo.ArrayRef => getArrayRef(ident).vars.flatMap(genLoadCode)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genStoreCode(ident: Ast.Ident): Seq[Instr[StatelessContext]] = {
      getVariable(ident) match {
        case _: VarInfo.Field      => throw Error("Script should not have fields")
        case v: VarInfo.Local      => Seq(StoreLocal(v.index))
        case _: VarInfo.Template   => throw Error(s"Unexpected template variable: ${ident.name}")
        case ref: VarInfo.ArrayRef => ref.ref.vars.flatMap(genStoreCode)
      }
    }
  }

  final case class StateForContract(
      isTxScript: Boolean,
      config: CompilerConfig,
      varTable: mutable.HashMap[String, VarInfo],
      var scope: Ast.FuncId,
      var varIndex: Int,
      funcIdents: immutable.Map[Ast.FuncId, ContractFunc[StatefulContext]],
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
      getVariable(ident) match {
        case v: VarInfo.Field    => Seq(LoadField(v.index))
        case v: VarInfo.Local    => Seq(LoadLocal(v.index))
        case v: VarInfo.Template => Seq(TemplateVariable(ident.name, varInfo.tpe.toVal, v.index))
        case _: VarInfo.ArrayRef => getArrayRef(ident).vars.flatMap(genLoadCode)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genStoreCode(ident: Ast.Ident): Seq[Instr[StatefulContext]] = {
      getVariable(ident) match {
        case v: VarInfo.Field      => Seq(StoreField(v.index))
        case v: VarInfo.Local      => Seq(StoreLocal(v.index))
        case _: VarInfo.Template   => throw Error(s"Unexpected template variable: ${ident.name}")
        case ref: VarInfo.ArrayRef => ref.ref.vars.flatMap(genStoreCode)
      }
    }
  }

  def genLogs(logFieldLength: Int): LogInstr = {
    if (logFieldLength >= 0 && logFieldLength < Instr.allLogInstrs.length) {
      Instr.allLogInstrs(logFieldLength)
    } else {
      throw Compiler.Error(s"Max 8 fields allowed for contract events")
    }
  }
}
