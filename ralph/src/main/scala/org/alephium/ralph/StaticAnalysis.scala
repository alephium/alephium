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
      checkCodeUsingPayToContract(ast.ident, func, method)
      checkCodeUsingAssets(ast.ident, func, method)
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
    val unusedFuncs = ast.funcs.filter { func =>
      func.isPrivate &&
      func.definedIn(ast.ident) &&
      !state.internalCallsReversed.get(func.id).exists(_.nonEmpty)
    }
    if (unusedFuncs.nonEmpty) {
      state.warnUnusedPrivateFunction(ast.ident, unusedFuncs.map(_.name))
    }
  }

  private[ralph] lazy val contractAssetsInstrs: Set[vm.Instr[_]] =
    Set(
      vm.TransferAlphFromSelf,
      vm.TransferTokenFromSelf,
      vm.TransferAlphToSelf,
      vm.TransferTokenToSelf,
      vm.DestroySelf,
      vm.SelfAddress
    )
  private lazy val payToContractInstrs: Set[vm.Instr[_]] =
    Set(vm.TransferAlphToSelf, vm.TransferTokenToSelf, vm.SelfAddress)
  private lazy val spendContractAssetsInstrs: Set[vm.Instr[_]] =
    Set(vm.TransferAlphFromSelf, vm.TransferTokenFromSelf, vm.DestroySelf)
  private lazy val payToContractInstrsExceptSelfAddress: Set[vm.Instr[_]] =
    Set(vm.TransferAlphToSelf, vm.TransferTokenToSelf)

  def checkCodeUsingContractAssets(
      contractId: Ast.TypeId,
      func: Ast.FuncDef[vm.StatefulContext],
      method: vm.Method[vm.StatefulContext]
  ): Unit = {
    if (
      func.useAssetsInContract == Ast.UseContractAssets &&
      !method.instrs.exists(contractAssetsInstrs.contains(_))
    ) {
      throw Compiler.Error(
        s"Function ${Ast.funcName(contractId, func.id)} does not use contract assets, but the annotation `assetsInContract` is enabled. " +
          "Please remove the `assetsInContract` annotation or set it to `enforced`",
        func.sourceIndex
      )
    }

    if (
      func.useAssetsInContract == Ast.NotUseContractAssets &&
      method.instrs.exists(spendContractAssetsInstrs.contains)
    ) {
      throw Compiler.Error(
        s"Function ${Ast.funcName(contractId, func.id)} uses contract assets, please use annotation `assetsInContract = true`.",
        func.sourceIndex
      )
    }
  }

  private def checkCodeUsingPayToContract(
      contractId: Ast.TypeId,
      func: Ast.FuncDef[vm.StatefulContext],
      method: vm.Method[vm.StatefulContext]
  ): Unit = {
    if (func.usePayToContractOnly && !method.instrs.exists(payToContractInstrs.contains)) {
      throw Compiler.Error(
        s"Function ${Ast.funcName(contractId, func.id)} does not pay to the contract, but the annotation `payToContractOnly` is enabled.",
        func.sourceIndex
      )
    }

    val hasPayToContractInstr = method.instrs.exists(payToContractInstrsExceptSelfAddress.contains)
    val isUseContractAssets =
      func.usePayToContractOnly || func.useAssetsInContract != Ast.NotUseContractAssets
    if (!isUseContractAssets && hasPayToContractInstr) {
      throw Compiler.Error(
        s"Function ${Ast.funcName(contractId, func.id)} transfers assets to the contract, please set either `assetsInContract` or `payToContractOnly` to true.",
        func.sourceIndex
      )
    }

    if (isUseContractAssets && hasPayToContractInstr && !func.usePreapprovedAssets) {
      throw Compiler.Error(
        s"Function ${Ast.funcName(contractId, func.id)} transfers assets to the contract, please use annotation `preapprovedAssets = true`.",
        func.sourceIndex
      )
    }
  }

  private lazy val useAssetsInstrs: Set[vm.Instr[_]] =
    Set(vm.ApproveAlph, vm.ApproveToken, vm.TransferAlph, vm.TransferToken, vm.BurnToken)

  private def checkCodeUsingAssets(
      contractId: Ast.TypeId,
      func: Ast.FuncDef[vm.StatefulContext],
      method: vm.Method[vm.StatefulContext]
  ): Unit = {
    val isNotUseAssets =
      !func.usePreapprovedAssets &&
        func.useAssetsInContract == Ast.NotUseContractAssets &&
        !func.usePayToContractOnly
    if (isNotUseAssets && method.instrs.exists(useAssetsInstrs.contains)) {
      throw Compiler.Error(
        s"Function ${Ast.funcName(contractId, func.id)} uses assets, please use annotation `preapprovedAssets = true` or `assetsInContract = true`",
        func.sourceIndex
      )
    }
  }

  def checkUpdateFields[Ctx <: vm.StatelessContext](
      state: Compiler.State[Ctx],
      func: FuncDef[Ctx],
      method: vm.Method[Ctx]
  ): Unit = {
    val updateFields = method.instrs.exists {
      case _: vm.StoreMutField | _: vm.StoreMutFieldByIndex.type | _: vm.MigrateWithFields.type =>
        true
      case _ => false
    }

    if (updateFields && !func.useUpdateFields) {
      state.warnNoUpdateFieldsCheck(state.typeId, func.id)
    }

    if (!updateFields && func.useUpdateFields) {
      state.warnUnnecessaryUpdateFieldsCheck(state.typeId, func.id)
    }
  }

  private[ralph] def checkExternalCallPermissions(
      nonSimpleViewFuncSet: mutable.Set[(TypeId, FuncId)],
      contractState: Compiler.State[vm.StatefulContext],
      contract: Contract,
      checkExternalCallerTables: mutable.Map[TypeId, mutable.Map[FuncId, Boolean]]
  ): Unit = {
    val allNoCheckExternalCallers: mutable.Set[(TypeId, FuncId)] = mutable.Set.empty
    val table = checkExternalCallerTables(contract.ident)
    contract.funcs.foreach { func =>
      if (
        func.isPublic &&
        !table(func.id) &&
        nonSimpleViewFuncSet.contains(contract.ident -> func.id)
      ) {
        allNoCheckExternalCallers.addOne(contract.ident -> func.id)
      }
    }
    allNoCheckExternalCallers.foreach { case (typeId, funcId) =>
      contractState.warnCheckExternalCaller(typeId, funcId)
    }
  }

  def buildNonSimpleViewFuncSet(
      multiContract: MultiContract,
      states: AVector[Compiler.State[vm.StatefulContext]]
  ): mutable.Set[(TypeId, FuncId)] = {
    val nonSimpleViewFuncSet = mutable.Set.empty[(TypeId, FuncId)]
    multiContract.contracts.zipWithIndex.foreach {
      case (contract: Contract, index) =>
        val state = states(index)
        contract.funcs.foreach { func =>
          val key = contract.ident -> func.id
          if (!func.isSimpleViewFunc(state)) {
            nonSimpleViewFuncSet.addOne(key)
          }
        }
      case _ => ()
    }
    updateNonSimpleViewFuncSet(nonSimpleViewFuncSet, multiContract, states)
    nonSimpleViewFuncSet
  }

  // Optimize: we will need to introduce `MultiState` later
  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def updateNonSimpleViewFuncSet(
      nonSimpleViewFuncSet: mutable.Set[(TypeId, FuncId)],
      multiContract: MultiContract,
      states: AVector[Compiler.State[vm.StatefulContext]]
  ): Unit = {
    val startSize = nonSimpleViewFuncSet.size
    multiContract.contracts.zipWithIndex.foreach {
      case (contract: Contract, index) =>
        val state = states(index)
        state.internalCalls.foreach { case (caller, callees) =>
          val key = contract.ident -> caller
          if (
            !nonSimpleViewFuncSet.contains(key) &&
            callees.exists(callee => nonSimpleViewFuncSet.contains(contract.ident -> callee))
          ) {
            nonSimpleViewFuncSet.addOne(key)
          }
        }
        state.externalCalls.foreach { case (caller, callees) =>
          val key = contract.ident -> caller
          if (
            !nonSimpleViewFuncSet.contains(key) &&
            callees.exists(callee => nonSimpleViewFuncSet.contains(callee))
          ) {
            nonSimpleViewFuncSet.addOne(key)
          }
        }
      case _ => ()
    }
    val endSize = nonSimpleViewFuncSet.size
    if (endSize > startSize) {
      updateNonSimpleViewFuncSet(nonSimpleViewFuncSet, multiContract, states)
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
    val nonSimpleViewFuncSet = buildNonSimpleViewFuncSet(multiContract, states)
    multiContract.contracts.zipWithIndex.foreach {
      case (contract: Contract, index) if !contract.isAbstract =>
        val state = states(index)
        checkExternalCallPermissions(
          nonSimpleViewFuncSet,
          state,
          contract,
          checkExternalCallerTables
        )
      case _ =>
    }
  }
}
