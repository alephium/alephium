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

package org.alephium.ralph

import java.nio.charset.StandardCharsets

import scala.annotation.nowarn
import scala.collection.{immutable, mutable}

import akka.util.ByteString
import fastparse.Parsed

import org.alephium.protocol.vm._
import org.alephium.ralph.Ast.{Ident, MultiContract}
import org.alephium.ralph.error.CompilerError
import org.alephium.ralph.error.CompilerError.FastParseError
import org.alephium.util.{AVector, U256}

//TODO add SourceIndex to warnings
final case class CompiledContract(
    code: StatefulContract,
    ast: Ast.Contract,
    warnings: AVector[Warning],
    debugCode: StatefulContract,
    tests: Testing.CompiledUnitTests[StatefulContext]
)
final case class CompiledScript(
    code: StatefulScript,
    ast: Ast.TxScript,
    warnings: AVector[Warning],
    debugCode: StatefulScript
)

// scalastyle:off number.of.methods
// scalastyle:off file.size.limit
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object Compiler {

  def compileAssetScript(
      input: String,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Either[Error, (StatelessScript, AVector[Warning])] =
    try {
      fastparse.parse(input, new StatelessParser(None).assetScript(_)) match {
        case Parsed.Success((script, globalState), _) =>
          val state = State.buildFor(script, globalState)(compilerOptions)
          Right((script.genCodeFull(state), state.getWarnings))
        case failure: Parsed.Failure =>
          Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }

  def compileTxScript(
      input: String,
      index: Int = 0,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Either[Error, StatefulScript] =
    compileTxScriptFull(input, index, compilerOptions).map(_.debugCode)

  def compileTxScriptFull(
      input: String,
      index: Int = 0,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Either[Error, CompiledScript] =
    compileStateful(input, _.genStatefulScript(index)(compilerOptions))

  def compileContract(
      input: String,
      index: Int = 0,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Either[Error, StatefulContract] =
    compileContractFull(input, index, compilerOptions).map(_.debugCode)

  def compileContractFull(
      input: String,
      index: Int = 0,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Either[Error, CompiledContract] =
    compileStateful(input, _.genStatefulContract(index)(compilerOptions))

  private def compileStateful[T](input: String, genCode: MultiContract => T): Either[Error, T] = {
    try {
      compileMultiContract(input).map(genCode)
    } catch {
      case e: Error => Left(e)
    }
  }

  type CompileProjectResult = (
      AVector[CompiledContract],
      AVector[CompiledScript],
      Ast.GlobalState[StatefulContext],
      AVector[Warning]
  )

  def compileProject(
      input: String,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Either[Error, CompileProjectResult] = {
    try {
      compileMultiContract(input).map { multiContract =>
        val (warnings0, compiled) = multiContract.genStatefulContracts()(compilerOptions)
        val statefulContracts     = compiled.map(_._1)
        val statefulScripts       = multiContract.genStatefulScripts()(compilerOptions)
        val unusedGlobalConstantWarning = if (compilerOptions.ignoreUnusedConstantsWarnings) {
          None
        } else {
          Some(multiContract.globalState.getUnusedGlobalConstantsWarning())
        }
        val warnings1 = unusedGlobalConstantWarning match {
          case Some(warning) => warnings0 ++ warning
          case None          => warnings0
        }
        (statefulContracts, statefulScripts, multiContract.globalState, warnings1)
      }
    } catch {
      case e: Error => Left(e)
    }
  }

  def compileMultiContract(input: String): Either[Error, MultiContract] = {
    try {
      fastparse.parse(input, new StatefulParser(None).multiContract(_)) match {
        case Parsed.Success(multiContract, _) =>
          Right(multiContract.extendedContracts())
        case failure: Parsed.Failure => Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }
  }

  trait FuncInfo[-Ctx <: StatelessContext] {
    def name: String
    def isPublic: Boolean
    def isVariadic: Boolean = false
    def usePreapprovedAssets: Boolean
    def useAssetsInContract: Ast.ContractAssetsAnnotation =
      useContractAssetsInfo.useAssetsInContract
    def usePayToContractOnly: Boolean = useContractAssetsInfo.usePayToContractOnly
    def useContractAssetsInfo: UseContractAssetsInfo
    def useUpdateFields: Boolean
    def inline: Boolean = false
    def getReturnType[C <: Ctx](inputType: Seq[Type], state: Compiler.State[C]): Seq[Type]
    def genCodeForArgs[C <: Ctx](args: Seq[Ast.Expr[C]], state: State[C]): Seq[Instr[C]] =
      args.flatMap(_.genCode(state))
    def genCode(inputType: Seq[Type]): Seq[Instr[Ctx]]
    def genCode[C <: Ctx](
        @nowarn ast: Ast.Positioned,
        @nowarn state: State[C],
        inputType: Seq[Type]
    ): Seq[Instr[Ctx]] = genCode(inputType)
    def genInlineCode[C <: Ctx](
        args: Seq[Ast.Expr[C]],
        state: Compiler.State[C],
        callAst: Ast.CallAst[C]
    ): Seq[Instr[C]] =
      ???
  }

  type Error = CompilerError.FormattableError

  object Error {
    // scalastyle:off null
    def apply(message: String, sourceIndex: Option[SourceIndex]): Error =
      new CompilerError.Default(message, sourceIndex, null)
    // scalastyle:on null
    def apply(message: String, sourceIndex: Option[SourceIndex], cause: Throwable): Error =
      new CompilerError.Default(message, sourceIndex, cause)

    def parse(failure: Parsed.Failure): Error = FastParseError(failure)
  }

  def expectOneType(ident: Ident, tpe: Seq[Type]): Type = {
    if (tpe.length == 1) {
      tpe(0)
    } else {
      throw Error(s"Try to set types $tpe for variable $ident", ident.sourceIndex)
    }
  }

  type VarInfoBuilder = (Ident, Type, Boolean, Boolean, Byte, Boolean) => VarInfo
  sealed trait VarInfo {
    def ident: Ident
    def tpe: Type
    def isMutable: Boolean
    def isUnused: Boolean
    def isGenerated: Boolean
    def isLocal: Boolean
  }
  object VarInfo {
    final case class Local(
        ident: Ident,
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        index: Byte,
        isGenerated: Boolean
    ) extends VarInfo {
      def isLocal: Boolean = true
    }
    final case class Field(
        ident: Ident,
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        index: Byte,
        isGenerated: Boolean
    ) extends VarInfo {
      def isLocal: Boolean = false
    }
    final case class MapVar(ident: Ident, tpe: Type.Map, index: Int) extends VarInfo {
      def isMutable: Boolean   = true
      def isUnused: Boolean    = false
      def isGenerated: Boolean = false
      def isLocal: Boolean     = false
    }
    final case class Template(ident: Ident, tpe: Type, index: Int, isGenerated: Boolean)
        extends VarInfo {
      def isMutable: Boolean = false
      def isUnused: Boolean  = false
      def isLocal: Boolean   = false
    }
    final case class MultipleVar[Ctx <: StatelessContext](
        ident: Ident,
        isMutable: Boolean,
        isUnused: Boolean,
        isGenerated: Boolean,
        ref: VariablesRef[Ctx]
    ) extends VarInfo {
      def tpe: Type        = ref.tpe
      def isLocal: Boolean = ref.isLocal
    }
    final case class Constant[Ctx <: StatelessContext](
        ident: Ident,
        tpe: Type,
        value: Val,
        instrs: Seq[Instr[Ctx]],
        constantDef: Option[Ast.ConstantDefinition]
    ) extends VarInfo {
      def isMutable: Boolean   = false
      def isUnused: Boolean    = false
      def isGenerated: Boolean = constantDef.isEmpty
      def isLocal: Boolean     = true
    }
    final case class InlinedArgument[Ctx <: StatelessContext](
        ident: Ident,
        tpe: Type,
        instrs: Seq[Instr[Ctx]]
    ) extends VarInfo {
      def isMutable: Boolean   = false
      def isUnused: Boolean    = false
      def isGenerated: Boolean = false
      def isLocal: Boolean     = true
    }
  }
  trait ContractFunc[Ctx <: StatelessContext] extends FuncInfo[Ctx] {
    def argsType: Seq[Type]
    def returnType: Seq[Type]
    def isStatic: Boolean = false
    def genExternalCallCode(
        state: Compiler.State[StatefulContext],
        objCodes: Seq[Instr[StatefulContext]],
        typeId: Ast.TypeId
    ): Seq[Instr[StatefulContext]] = ???
  }
  final case class SimpleFunc[Ctx <: StatelessContext](
      funcDef: Ast.FuncDef[Ctx],
      isPublic: Boolean,
      usePreapprovedAssets: Boolean,
      useContractAssetsInfo: UseContractAssetsInfo,
      useUpdateFields: Boolean,
      argsType: Seq[Type],
      returnType: Seq[Type],
      index: Byte
  ) extends ContractFunc[Ctx] {
    def name: String = funcDef.name

    override def inline: Boolean = funcDef.inline

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    override def genInlineCode[C <: Ctx](
        args: Seq[Ast.Expr[C]],
        state: Compiler.State[C],
        callAst: Ast.CallAst[C]
    ): Seq[Instr[C]] = {
      state.genInlineCode(args, funcDef.asInstanceOf[Ast.FuncDef[C]], callAst)
    }

    override def getReturnType[C <: Ctx](
        inputType: Seq[Type],
        state: Compiler.State[C]
    ): Seq[Type] = {
      if (inputType == state.resolveTypes(argsType)) {
        state.resolveTypes(returnType)
      } else {
        throw Error(
          s"Invalid args type ${quote(inputType)} for func $name, expected ${quote(argsType)}",
          None
        )
      }
    }

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      Seq(CallLocal(index))
    }

    override def genExternalCallCode(
        state: Compiler.State[StatefulContext],
        objCodes: Seq[Instr[StatefulContext]],
        typeId: Ast.TypeId
    ): Seq[Instr[StatefulContext]] = {
      if (isPublic) {
        val contractInfo = state.getContractInfo(typeId)
        val callInstr: Instr[StatefulContext] =
          if (
            contractInfo.kind == ContractKind.Interface &&
            state.isUseMethodSelector(typeId, funcDef.id)
          ) {
            CallExternalBySelector(funcDef.getMethodSelector(state.globalState))
          } else {
            CallExternal(index)
          }
        val argLength = state.flattenTypeLength(argsType)
        val retLength = state.flattenTypeLength(returnType)
        Seq(
          ConstInstr.u256(Val.U256(U256.unsafe(argLength))),
          ConstInstr.u256(Val.U256(U256.unsafe(retLength)))
        ) ++ objCodes :+ callInstr
      } else {
        throw Error(s"Call external private function of ${typeId.name}", typeId.sourceIndex)
      }
    }
  }
  object SimpleFunc {
    private def from[Ctx <: StatelessContext](
        func: Ast.FuncDef[Ctx],
        index: Byte
    ): SimpleFunc[Ctx] = {
      new SimpleFunc[Ctx](
        func,
        func.isPublic,
        func.usePreapprovedAssets,
        UseContractAssetsInfo(func.useAssetsInContract, func.usePayToContractOnly),
        func.useUpdateFields,
        func.args.map(_.tpe),
        func.rtypes,
        index
      )
    }

    @scala.annotation.tailrec
    private def getNextIndex(fromIndex: Int, preDefinedIndexes: Seq[Int]): Int = {
      if (preDefinedIndexes.contains(fromIndex)) {
        getNextIndex(fromIndex + 1, preDefinedIndexes)
      } else {
        fromIndex
      }
    }

    def from[Ctx <: StatelessContext](
        funcs: Seq[Ast.FuncDef[Ctx]],
        isInterface: Boolean
    ): Seq[SimpleFunc[Ctx]] = {
      if (isInterface) {
        val preDefinedIndexes = funcs.view
          .map(_.useMethodIndex)
          .collect { case Some(index) => index }
          .toSeq
        var fromIndex: Int = 0
        funcs.map { func =>
          func.useMethodIndex match {
            case Some(index) => from(func, index.toByte)
            case None =>
              val funcIndex = getNextIndex(fromIndex, preDefinedIndexes)
              fromIndex = funcIndex + 1
              from(func, funcIndex.toByte)
          }
        }
      } else {
        var funcIndex = 0
        funcs.map { func =>
          val simpleFunc = from(func, funcIndex.toByte)
          if (!func.inline) funcIndex += 1
          simpleFunc
        }
      }
    }
  }

  final case class EventInfo(typeId: Ast.TypeId, fieldTypes: Seq[Type]) {
    def checkFieldTypes[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        argTypes: Seq[Type],
        sourceIndex: Option[SourceIndex]
    ): Unit = {
      if (state.resolveTypes(fieldTypes) != argTypes) {
        val eventAbi = s"""${typeId.name}${fieldTypes.mkString("(", ", ", ")")}"""
        throw Error(s"Invalid args type $argTypes for event $eventAbi", sourceIndex)
      }
    }
  }

  object State {
    private[ralph] val maxVarIndex: Int = 0xff

    // scalastyle:off cyclomatic.complexity method.length
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    // we have checked the index type(U256)
    def getConstantIndex[Ctx <: StatelessContext](index: Ast.Expr[Ctx]): Ast.Expr[Ctx] = {
      index match {
        case e: Ast.Const[Ctx @unchecked] => e
        case Ast.Binop(op: ArithOperator, Ast.Const(left: Val.U256), Ast.Const(right: Val.U256)) =>
          op.calc(Seq(left, right)) match {
            case Right(value) => Ast.Const(value)
            case Left(_)      => throw Error(s"Invalid array index $index", index.sourceIndex)
          }
        case e @ Ast.Binop(op, left, right) =>
          val expr = Ast.Binop(op, getConstantIndex(left), getConstantIndex(right))
          if (expr == e) expr else getConstantIndex(expr)
        case _ => index
      }
    }
    // scalastyle:on cyclomatic.complexity

    def checkConstantIndex(index: Int, sourceIndex: Option[SourceIndex]): Unit = {
      if (index < 0 || index >= maxVarIndex) {
        throw Error(s"Invalid array index $index", sourceIndex)
      }
    }

    def getAndCheckConstantIndex[Ctx <: StatelessContext](index: Ast.Expr[Ctx]): Option[Int] = {
      getConstantIndex(index) match {
        case Ast.Const(Val.U256(v)) =>
          val idx =
            v.toInt.getOrElse(throw Compiler.Error(s"Invalid array index: $v", index.sourceIndex))
          checkConstantIndex(idx, index.sourceIndex)
          Some(idx)
        case _ => None
      }
    }

    def buildFor(
        script: Ast.AssetScript,
        globalState: Ast.GlobalState[StatelessContext]
    )(implicit compilerOptions: CompilerOptions): State[StatelessContext] = {
      val funcTable = script.funcTable(globalState)
      StateForScript(
        script.ident,
        mutable.HashMap.empty,
        0,
        funcTable,
        immutable.Map(script.ident -> ContractInfo(script, ContractKind.TxScript, funcTable)),
        globalState
      )
    }

    @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
    def buildFor(
        multiContract: MultiContract,
        contractIndex: Int
    )(implicit compilerOptions: CompilerOptions): State[StatefulContext] = {
      val contract = multiContract.get(contractIndex)
      StateForContract(
        contract.ident,
        contract.isInstanceOf[Ast.TxScript],
        mutable.HashMap.empty,
        0,
        contract.funcTable(multiContract.globalState),
        contract.eventsInfo(),
        multiContract.methodSelectorTable.getOrElse(Map.empty),
        multiContract.contractsTable,
        multiContract.globalState
      )
    }
  }

  sealed trait ContractKind extends Serializable with Product {
    def instantiable: Boolean
    def inheritable: Boolean

    override def toString(): String = productPrefix
  }
  object ContractKind {
    case object TxScript extends ContractKind {
      def instantiable: Boolean = false
      def inheritable: Boolean  = false
    }
    case object Interface extends ContractKind {
      def instantiable: Boolean = true
      def inheritable: Boolean  = true
    }
    final case class Contract(isAbstract: Boolean) extends ContractKind {
      def instantiable: Boolean = !isAbstract
      def inheritable: Boolean  = isAbstract

      override def toString(): String = {
        if (isAbstract) "Abstract Contract" else "Contract"
      }
    }
  }

  final case class ContractInfo[Ctx <: StatelessContext](
      ast: Ast.ContractT[Ctx],
      kind: ContractKind,
      funcs: immutable.Map[Ast.FuncId, ContractFunc[Ctx]]
  )

  trait CallGraph {
    def currentScope: Ast.FuncId

    // caller -> callees
    val internalCalls = mutable.HashMap.empty[Ast.FuncId, mutable.Set[Ast.FuncId]]
    def addInternalCall(callee: Ast.FuncId): Unit = {
      internalCalls.get(currentScope) match {
        case Some(callees) => callees += callee
        case None          => internalCalls.update(currentScope, mutable.Set(callee))
      }
    }
    // callee -> callers
    lazy val internalCallsReversed: mutable.Map[Ast.FuncId, mutable.ArrayBuffer[Ast.FuncId]] = {
      val reversed = mutable.Map.empty[Ast.FuncId, mutable.ArrayBuffer[Ast.FuncId]]
      internalCalls.foreachEntry { case (caller, callees) =>
        callees.foreach { callee =>
          reversed.get(callee) match {
            case None          => reversed.update(callee, mutable.ArrayBuffer(caller))
            case Some(callers) => callers.addOne(caller)
          }
        }
      }
      reversed
    }

    val externalCalls = mutable.HashMap.empty[Ast.FuncId, mutable.Set[(Ast.TypeId, Ast.FuncId)]]
    def addExternalCall(contract: Ast.TypeId, func: Ast.FuncId): Unit = {
      val funcRef = contract -> func
      externalCalls.get(currentScope) match {
        case Some(callees) => callees += funcRef
        case None          => externalCalls.update(currentScope, mutable.Set(funcRef))
      }
    }
  }

  final case class VarKey(name: String, scope: VariableScope)

  sealed trait AccessVariable
  final case class ReadVariable(variable: VarKey)  extends AccessVariable
  final case class WriteVariable(variable: VarKey) extends AccessVariable

  final case class UseContractAssetsInfo(
      useAssetsInContract: Ast.ContractAssetsAnnotation,
      usePayToContractOnly: Boolean
  ) {
    def useContractAssets: Boolean = useAssetsInContract.assetsEnabled || usePayToContractOnly
    def merge(other: UseContractAssetsInfo): UseContractAssetsInfo =
      if (useAssetsInContract.assetsEnabled) this else other
  }

  // scalastyle:off number.of.methods
  sealed trait State[Ctx <: StatelessContext]
      extends CallGraph
      with Warnings
      with Scope
      with VariableScoped
      with PhaseLike
      with Constants[Ctx]
      with Testing.State[Ctx] {
    def typeId: Ast.TypeId
    def selfContractType: Type = Type.Contract(typeId)
    def varTable: mutable.HashMap[VarKey, VarInfo]
    var allowDebug: Boolean                                = false
    var genInlineCode: Boolean                             = true
    private var allowSameVarName: Boolean                  = false
    private val inlineFuncStack: mutable.Stack[Ast.FuncId] = mutable.Stack.empty

    def setGenDebugCode(): Unit = {
      allowDebug = true
      genInlineCode = false
    }

    def setGenReleaseCode(): Unit = {
      allowDebug = false
      genInlineCode = true
    }

    val hasInterfaceFuncCallSet: mutable.Set[Ast.FuncId] = mutable.Set.empty
    def addInterfaceFuncCall(funcId: Ast.FuncId): Unit = {
      hasInterfaceFuncCallSet.addOne(funcId)
    }

    def methodSelectorTable: immutable.Map[(Ast.TypeId, Ast.FuncId), Boolean]
    @inline def isUseMethodSelector(typeId: Ast.TypeId, funcId: Ast.FuncId): Boolean =
      methodSelectorTable.getOrElse((typeId, funcId), true)

    def funcIdents: immutable.Map[Ast.FuncId, ContractFunc[Ctx]]
    def contractTable: immutable.Map[Ast.TypeId, ContractInfo[Ctx]]
    def globalState: Ast.GlobalState[Ctx]
    val accessedVars: mutable.Set[AccessVariable] = mutable.Set.empty[AccessVariable]
    def eventsInfo: Seq[EventInfo]

    @inline def getStruct(typeId: Ast.TypeId): Ast.Struct = globalState.getStruct(typeId)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def getOrCreateVariablesRef(expr: Ast.Expr[Ctx]): (VariablesRef[Ctx], Seq[Instr[Ctx]]) = {
      expr match {
        case Ast.LoadDataBySelectors(base, selectors) =>
          if (base.getType(this)(0).isMapType) {
            createVariablesRef(expr)
          } else {
            val (ref, codes) = getOrCreateVariablesRef(base)
            (ref.subRef(this, selectors), codes)
          }
        case Ast.Variable(ident)  => (getVariablesRef(ident), Seq.empty)
        case Ast.ParenExpr(inner) => getOrCreateVariablesRef(inner)
        case _                    => createVariablesRef(expr)
      }
    }

    private def createVariablesRef(
        expr: Ast.Expr[Ctx]
    ): (VariablesRef[Ctx], Seq[Instr[Ctx]]) = {
      val ref = VariablesRef.init(
        this,
        expr.getType(this)(0),
        freshName(),
        isMutable = false,
        isUnused = false,
        isLocal = true,
        isGenerated = true,
        isTemplate = false,
        VarInfo.Local.apply
      )
      val codes = expr.genCode(this) ++ ref.genStoreCode(this).reverse.flatten
      (ref, codes)
    }

    def getLocalArrayVarIndex(): Ast.Ident = {
      getLocalArrayVarIndex(
        addLocalVariable(_, Type.U256, isMutable = true, isUnused = false, isGenerated = true)
      )
    }

    def getImmFieldArrayVarIndex(): Ast.Ident = {
      getImmFieldArrayVarIndex(
        addLocalVariable(_, Type.U256, isMutable = true, isUnused = false, isGenerated = true)
      )
    }

    def getMutFieldArrayVarIndex(): Ast.Ident = {
      getMutFieldArrayVarIndex(
        addLocalVariable(_, Type.U256, isMutable = true, isUnused = false, isGenerated = true)
      )
    }

    def getSubContractIdVar(): Ast.Ident = {
      getSubContractIdVar(
        addLocalVariable(_, Type.ByteVec, isMutable = true, isUnused = false, isGenerated = true)
      )
    }

    def addVariablesRef(
        ident: Ast.Ident,
        isMutable: Boolean,
        isUnused: Boolean,
        isGenerated: Boolean,
        ref: VariablesRef[Ctx]
    ): Unit = {
      val sname   = checkNewVariable(ident)
      val varInfo = VarInfo.MultipleVar(ident, isMutable, isUnused, isGenerated, ref)
      trackAndAddVarInfo(sname, varInfo)
    }

    def getVariablesRef(ident: Ast.Ident): VariablesRef[Ctx] = {
      getVariable(ident) match {
        case v: VarInfo.MultipleVar[Ctx @unchecked] => v.ref
        case _ => throw Error(s"Array or struct ${ident.name} does not exist", ident.sourceIndex)
      }
    }

    protected def scopedName(name: String): String = {
      if (currentScope == Ast.FuncId.empty) name else scopedName(currentScope, name)
    }

    @inline private def scopedNamePrefix(scopeId: Ast.FuncId): String = s"${scopeId.name}."
    protected def scopedName(scopeId: Ast.FuncId, name: String): String = {
      s"${scopedNamePrefix(scopeId)}$name"
    }

    @inline private def addVarInfo(name: String, varInfo: VarInfo): VarKey = {
      val varKey = VarKey(name, variableScope)
      assume(!varTable.contains(varKey))
      varTable(varKey) = varInfo
      varKey
    }

    @inline private def trackAndAddVarInfo(sname: String, varInfo: VarInfo): Unit = {
      val varKey = addVarInfo(sname, varInfo)
      trackGenCodePhaseNewVars(varKey)
    }

    private[ralph] def addMapVar(ident: Ast.Ident, tpe: Type.Map, mapIndex: Int): Unit = {
      val sname = checkNewVariable(ident)
      trackAndAddVarInfo(sname, VarInfo.MapVar(ident, tpe, mapIndex))
    }

    @inline private def getGlobalVariable(name: String): Option[VarInfo] = {
      varTable.get(VarKey(name, FunctionRoot))
    }

    @inline private[ralph] def hasMapVar(ident: Ast.Ident): Boolean = {
      getGlobalVariable(ident.name).exists(_.tpe.isMapType)
    }

    def addInlinedArgument(ident: Ast.Ident, tpe: Type, instrs: Seq[Instr[Ctx]]): Unit = {
      val sname   = checkNewVariable(ident)
      val varInfo = VarInfo.InlinedArgument(ident, tpe, instrs)
      trackAndAddVarInfo(sname, varInfo)
    }

    private def genInitCodeForInlineCall(
        args: Seq[Ast.Expr[Ctx]],
        argCodes: Seq[Seq[Instr[Ctx]]],
        funcDef: Ast.FuncDef[Ctx]
    ): Seq[Instr[Ctx]] = {
      args.view.zipWithIndex.flatMap { case (argExpr, index) =>
        val code = argCodes(index)
        val arg  = funcDef.args(index)
        val tpe  = resolveType(arg.tpe)
        argExpr match {
          case _: Ast.Variable[Ctx @unchecked] | _: Ast.Const[Ctx @unchecked] =>
            tpe match {
              case _: Type.FixedSizeArray | _: Type.Struct => ()
              case tpe => addInlinedArgument(arg.ident, tpe, code)
            }
            Seq.empty[Instr[Ctx]]
          case _ =>
            addLocalVariable(arg.ident, tpe, arg.isMutable, arg.isUnused, false)
            code ++ genStoreCode(arg.ident).reverse.flatten
        }
      }.toSeq
    }

    def genInlineCode(
        args: Seq[Ast.Expr[Ctx]],
        funcDef: Ast.FuncDef[Ctx],
        callAst: Ast.CallAst[Ctx]
    ): Seq[Instr[Ctx]] = {
      if (inlineFuncStack.contains(funcDef.id)) {
        throw Error(
          s"Inline function ${quote(funcDef.name)} cannot be recursive",
          funcDef.id.sourceIndex
        )
      }

      assume(funcDef.inline && args.length == funcDef.args.length)
      val argCodes = args.map(_.genCode(this))
      withScope(callAst) {
        inlineFuncStack.push(funcDef.id)
        allowSameVarName = true
        val initCodes = genInitCodeForInlineCall(args, argCodes, funcDef)
        addLocalVarsExceptArgs(funcDef)
        val instrs0 = funcDef.body.flatMap(_.genCode(this))
        val instrs1 = if (instrs0.lastOption.contains(Return)) {
          instrs0.dropRight(1)
        } else {
          instrs0
        }
        val bodyCodes = mutable.ArrayBuffer.empty[Instr[Ctx]]
        instrs1.view.zipWithIndex.foreach { case (instr, index) =>
          instr match {
            case Return => bodyCodes.addOne(Jump(instrs1.length - index - 1))
            case _      => bodyCodes.addOne(instr)
          }
        }
        allowSameVarName = false
        inlineFuncStack.pop()
        initCodes ++ bodyCodes
      }
    }

    private def addLocalVarsExceptArgs(func: Ast.FuncDef[Ctx]): Unit = {
      val argNames = func.args.map(arg => scopedName(func.id, arg.ident.name))
      val prefix   = scopedNamePrefix(func.id)
      val varInfos = mutable.ArrayBuffer.empty[(VarKey, VarInfo, Int)]
      varTable.view
        .filter { case (key, varInfo) =>
          key.name.startsWith(prefix) &&
          !argNames.contains(key.name) &&
          !varInfo.isGenerated &&
          varInfo.isLocal
        }
        .foreach {
          case (key, info: VarInfo.Local) => varInfos.addOne((key, info, info.index.toInt))
          case (key, info @ VarInfo.MultipleVar(_, _, _, _, ref: LocalVarRef[_])) =>
            varInfos.addOne((key, info, ref.getConstantOffset()))
          case _ => ()
        }
      varInfos
        .sortBy(_._3)
        .foreach { case (key, varInfo, _) =>
          addLocalVariable(key.scope.getScopeRefPath, varInfo)
        }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def addLocalVariable(scopeRefs: Seq[Ast.Positioned], varInfo: VarInfo): Unit = {
      if (scopeRefs.isEmpty) {
        addVarInfo(varInfo)
      } else {
        withScope(scopeRefs(0))(addLocalVariable(scopeRefs.drop(1), varInfo))
      }
    }

    private def addVarInfo(varInfo: VarInfo): Unit = {
      addLocalVariable(
        varInfo.ident,
        varInfo.tpe,
        varInfo.isMutable,
        varInfo.isUnused,
        varInfo.isGenerated
      )
    }

    def addTemplateVariable(ident: Ast.Ident, tpe: Type): Unit = {
      addVariable(
        ident,
        tpe,
        isMutable = false,
        isUnused = false,
        isLocal = false,
        isGenerated = false,
        isTemplate = true,
        (ident, tpe, _, _, index, isGenerated) =>
          VarInfo.Template(ident, tpe, index.toInt, isGenerated)
      )
    }
    def addFieldVariable(
        ident: Ast.Ident,
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        isGenerated: Boolean
    ): Unit = {
      addVariable(
        ident,
        tpe,
        isMutable,
        isUnused,
        isLocal = false,
        isGenerated,
        isTemplate = false,
        VarInfo.Field.apply
      )
    }
    def addLocalVariable(
        ident: Ast.Ident,
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        isGenerated: Boolean
    ): Unit = {
      addVariable(
        ident,
        tpe,
        isMutable,
        isUnused,
        isLocal = true,
        isGenerated,
        isTemplate = false,
        VarInfo.Local.apply
      )
    }
    // scalastyle:off parameter.number
    // scalastyle:off method.length
    def addVariable(
        ident: Ast.Ident,
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        isLocal: Boolean,
        isGenerated: Boolean,
        isTemplate: Boolean,
        varInfoBuilder: Compiler.VarInfoBuilder
    ): Unit = {
      val sname = checkNewVariable(ident)
      tpe match {
        case tpe: Type.NamedType => // this should never happen
          throw Error(s"Unresolved named type $tpe", ident.sourceIndex)
        case _: Type.FixedSizeArray | _: Type.Struct =>
          VariablesRef.init(
            this,
            tpe,
            ident.name,
            isMutable,
            isUnused,
            isLocal,
            isGenerated,
            isTemplate,
            varInfoBuilder
          )
          ()
        case _ =>
          val varInfo = varInfoBuilder(
            ident,
            tpe,
            isMutable,
            isUnused,
            getAndUpdateVarIndex(isTemplate, isLocal, isMutable).toByte,
            isGenerated
          )
          trackAndAddVarInfo(sname, varInfo)
      }
    }
    // scalastyle:on parameter.number
    // scalastyle:off method.length

    private[ralph] def addTestingConstant(ident: Ast.Ident, value: Val, tpe: Type): Unit = {
      assume(tpe.toVal == value.tpe)
      val sname = checkNewVariable(ident)
      addVarInfo(sname, VarInfo.Constant(ident, tpe, value, Seq(value.toConstInstr), None))
      ()
    }

    def addConstant(
        ident: Ast.Ident,
        value: Val,
        constantDef: Option[Ast.ConstantDefinition]
    ): Unit = {
      val sname = checkNewVariable(ident)
      assume(ident.name == sname)
      val varInfo =
        VarInfo.Constant(
          ident,
          Type.fromVal(value),
          value,
          Seq(value.toConstInstr),
          constantDef
        )
      addVarInfo(sname, varInfo)
      ()
    }

    @scala.annotation.tailrec
    private def varDefinedInScopeOrParent(sname: String, scope: VariableScope): Boolean = {
      val varKey = VarKey(sname, scope)
      scope match {
        case FunctionRoot => varTable.contains(varKey)
        case child: ChildScope =>
          varTable.contains(varKey) || varDefinedInScopeOrParent(sname, child.parent)
      }
    }

    private def checkNewVariable(ident: Ast.Ident): String = {
      val name  = ident.name
      val sname = scopedName(name)
      if (getGlobalVariable(name).isDefined) {
        throw Error(s"Global variables have the same name: $name", ident.sourceIndex)
      } else if (!allowSameVarName && varDefinedInScopeOrParent(sname, variableScope)) {
        throw Error(s"Local variables have the same name: $name", ident.sourceIndex)
      } else if (currentScopeState.varIndex >= State.maxVarIndex) {
        throw Error(s"Number of variables more than ${State.maxVarIndex}", ident.sourceIndex)
      }
      sname
    }

    def getConstant(ident: Ast.Ident): VarInfo.Constant[Ctx] = {
      getVariable(ident) match {
        case v: VarInfo.Constant[Ctx @unchecked] => v
        case _ =>
          throw Error(
            s"Constant variable ${ident.name} does not exist or is used before declaration",
            ident.sourceIndex
          )
      }
    }

    @scala.annotation.tailrec
    private def getVariableInScopeOrParent(
        sname: String,
        scope: VariableScope
    ): Option[(VarKey, VarInfo)] = {
      val varKey = VarKey(sname, scope)
      scope match {
        case FunctionRoot => varTable.get(varKey).map(info => (varKey, info))
        case child: ChildScope =>
          varTable.get(varKey) match {
            case varInfo @ Some(_) => varInfo.map(info => (varKey, info))
            case None              => getVariableInScopeOrParent(sname, child.parent)
          }
      }
    }

    def getVariable(ident: Ast.Ident, isWrite: Boolean = false): VarInfo = {
      val name  = ident.name
      val sname = scopedName(ident.name)
      val (varKey, varInfo) = getVariableInScopeOrParent(sname, variableScope) match {
        case Some(variable) => variable
        case None =>
          val varKey = VarKey(name, FunctionRoot)
          varTable.get(varKey).orElse(globalState.getConstantOpt(ident)) match {
            case Some(varInfo) => (varKey, varInfo)
            case None =>
              val varName = if (isInTestContext) name else sname
              throw Error(
                s"Variable $varName is not defined in the current scope or is used before being defined",
                ident.sourceIndex
              )
          }
      }
      if (isWrite) {
        currentScopeAccessedVars.add(WriteVariable(varKey))
      } else {
        currentScopeAccessedVars.add(ReadVariable(varKey))
      }

      varInfo
    }

    def addAccessedVars(vars: Set[AccessVariable]): Unit = accessedVars.addAll(vars)

    def checkUnusedLocalVars(funcId: Ast.FuncId): Unit = {
      val prefix = scopedNamePrefix(funcId)
      val unusedVars = varTable.filter { case (varKey, varInfo) =>
        varKey.name.startsWith(prefix) &&
        !varInfo.isGenerated &&
        !varInfo.isUnused &&
        !accessedVars.contains(ReadVariable(varKey))
      }
      if (unusedVars.nonEmpty) {
        val unusedVarsInfo = unusedVars.map { case (varKey, varInfo) =>
          (varKey.name, varInfo.tpe)
        }.toSeq
        warnUnusedVariables(typeId, unusedVarsInfo)
      }

      accessedVars.filterInPlace {
        case ReadVariable(varKey) => !varKey.name.startsWith(prefix)
        case _                    => true
      }
    }

    def checkUnassignedLocalMutableVars(funcId: Ast.FuncId): Unit = {
      val prefix = scopedNamePrefix(funcId)
      val unassignedMutableVars = varTable.view
        .filter { case (varKey, varInfo) =>
          varInfo.isMutable &&
          varKey.name.startsWith(prefix) &&
          !varInfo.isGenerated &&
          !varInfo.isUnused &&
          !accessedVars.contains(WriteVariable(varKey))
        }
        .keys
        .toSeq
      if (unassignedMutableVars.nonEmpty) {
        val names = unassignedMutableVars.map(_.name).sorted.mkString(",")
        throw Compiler.Error(
          s"There are unassigned mutable local vars in function ${typeId.name}.${funcId.name}: $names",
          funcId.sourceIndex
        )
      }
      accessedVars.filterInPlace {
        case WriteVariable(varKey) => !varKey.name.startsWith(prefix)
        case _                     => true
      }
    }

    def checkUnusedMaps(): Unit = {
      val unusedMaps = varTable.filter { case (varKey, varInfo) =>
        varInfo.tpe.isMapType && !accessedVars.contains(ReadVariable(varKey)) && !accessedVars
          .contains(WriteVariable(varKey))
      }

      if (unusedMaps.nonEmpty) {
        val unusedMapsInfo = unusedMaps.map { case (varKey, varInfo) =>
          (varKey.name, varInfo.ident.sourceIndex)
        }.toSeq
        warnUnusedMaps(typeId, unusedMapsInfo)
      }
    }

    def checkUnusedFieldsAndConstants(): Unit = {
      val unusedVars = varTable.filter { case (varKey, varInfo) =>
        !varInfo.isGenerated &&
        !varInfo.isUnused &&
        !accessedVars.contains(ReadVariable(varKey)) &&
        !varInfo.tpe.isMapType
      }
      val unusedLocalConstants = mutable.ArrayBuffer.empty[(String, Option[SourceIndex])]
      val unusedFields         = mutable.ArrayBuffer.empty[(String, Option[SourceIndex])]
      unusedVars.foreach {
        case (varKey, VarInfo.Constant(_, _, _, _, Some(constantDef))) =>
          if (constantDef.definedIn(typeId)) {
            unusedLocalConstants.addOne((varKey.name, constantDef.ident.sourceIndex))
          }
        case (varKey, varInfo) if !varInfo.isLocal =>
          unusedFields.addOne((varKey.name, varInfo.ident.sourceIndex))
        case _ => ()
      }
      if (unusedLocalConstants.nonEmpty) {
        warnUnusedLocalConstants(typeId, unusedLocalConstants)
      }
      if (unusedFields.nonEmpty) {
        warnUnusedFields(typeId, unusedFields)
      }
    }

    private[ralph] def getUsedParentConstants()
        : Iterable[(Ast.TypeId, (String, Option[SourceIndex]))] = {
      val used = mutable.ArrayBuffer.empty[(Ast.TypeId, (String, Option[SourceIndex]))]
      varTable.foreach {
        case (varKey, VarInfo.Constant(_, tpe, _, _, Some(constantDef))) =>
          if (accessedVars.contains(ReadVariable(varKey))) {
            constantDef.origin match {
              case Some(originContractId) if originContractId != typeId =>
                used.addOne((originContractId, (varKey.name, tpe.sourceIndex)))
              case _ => ()
            }
          }
        case _ => ()
      }
      used
    }

    def checkUnassignedMutableFields(): Unit = {
      val unassignedMutableFields = varTable.view
        .filter { case (name, varInfo) =>
          !varInfo.isLocal &&
          !varInfo.tpe.isMapType &&
          varInfo.isMutable &&
          isTypeMutable(varInfo.tpe) &&
          !varInfo.isGenerated &&
          !varInfo.isUnused &&
          !accessedVars.contains(WriteVariable(name))
        }
        .keys
        .toSeq
      if (unassignedMutableFields.nonEmpty) {
        val names = unassignedMutableFields.map(_.name).sorted.mkString(",")
        throw Compiler.Error(
          s"There are unassigned mutable fields in contract ${typeId.name}: $names",
          typeId.sourceIndex
        )
      }
    }

    def getLocalVarSize(func: Ast.FuncId): Int = {
      varTable.view
        .filterKeys(_.name.startsWith(scopedNamePrefix(func)))
        .values
        .count(_.isInstanceOf[VarInfo.Local])
    }

    def checkArrayIndexType(index: Ast.Expr[Ctx]): Unit = {
      index.getType(this) match {
        case Seq(Type.U256) =>
        case tpe =>
          val tpeStr = if (tpe.length == 1) quote(tpe(0)) else quote(tpe)
          throw Compiler.Error(
            s"Invalid array index type $tpeStr, expected ${quote("U256")}",
            index.sourceIndex
          )
      }
    }

    def checkMapKeyType(mapType: Type.Map, index: Ast.Expr[Ctx]): Unit = {
      index.getType(this) match {
        case Seq(tpe) if tpe == mapType.key =>
        case tpe =>
          val tpeStr = if (tpe.length == 1) quote(tpe(0)) else quote(tpe)
          throw Compiler.Error(
            s"Invalid map key type $tpeStr, expected ${quote(mapType.key)}",
            index.sourceIndex
          )
      }
    }

    def genLoadCode(ident: Ast.Ident): Seq[Instr[Ctx]]

    def genLoadCode(
        ident: Ast.Ident,
        isTemplate: Boolean,
        isLocal: Boolean,
        isMutable: Boolean,
        tpe: Type,
        offset: VarOffset[Ctx]
    ): Seq[Instr[Ctx]]

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genLoadTemplateRef(ident: Ast.Ident, tpe: Type, offset: VarOffset[Ctx]): Seq[Instr[Ctx]] = {
      val index = offset.asInstanceOf[ConstantVarOffset[Ctx]].value
      Seq(TemplateVariable(ident.name, resolveType(tpe).toVal, index))
    }

    def genStoreCode(ident: Ast.Ident): Seq[Seq[Instr[Ctx]]]

    def genStoreCode(offset: VarOffset[Ctx], isLocal: Boolean): Seq[Instr[Ctx]]

    def resolveType(ident: Ast.Ident): Type = resolveType(getVariable(ident).tpe)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def resolveType(tpe: Type): Type = {
      tpe match {
        case t: Type.FixedSizeArray =>
          Type.FixedSizeArray(resolveType(t.baseType), Left(calcArraySize(t)))
        case _ => globalState.resolveType(tpe)
      }
    }

    @inline def resolveTypes(types: Seq[Type]): Seq[Type] = types.map(resolveType)
    @inline def flattenTypeLength(types: Seq[Type]): Int =
      globalState.flattenTypeLength(resolveTypes(types))

    @inline def flattenTypeMutability(tpe: Type, isMutable: Boolean): Seq[Boolean] =
      globalState.flattenTypeMutability(resolveType(tpe), isMutable)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def isTypeMutable(tpe: Type): Boolean = {
      resolveType(tpe) match {
        case t: Type.Struct =>
          val struct = getStruct(t.id)
          struct.fields.forall(field => field.isMutable && isTypeMutable(field.tpe))
        case t: Type.FixedSizeArray => isTypeMutable(t.baseType)
        case _                      => true
      }
    }

    def genFieldsInitCodes(
        fieldsMutability: Seq[Boolean],
        exprs: Seq[Ast.Expr[Ctx]]
    ): (Seq[Instr[Ctx]], Seq[Instr[Ctx]]) = {
      if (fieldsMutability.forall(identity)) { // all fields are mutable
        (Seq.empty, exprs.flatMap(_.genCode(this)))
      } else if (fieldsMutability.forall(!_)) { // all fields are immutable
        (exprs.flatMap(_.genCode(this)), Seq.empty)
      } else {
        val (initCodes, argCodes) =
          exprs.foldLeft((Seq.empty[Instr[Ctx]], Seq.empty[Seq[Instr[Ctx]]])) {
            case ((initCodes, argCodes), expr) =>
              expr.getType(this) match {
                case Seq(_: Type.FixedSizeArray) | Seq(_: Type.Struct) =>
                  val (ref, codes) = getOrCreateVariablesRef(expr)
                  (initCodes ++ codes, argCodes ++ ref.genLoadFieldsCode(this))
                case _ => (initCodes, argCodes :+ expr.genCode(this))
              }
          }
        assume(argCodes.length == fieldsMutability.length)
        val (immFields, mutFields) = argCodes.view
          .zip(fieldsMutability)
          .foldLeft((Seq.empty[Instr[Ctx]], Seq.empty[Instr[Ctx]])) {
            case ((immFields, mutFields), (instrs, isMutable)) =>
              if (isMutable) {
                (immFields, mutFields ++ instrs)
              } else {
                (immFields ++ instrs, mutFields)
              }
          }
        (initCodes ++ immFields, mutFields)
      }
    }

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
        case _ => throw Error(s"Invalid contract object id ${objId.name}", objId.sourceIndex)
      }
    }

    def getFunc(typeId: Ast.TypeId, callId: Ast.FuncId): ContractFunc[Ctx] = {
      getContractInfo(typeId).funcs
        .getOrElse(
          callId,
          throw Error(s"Function ${typeId}.${callId.name} does not exist", callId.sourceIndex)
        )
    }

    def getContractInfo(typeId: Ast.TypeId): ContractInfo[Ctx] = {
      contractTable.getOrElse(
        typeId,
        throw Error(s"Contract ${typeId.name} does not exist", typeId.sourceIndex)
      )
    }

    def getEvent(typeId: Ast.TypeId): EventInfo = {
      eventsInfo
        .find(_.typeId.name == typeId.name)
        .getOrElse(
          throw Error(s"Event ${typeId.name} does not exist", typeId.sourceIndex)
        )
    }

    def getBuiltInFunc(call: Ast.FuncId): BuiltIn.BuiltIn[Ctx]

    private def getNewFunc(call: Ast.FuncId): FuncInfo[Ctx] = {
      funcIdents.getOrElse(
        call,
        throw Error(s"Function ${call.name} does not exist", call.sourceIndex)
      )
    }

    def checkArguments(args: Seq[Ast.Argument]): Unit = {
      args.foreach(_.tpe match {
        case c: Type.NamedType =>
          resolveType(c) match {
            case c: Type.Contract => checkContractType(c.id)
            case _                =>
          }
        case _ =>
      })
    }

    def checkContractType(typeId: Ast.TypeId): Unit = {
      if (!contractTable.contains(typeId)) {
        throw Error(s"Contract ${typeId.name} does not exist", typeId.sourceIndex)
      }
    }

    def checkAssign(ident: Ast.Ident, tpe: Seq[Type]): Unit = {
      checkAssign(ident, expectOneType(ident, tpe))
    }

    def checkAssign(ident: Ast.Ident, tpe: Type): Unit = {
      val varInfo = getVariable(ident)
      if (varInfo.tpe != tpe) {
        throw Error(
          s"Assign $tpe value to $ident: ${resolveType(varInfo.tpe).toVal}",
          ident.sourceIndex
        )
      }
      if (!varInfo.isMutable) {
        throw Error(s"Assign value to immutable variable $ident", ident.sourceIndex)
      }
    }

    def checkReturn(returnType: Seq[Type], sourceIndex: Option[SourceIndex]): Unit = {
      val rtype = funcIdents(currentScope).returnType
      if (returnType != resolveTypes(rtype)) {
        throw Error(
          s"Invalid return types ${quote(returnType)} for func ${currentScope.name}, expected ${quote(rtype)}",
          sourceIndex
        )
      }
    }
  }
  // scalastyle:on number.of.methods

  type Contract[Ctx <: StatelessContext] = immutable.Map[Ast.FuncId, ContractFunc[Ctx]]
  final case class StateForScript(
      typeId: Ast.TypeId,
      varTable: mutable.HashMap[VarKey, VarInfo],
      var varIndex: Int,
      funcIdents: immutable.Map[Ast.FuncId, ContractFunc[StatelessContext]],
      contractTable: immutable.Map[Ast.TypeId, ContractInfo[StatelessContext]],
      globalState: Ast.GlobalState[StatelessContext]
  )(implicit val compilerOptions: CompilerOptions)
      extends State[StatelessContext] {
    override def eventsInfo: Seq[EventInfo] = Seq.empty

    def methodSelectorTable: Map[(Ast.TypeId, Ast.FuncId), Boolean] = ???

    def getBuiltInFunc(call: Ast.FuncId): BuiltIn.BuiltIn[StatelessContext] = {
      BuiltIn.statelessFuncs
        .getOrElse(
          call.name,
          throw Error(s"Built-in function ${call.name} does not exist", call.sourceIndex)
        )
    }

    private def genVarIndexCode(
        offset: VarOffset[StatelessContext],
        isLocal: Boolean,
        constantIndex: Byte => Instr[StatelessContext],
        varIndex: Instr[StatelessContext]
    ): Seq[Instr[StatelessContext]] = {
      if (!isLocal) {
        throw Error(s"Script should not have fields", typeId.sourceIndex)
      }

      offset match {
        case ConstantVarOffset(value) =>
          State.checkConstantIndex(value, typeId.sourceIndex)
          Seq(constantIndex(value.toByte))
        case VariableVarOffset(instrs) =>
          instrs :+ varIndex
      }
    }

    def genLoadCode(
        ident: Ast.Ident,
        isTemplate: Boolean,
        isLocal: Boolean,
        isMutable: Boolean,
        tpe: Type,
        offset: VarOffset[StatelessContext]
    ): Seq[Instr[StatelessContext]] = {
      if (isTemplate) {
        genLoadTemplateRef(ident, tpe, offset)
      } else {
        genVarIndexCode(offset, isLocal, LoadLocal.apply, LoadLocalByIndex)
      }
    }

    def genStoreCode(
        offset: VarOffset[StatelessContext],
        isLocal: Boolean
    ): Seq[Instr[StatelessContext]] =
      genVarIndexCode(offset, isLocal, StoreLocal.apply, StoreLocalByIndex)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genLoadCode(ident: Ast.Ident): Seq[Instr[StatelessContext]] = {
      getVariable(ident) match {
        case _: VarInfo.Field => throw Error("Script should not have fields", typeId.sourceIndex)
        case v: VarInfo.Local => Seq(LoadLocal(v.index))
        case v: VarInfo.Template =>
          Seq(TemplateVariable(ident.name, resolveType(v.tpe).toVal, v.index))
        case v: VarInfo.MultipleVar[StatelessContext @unchecked] => v.ref.genLoadCode(this)
        case v: VarInfo.Constant[StatelessContext @unchecked]    => v.instrs
        case _: VarInfo.MapVar =>
          throw Error("Script should not have map variables", typeId.sourceIndex)
        case v: VarInfo.InlinedArgument[StatelessContext @unchecked] => v.instrs
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genStoreCode(ident: Ast.Ident): Seq[Seq[Instr[StatelessContext]]] = {
      getVariable(ident) match {
        case _: VarInfo.Field => throw Error("Script should not have fields", typeId.sourceIndex)
        case v: VarInfo.Local => Seq(Seq(StoreLocal(v.index)))
        case _: VarInfo.Template =>
          throw Error(s"Unexpected template variable: ${ident.name}", ident.sourceIndex)
        case v: VarInfo.MultipleVar[StatelessContext @unchecked] => v.ref.genStoreCode(this)
        case _: VarInfo.Constant[StatelessContext @unchecked] =>
          throw Error(s"Unexpected constant variable: ${ident.name}", ident.sourceIndex)
        case _: VarInfo.MapVar =>
          throw Error(s"Unexpected map variable: ${ident.name}", ident.sourceIndex)
        case v: VarInfo.InlinedArgument[StatelessContext @unchecked] =>
          throw Error(s"Unexpected inlined argument: ${v.ident.name}", v.ident.sourceIndex)
      }
    }
  }

  final case class StateForContract(
      typeId: Ast.TypeId,
      isTxScript: Boolean,
      varTable: mutable.HashMap[VarKey, VarInfo],
      var varIndex: Int,
      funcIdents: immutable.Map[Ast.FuncId, ContractFunc[StatefulContext]],
      eventsInfo: Seq[EventInfo],
      methodSelectorTable: immutable.Map[(Ast.TypeId, Ast.FuncId), Boolean],
      contractTable: immutable.Map[Ast.TypeId, ContractInfo[StatefulContext]],
      globalState: Ast.GlobalState[StatefulContext]
  )(implicit val compilerOptions: CompilerOptions)
      extends State[StatefulContext] {
    def getBuiltInFunc(call: Ast.FuncId): BuiltIn.BuiltIn[StatefulContext] = {
      BuiltIn.statefulFuncs
        .getOrElse(
          call.name,
          throw Error(s"Built-in function ${call.name} does not exist", call.sourceIndex)
        )
    }

    private def genVarIndexCode(
        offset: VarOffset[StatefulContext],
        isLocal: Boolean,
        localConstantIndex: Byte => Instr[StatefulContext],
        fieldConstantIndex: Byte => Instr[StatefulContext],
        localVarIndex: Instr[StatefulContext],
        fieldVarIndex: Instr[StatefulContext]
    ): Seq[Instr[StatefulContext]] = {
      offset match {
        case ConstantVarOffset(value) =>
          State.checkConstantIndex(value, typeId.sourceIndex)
          val index = value.toByte
          if (isLocal) Seq(localConstantIndex(index)) else Seq(fieldConstantIndex(index))
        case VariableVarOffset(instrs) =>
          val instr = if (isLocal) localVarIndex else fieldVarIndex
          instrs :+ instr
      }
    }

    def genLoadCode(
        ident: Ast.Ident,
        isTemplate: Boolean,
        isLocal: Boolean,
        isMutable: Boolean,
        tpe: Type,
        offset: VarOffset[StatefulContext]
    ): Seq[Instr[StatefulContext]] = {
      if (isTemplate) {
        genLoadTemplateRef(ident, tpe, offset)
      } else {
        genVarIndexCode(
          offset,
          isLocal,
          LoadLocal.apply,
          if (isMutable) LoadMutField.apply else LoadImmField.apply,
          LoadLocalByIndex,
          if (isMutable) LoadMutFieldByIndex else LoadImmFieldByIndex
        )
      }
    }

    def genStoreCode(
        offset: VarOffset[StatefulContext],
        isLocal: Boolean
    ): Seq[Instr[StatefulContext]] = {
      genVarIndexCode(
        offset,
        isLocal,
        StoreLocal.apply,
        StoreMutField.apply,
        StoreLocalByIndex,
        StoreMutFieldByIndex
      )
    }

    private def genMapIndex(index: Int): Seq[Instr[StatefulContext]] = {
      val bytes =
        ByteString.fromArrayUnsafe(s"__map__${index}__".getBytes(StandardCharsets.US_ASCII))
      Seq(BytesConst(Val.ByteVec(bytes)))
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genLoadCode(ident: Ast.Ident): Seq[Instr[StatefulContext]] = {
      getVariable(ident) match {
        case v: VarInfo.Field =>
          if (v.isMutable) Seq(LoadMutField(v.index)) else Seq(LoadImmField(v.index))
        case v: VarInfo.Local => Seq(LoadLocal(v.index))
        case v: VarInfo.Template =>
          Seq(TemplateVariable(ident.name, resolveType(v.tpe).toVal, v.index))
        case v: VarInfo.MultipleVar[StatefulContext @unchecked]     => v.ref.genLoadCode(this)
        case v: VarInfo.Constant[StatefulContext @unchecked]        => v.instrs
        case VarInfo.MapVar(_, _, index)                            => genMapIndex(index)
        case v: VarInfo.InlinedArgument[StatefulContext @unchecked] => v.instrs
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genStoreCode(ident: Ast.Ident): Seq[Seq[Instr[StatefulContext]]] = {
      getVariable(ident) match {
        case v: VarInfo.Field => Seq(Seq(StoreMutField(v.index)))
        case v: VarInfo.Local => Seq(Seq(StoreLocal(v.index)))
        case _: VarInfo.Template =>
          throw Error(s"Unexpected template variable: ${ident.name}", ident.sourceIndex)
        case v: VarInfo.MultipleVar[StatefulContext @unchecked] => v.ref.genStoreCode(this)
        case _: VarInfo.Constant[StatefulContext @unchecked] =>
          throw Error(s"Unexpected constant variable: ${ident.name}", ident.sourceIndex)
        case _: VarInfo.MapVar =>
          throw Error(s"Unexpected map variable: ${ident.name}", ident.sourceIndex)
        case v: VarInfo.InlinedArgument[StatefulContext @unchecked] =>
          throw Error(s"Unexpected inlined argument: ${v.ident.name}", v.ident.sourceIndex)
      }
    }
  }

  def genLogs(logFieldLength: Int, sourceIndex: Option[SourceIndex]): LogInstr = {
    if (logFieldLength >= 0 && logFieldLength < Instr.allLogInstrs.length) {
      Instr.allLogInstrs(logFieldLength)
    } else {
      throw Compiler.Error(s"Contract events allow up to 8 fields", sourceIndex)
    }
  }
}
