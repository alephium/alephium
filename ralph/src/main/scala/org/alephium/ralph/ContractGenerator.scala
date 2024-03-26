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

import scala.collection.immutable
import scala.collection.mutable

import org.alephium.protocol.vm._
import org.alephium.serde.serialize
import org.alephium.util.{AVector, U256}

final class ContractGenerator(state: Compiler.State[StatefulContext], tpe: Type) {
  import ContractGenerator._

  private def getPathAndArgs(selectors: Seq[FieldSelector]): (String, Seq[String]) = {
    val (path, args) = selectors.foldLeft(("", Seq.empty[String])) {
      case ((path, indexes), selector) =>
        selector match {
          case FieldName(name) => (s"$path.$name", indexes)
          case ArrayIndex =>
            val index = s"index${indexes.length}"
            (s"$path[$index]", indexes :+ s"$index: U256")
        }
    }
    (path.drop(1), args)
  }

  private def genGetFunc(tpe: Type, funcIndex: Int, selectors: Seq[FieldSelector]): String = {
    val (path, args) = getPathAndArgs(selectors)
    val argList      = if (args.isEmpty) "" else args.mkString(", ")
    s"""
       |pub fn f$funcIndex($argList) -> ${getTypeSignature(tpe)} {
       |  return $path
       |}
       |""".stripMargin
  }

  private lazy val parentContractIdFieldName = "parentContractId"
  private def genCheckCallerFunc(funcIndex: Int): String = {
    val callerContractIdName = "callerContractId"
    s"""
       |fn f$funcIndex($callerContractIdName: ByteVec) -> () {
       |  checkCaller!($callerContractIdName == $parentContractIdFieldName, 0)
       |}
       |""".stripMargin
  }

  private def genSetFunc(
      tpe: Type,
      funcIndex: Int,
      checkFuncIndex: Int,
      selectors: Seq[FieldSelector]
  ): String = {
    val (path, args) = getPathAndArgs(selectors)
    val argList      = if (args.isEmpty) "" else s", ${args.mkString(", ")}"
    val newValueName = "newValue"
    s"""
       |@using(updateFields = true)
       |pub fn f$funcIndex($newValueName: ${getTypeSignature(tpe)}$argList) -> () {
       |  f$checkFuncIndex(callerContractId!())
       |  $path = $newValueName
       |}
       |""".stripMargin
  }

  private lazy val contractFields = Seq(
    Ast.StructField(Ast.Ident(DefaultFieldName), isMutable = true, tpe),
    Ast.StructField(
      Ast.Ident(parentContractIdFieldName),
      isMutable = false,
      tpe = Type.ByteVec
    )
  )

  private def genDestroyFunc(checkFuncIndex: Int): String = {
    val addressName = "address"
    s"""
       |@using(assetsInContract = true)
       |pub fn destroy($addressName: Address) -> () {
       |  f$checkFuncIndex(callerContractId!())
       |  destroySelf!($addressName)
       |}
       |""".stripMargin
  }

  private def genContractFields: String = {
    contractFields.view
      .map { f =>
        val mut = if (f.isMutable) "mut" else ""
        s"$mut ${f.ident.name}: ${getTypeSignature(f.tpe)}"
      }
      .mkString(", ")
  }

  private val loadFuncIndex  = mutable.Map.empty[Seq[FieldSelector], Int]
  private val storeFuncIndex = mutable.Map.empty[Seq[FieldSelector], Int]
  private val allFuncs       = mutable.ArrayBuffer.empty[String]

  private def genFuncs: (FuncIndex, FuncIndex, Seq[String]) = {
    val flattenedFields = contractFields
      .dropRight(1)
      .flatMap(f =>
        flattenFields(state, state.resolveType(f.tpe), f.isMutable, Seq(FieldName(f.name)))
      )
    val checkCallerFuncIndex = allFuncs.length
    allFuncs.addOne(genCheckCallerFunc(checkCallerFuncIndex))
    flattenedFields.foreach { field =>
      val loadIndex = allFuncs.length
      loadFuncIndex += (field.selectors -> loadIndex)
      allFuncs.addOne(genGetFunc(field.tpe, loadIndex, field.selectors))
      if (field.isMutable && state.isTypeMutable(field.tpe)) {
        val storeIndex = allFuncs.length
        storeFuncIndex += (field.selectors -> storeIndex)
        allFuncs.addOne(genSetFunc(field.tpe, storeIndex, checkCallerFuncIndex, field.selectors))
      }
    }
    allFuncs.addOne(genDestroyFunc(checkCallerFuncIndex))
    (loadFuncIndex.toMap, storeFuncIndex.toMap, allFuncs.toSeq)
  }

