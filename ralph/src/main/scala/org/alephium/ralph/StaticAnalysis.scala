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

import scala.collection.mutable

import org.alephium.protocol.vm
import org.alephium.ralph.Ast._
import org.alephium.util.AVector

object StaticAnalysis {
  @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
  def checkMethodsStateless[Ctx <: vm.StatelessContext](
      ast: Ast.ContractT[Ctx],
      methods: AVector[vm.Method[Ctx]],
      state: Compiler.State[Ctx]
  ): Unit = {
    assume(ast.funcs.length == methods.length)
    checkIfPrivateMethodsUsed(ast, state)
    ast.funcs.zip(methods.toIterable).foreach { case (func, method) =>
      // skip check readonly for main function
      if (!(ast.isInstanceOf[Ast.TxScript] && (func.name == "main"))) {
        checkReadonly(state, func, method)
      }
    }
  }

  def checkMethodsStateful(
      ast: Ast.ContractWithState,
      methods: AVector[vm.Method[vm.StatefulContext]],
      state: Compiler.State[vm.StatefulContext]
  ): Unit = {
    checkMethodsStateless(ast, methods, state)
    ast.funcs.zip(methods.toIterable).foreach { case (func, method) =>
      checkCodeUsingContractAssets(ast.ident, func, method)
    }
  }

  def checkMethods(
      ast: Ast.Contract,
      code: vm.StatefulContract,
      state: Compiler.State[vm.StatefulContext]
  ): Unit = {
    checkMethodsStateful(ast, code.methods, state)
  }

  def checkIfPrivateMethodsUsed[Ctx <: vm.StatelessContext](
      ast: Ast.ContractT[Ctx],
      state: Compiler.State[Ctx]
  ): Unit = {
    ast.funcs.foreach { func =>
      if (func.isPrivate && !state.internalCallsReversed.get(func.id).exists(_.nonEmpty)) {
        state.warnUnusedPrivateFunction(ast.ident, func.id)
      }
    }
  }

  val contractAssetsInstrs: Set[vm.Instr[_]] =
    Set(
      vm.TransferAlphFromSelf,
      vm.TransferTokenFromSelf,
      vm.TransferAlphToSelf,
      vm.TransferTokenToSelf,
      vm.DestroySelf,
      vm.SelfAddress
    )

  def checkCodeUsingContractAssets(
      contractId: Ast.TypeId,
      func: Ast.FuncDef[vm.StatefulContext],
      method: vm.Method[vm.StatefulContext]
  ): Unit = {
    if (func.useAssetsInContract && !method.instrs.exists(contractAssetsInstrs.contains(_))) {
      throw Compiler.Error(
        s"Function ${Ast.funcName(contractId, func.id)} does not use contract assets, but its annotation of contract assets is turn on"
      )
    }
  }

