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
      // skip check update fields for main function
      if (!(ast.isInstanceOf[Ast.TxScript] && (func.name == "main"))) {
        checkUpdateFields(state, func, method)
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

  def checkUpdateFields[Ctx <: vm.StatelessContext](
      state: Compiler.State[Ctx],
      func: FuncDef[Ctx],
      method: vm.Method[Ctx]
  ): Unit = {
    val updateFields = method.instrs.exists {
      case _: vm.StoreMutField | _: vm.StoreFieldByIndex.type | _: vm.MigrateWithFields.type => true
      case _ => false
    }

    if (updateFields && !func.useUpdateFields) {
      state.warnNoUpdateFieldsCheck(state.typeId, func.id)
    }

    if (!updateFields && func.useUpdateFields) {
      state.warnUnnecessaryUpdateFieldsCheck(state.typeId, func.id)
    }
  }

  private def isSimpleViewFunction(
      contractState: Compiler.State[vm.StatefulContext],
      funcId: FuncId
  ): Boolean = {
    val func = contractState.getFunc(funcId)
    !(
      func.useUpdateFields ||
        func.usePreapprovedAssets ||
        func.useAssetsInContract ||
        contractState.hasSubFunctionCall(funcId)
    )
  }

  private[ralph] def checkExternalCallPermissions(
      allStates: AVector[Compiler.State[vm.StatefulContext]],
      contractState: Compiler.State[vm.StatefulContext],
      contract: Contract,
      checkExternalCallerTables: mutable.Map[TypeId, mutable.Map[FuncId, Boolean]]
  ): Unit = {
    val allNoCheckExternalCallers: mutable.Set[(TypeId, FuncId)] = mutable.Set.empty
    contract.funcs.foreach { func =>
      // To check that external calls should have check external callers
      contractState.externalCalls.get(func.id) match {
        case Some(callees) if callees.nonEmpty =>
          callees.foreach { case funcRef @ (typeId, funcId) =>
            if (!checkExternalCallerTables(typeId)(funcId)) {
              val calleeContractState = allStates
                .find(_.typeId == typeId)
                .getOrElse(
                  throw Compiler.Error(s"No state for contract $typeId") // this should never happen
                )
              if (!isSimpleViewFunction(calleeContractState, funcId)) {
                allNoCheckExternalCallers.addOne(funcRef)
              }
            }
          }
        case _ => ()
      }
    }
    allNoCheckExternalCallers.foreach { case (typeId, funcId) =>
      contractState.warnCheckExternalCaller(typeId, funcId)
    }
  }

  def checkInterfaceCheckExternalCaller(
      allStates: AVector[Compiler.State[vm.StatefulContext]],
      multiContract: MultiContract,
      interfaceTypeId: TypeId,
      checkExternalCallerTables: mutable.Map[TypeId, mutable.Map[FuncId, Boolean]]
  ): Unit = {
    assume(multiContract.dependencies.isDefined)
    val children = multiContract.dependencies
      .map(_.filter { case (child, parents) =>
        parents.contains(interfaceTypeId) && multiContract.isContract(child)
      }.keys.toSeq)
      .getOrElse(Seq.empty)
    val interface = multiContract.getInterface(interfaceTypeId)
    children.foreach { contractId =>
      val childContractState = allStates
        .find(_.typeId == contractId)
        .getOrElse(
          throw Compiler.Error(s"No state for contract $contractId") // this should never happen
        )
      val table = checkExternalCallerTables(contractId)
      interface.funcs.foreach { func =>
        if (
          func.useCheckExternalCaller &&
          !table(func.id) &&
          !isSimpleViewFunction(childContractState, func.id)
        ) {
          throw Compiler.Error(Warnings.noCheckExternalCallerMsg(contractId.name, func.id.name))
        }
      }
    }
  }

  def checkExternalCalls(
      multiContract: MultiContract,
      states: AVector[Compiler.State[vm.StatefulContext]]
  ): Unit = {
    val checkExternalCallerTables = mutable.Map.empty[TypeId, mutable.Map[FuncId, Boolean]]
    multiContract.contracts.zipWithIndex.foreach {
      case (contract: Contract, index) if !contract.isAbstract =>
        val state = states(index)
        val table = contract.buildCheckExternalCallerTable(state)
        checkExternalCallerTables.update(contract.ident, table)
      case (interface: ContractInterface, _) =>
        val table = mutable.Map.from(interface.funcs.map(_.id -> true))
        checkExternalCallerTables.update(interface.ident, table)
      case _ => ()
    }
    multiContract.contracts.zipWithIndex.foreach {
      case (contract: Contract, index) if !contract.isAbstract =>
        val state = states(index)
        checkExternalCallPermissions(states, state, contract, checkExternalCallerTables)
      case (interface: ContractInterface, _) =>
        checkInterfaceCheckExternalCaller(
          states,
          multiContract,
          interface.ident,
          checkExternalCallerTables
        )
      case _ =>
    }
  }
}