  private def getContractName: String = {
    val typeSignature = getTypeSignature(tpe)
    val baseName      = s"ContractWrapper${typeSignature.replaceAll("[\\[;\\]]", "_")}"
    val usedTypeName =
      state.contractTable.view.keys.map(_.name).toSeq ++ state.globalState.structs.map(_.name)
    freshName(baseName, usedTypeName)
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def genContract: WrapperContract = {
    val (loadFuncIndex, storeFuncIndex, allFuncs) = genFuncs
    if (allFuncs.length > Byte.MaxValue) {
      throw Compiler.Error(
        s"The number of contract functions generated for type $tpe exceeds the maximum value",
        None
      )
    }
    val contractCode =
      s"""
         |Contract $getContractName($genContractFields) {
         |  ${allFuncs.mkString("\n")}
         |}
         |""".stripMargin
    val multiContract = Compiler.compileMultiContract(contractCode) match {
      case Left(error)          => throw error
      case Right(multiContract) => multiContract
    }
    val contract = multiContract.contracts(0).asInstanceOf[Ast.Contract]
    val newState = Compiler.StateForContract(
      contract.ident,
      isTxScript = false,
      mutable.HashMap.empty,
      0,
      contract.funcTable(state.globalState),
      Seq.empty,
      state.contractTable,
      state.globalState
    )(CompilerOptions.Default)
    contract.check(newState)
    assume(newState.warnings.isEmpty)
    val compiled = contract.genCode(newState)
    WrapperContract(tpe, contractCode, contract, compiled, loadFuncIndex, storeFuncIndex)
  }
}

object ContractGenerator {
  private val DefaultFieldName      = "value"
  private[ralph] val ContractsCache = mutable.Map.empty[Type, WrapperContract]

  type FuncIndex = immutable.Map[Seq[FieldSelector], Int]

  private[ralph] def clearCache(): Unit = ContractsCache.clear()