  def checkReadonly[Ctx <: vm.StatelessContext](
      state: Compiler.State[Ctx],
      func: FuncDef[Ctx],
      method: vm.Method[Ctx]
  ): Unit = {
    val changeState = method.instrs.exists {
      case _: vm.StoreField | _: vm.StoreFieldByIndex.type => true
      case _                                               => false
    }
    val internalCalls        = state.internalCalls.getOrElse(func.id, mutable.Set.empty)
    val invalidInternalCalls = internalCalls.filterNot(state.getFunc(_).isReadonly)
    val externalCalls        = state.externalCalls.getOrElse(func.id, mutable.Set.empty)
    val invalidExternalCalls = externalCalls.filterNot { case (typeId, funcId) =>
      state.getFunc(typeId, funcId).isReadonly
    }

    val isReadonly =
      !changeState && invalidInternalCalls.isEmpty && invalidExternalCalls.isEmpty
    if (!isReadonly && func.useReadonly) {
      if (changeState) {
        throw Compiler.Error(s"Readonly function ${funcName(state.typeId, func.id)} changes state")
      }
      if (invalidInternalCalls.nonEmpty) {
        throw Compiler.Error(
          s"Readonly function ${funcName(state.typeId, func.id)} have invalid internal calls: ${quote(
              invalidInternalCalls.map(_.name).mkString(", ")
            )}"
        )
      }
      if (invalidExternalCalls.nonEmpty) {
        val msg = invalidExternalCalls
          .map { case (typeId, funcId) =>
            s"${typeId.name}.${funcId.name}"
          }
          .mkString(", ")
        throw Compiler.Error(
          s"Readonly function ${funcName(state.typeId, func.id)} have invalid external calls: ${quote(msg)}"
        )
      }
    }

    if (isReadonly && !func.useReadonly) {
      state.warnReadonlyCheck(state.typeId, func.id)
    }
  }

  private[ralph] def checkExternalCallPermissions(
      contractState: Compiler.State[vm.StatefulContext],
      contract: Contract,
      externalCallCheckTables: mutable.Map[TypeId, mutable.Map[FuncId, Boolean]]
  ): Unit = {
    val allNoExternalCallChecks: mutable.Set[(TypeId, FuncId)] = mutable.Set.empty
    contract.funcs.foreach { func =>
      // To check that external calls should have external call checks
      contractState.externalCalls.get(func.id) match {
        case Some(callees) if callees.nonEmpty =>
          callees.foreach { case funcRef @ (typeId, funcId) =>
            if (!externalCallCheckTables(typeId)(funcId)) {
              allNoExternalCallChecks.addOne(funcRef)
            }
          }
        case _ => ()
      }
    }
    allNoExternalCallChecks.foreach { case (typeId, funcId) =>
      contractState.warnExternalCallCheck(typeId, funcId)
    }
  }

  def checkInterfaceExternalCallCheck(
      multiContract: MultiContract,
      interfaceTypeId: TypeId,
      externalCallCheckTables: mutable.Map[TypeId, mutable.Map[FuncId, Boolean]]
  ): Unit = {
    assume(multiContract.dependencies.isDefined)
    val children = multiContract.dependencies
      .map(_.filter { case (child, parents) =>
        parents.contains(interfaceTypeId) && multiContract.isContract(child)
      }.keys.toSeq)
      .getOrElse(Seq.empty)
    val interface = multiContract.getInterface(interfaceTypeId)
    children.foreach { contractId =>
      val table = externalCallCheckTables(contractId)
      interface.funcs.foreach { func =>
        if (func.useExternalCallCheck && !table(func.id)) {
          throw Compiler.Error(Warnings.noExternalCallCheckMsg(contractId.name, func.id.name))
        }
      }
    }
  }

  private def checkNoExternalCallFuncReadonly(
      contract: Contract,
      externalCallCheckTable: mutable.Map[FuncId, Boolean],
      state: Compiler.State[vm.StatefulContext]
  ): Unit = {
    contract.funcs.foreach { func =>
      val noExternalCallCheck = !func.useExternalCallCheck || !externalCallCheckTable(func.id)
      if (noExternalCallCheck && !func.hasReadonlyAnnotation && func.isPublic) {
        state.warnNonReadonlyAndNoExternalCallCheck(contract.ident, func.id)
      }
    }
  }

  def checkExternalCalls(
      multiContract: MultiContract,
      states: AVector[Compiler.State[vm.StatefulContext]]
  ): Unit = {
    val externalCallCheckTables = mutable.Map.empty[TypeId, mutable.Map[FuncId, Boolean]]
    multiContract.contracts.zipWithIndex.foreach {
      case (contract: Contract, index) if !contract.isAbstract =>
        val state = states(index)
        val table = contract.buildExternalCallCheckTable(state)
        externalCallCheckTables.update(contract.ident, table)
        checkNoExternalCallFuncReadonly(contract, table, state)
      case (interface: ContractInterface, _) =>
        val table = mutable.Map.from(interface.funcs.map(_.id -> true))
        externalCallCheckTables.update(interface.ident, table)
      case _ => ()
    }
    multiContract.contracts.zipWithIndex.foreach {
      case (contract: Contract, index) if !contract.isAbstract =>
        val state = states(index)
        checkExternalCallPermissions(state, contract, externalCallCheckTables)
      case (interface: ContractInterface, _) =>
        checkInterfaceExternalCallCheck(multiContract, interface.ident, externalCallCheckTables)
      case _ =>
    }
  }
}