  private[ralph] def generatedContracts(): AVector[CompiledContract] = {
    AVector.from(ContractsCache.view.values.map { wrapper =>
      CompiledContract(wrapper.compiled, wrapper.contract, AVector.empty, wrapper.compiled)
    })
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def genContract[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      tpe: Type
  ): WrapperContract = {
    ContractsCache.get(tpe) match {
      case Some(contract) => contract
      case None =>
        val contract = new ContractGenerator(
          state.asInstanceOf[Compiler.State[StatefulContext]],
          tpe
        ).genContract
        ContractsCache(tpe) = contract
        contract
    }
  }

  final case class WrapperContract(
      tpe: Type,
      sourceCode: String,
      contract: Ast.Contract,
      compiled: StatefulContract,
      loadFuncIndex: FuncIndex,
      storeFuncIndex: FuncIndex
  ) {
    lazy val bytecode = serialize(compiled)

    def genCreate(
        state: Compiler.State[StatefulContext],
        expr: Ast.Expr[StatefulContext],
        pathCodes: Seq[Instr[StatefulContext]]
    ): Seq[Instr[StatefulContext]] = {
      val fields           = contract.fields.dropRight(1) // remove the `parentContractId` field
      val fieldsMutability = fields.flatMap(f => state.flattenTypeMutability(f.tpe, f.isMutable))
      val (immFields, mutFields) = state.genInitCodes(fieldsMutability, Seq(expr))
      val immFieldLength         = fieldsMutability.count(!_)
      val mutFieldLength         = fieldsMutability.length - immFieldLength
      val encodeImmFields = immFields ++
        Seq(SelfContractId, ConstInstr.u256(Val.U256(U256.unsafe(immFieldLength + 1))), Encode)
      val encodeMutFields =
        mutFields ++ Seq(ConstInstr.u256(Val.U256(U256.unsafe(mutFieldLength))), Encode)
      (pathCodes :+ BytesConst(Val.ByteVec(bytecode))) ++
        encodeImmFields ++
        encodeMutFields ++
        Seq(CreateSubContract, Pop)
    }

    private def getFuncById(state: Compiler.State[StatefulContext], funcId: Ast.FuncId) = {
      contract.funcTable(state.globalState).get(funcId) match {
        case Some(func) => func
        case _ => // this should never happen
          throw Compiler.Error(s"The function ${funcId.name} does not exist", None)
      }
    }

    def genDestroy(
        state: Compiler.State[StatefulContext],
        expr: Ast.Expr[StatefulContext],
        pathCodes: Seq[Instr[StatefulContext]]
    ): Seq[Instr[StatefulContext]] = {
      val func     = getFuncById(state, Ast.FuncId("destroy", isBuiltIn = false))
      val objCodes = pathCodes :+ SubContractId
      expr.genCode(state) ++ func.genExternalCallCode(state, objCodes, contract.ident)
    }

    private def getPathAndIndexes(
        selectors: Seq[Ast.FieldSelector]
    ): (Seq[FieldSelector], Seq[Ast.Expr[StatefulContext]]) = {
      val path: Seq[FieldSelector] = Seq(FieldName(DefaultFieldName))
      selectors.foldLeft((path, Seq.empty[Ast.Expr[StatefulContext]])) {
        case ((path, indexes), selector) =>
          selector match {
            case s: Ast.IndexSelector[StatefulContext @unchecked] =>
              (path :+ ArrayIndex, indexes :+ s.index)
            case Ast.IdentSelector(ident) =>
              (path :+ FieldName(ident.name), indexes)
          }
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    private def genCallFunc[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        selectors: Seq[Ast.FieldSelector],
        pathCodes: Seq[Instr[Ctx]],
        funcIndex: FuncIndex
    ): Seq[Instr[Ctx]] = {
      val (path, indexes) = getPathAndIndexes(selectors)
      val index = funcIndex.get(path) match {
        case Some(index) => index
        case _ => // this should never happen
          throw Compiler.Error(s"The function does not exist, selectors: $selectors", None)
      }
      val newState = state.asInstanceOf[Compiler.State[StatefulContext]]
      val func     = getFuncById(newState, Ast.FuncId(s"f$index", isBuiltIn = false))
      val objCodes = pathCodes.asInstanceOf[Seq[Instr[StatefulContext]]] :+ SubContractId
      val instrs = indexes.flatMap(_.genCode(newState)) ++
        func.genExternalCallCode(newState, objCodes, contract.ident)
      instrs.asInstanceOf[Seq[Instr[Ctx]]]
    }

    def genLoad[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        selectors: Seq[Ast.FieldSelector],
        pathCodes: Seq[Instr[Ctx]]
    ): Seq[Instr[Ctx]] = {
      genCallFunc(state, selectors, pathCodes, loadFuncIndex)
    }

    def genStore[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        selectors: Seq[Ast.FieldSelector],
        pathCodes: Seq[Instr[Ctx]]
    ): Seq[Instr[Ctx]] = {
      genCallFunc(state, selectors, pathCodes, storeFuncIndex)
    }
  }

  @scala.annotation.tailrec
  def freshName(base: String, usedNames: Seq[String]): String = {
    if (usedNames.contains(base)) {
      freshName(s"${base}_", usedNames)
    } else {
      base
    }
  }

  private def genMapKey[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      expr: Ast.Expr[Ctx]
  ): Seq[Instr[Ctx]] = {
    val codes = expr.genCode(state)
    expr.getType(state)(0) match {
      case Type.Bool    => codes :+ BoolToByteVec
      case Type.U256    => codes :+ U256ToByteVec
      case Type.I256    => codes :+ I256ToByteVec
      case Type.Address => codes :+ AddressToByteVec
      case Type.ByteVec => codes
      case tpe => // dead branch
        throw Compiler.Error(s"Invalid key type $tpe", expr.sourceIndex)
    }
  }

  @inline def genSubContractPath[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      ident: Ast.Ident,
      index: Ast.Expr[Ctx]
  ): Seq[Instr[Ctx]] = {
    (state.genLoadCode(ident) ++ genMapKey(state, index)) :+ ByteVecConcat
  }

  @inline def genSubContractPath[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      map: Ast.Expr[Ctx],
      index: Ast.Expr[Ctx]
  ): Seq[Instr[Ctx]] = {
    (map.genCode(state) ++ genMapKey(state, index)) :+ ByteVecConcat
  }

  sealed trait FieldSelector
  final case class FieldName(name: String) extends FieldSelector
  case object ArrayIndex                   extends FieldSelector
  final case class ContractField(tpe: Type, isMutable: Boolean, selectors: Seq[FieldSelector])

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def flattenFields(
      state: Compiler.State[StatefulContext],
      tpe: Type,
      isMutable: Boolean,
      selectors: Seq[FieldSelector]
  ): Seq[ContractField] = {
    tpe match {
      case Type.Struct(id) =>
        val struct = state.getStruct(id)
        val fields = struct.fields.flatMap { field =>
          val fieldType      = state.resolveType(field.tpe)
          val isFieldMutable = field.isMutable && isMutable
          val fieldSelectors = selectors :+ FieldName(field.ident.name)
          flattenFields(state, fieldType, isFieldMutable, fieldSelectors)
        }
        ContractField(tpe, isMutable, selectors) +: fields
      case Type.FixedSizeArray(baseType, _) =>
        val elementSelectors = selectors :+ ArrayIndex
        val elementFields    = flattenFields(state, baseType, isMutable, elementSelectors)
        ContractField(tpe, isMutable, selectors) +: elementFields
      case _ =>
        Seq(ContractField(tpe, isMutable, selectors))
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private[ralph] def getTypeSignature(tpe: Type): String = {
    tpe match {
      case Type.FixedSizeArray(baseType, size) =>
        s"[${getTypeSignature(baseType)};$size]"
      case Type.Contract(id) => id.name
      case _                 => tpe.signature
    }
  }
}
