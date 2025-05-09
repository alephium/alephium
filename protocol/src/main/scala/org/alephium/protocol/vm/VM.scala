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

package org.alephium.protocol.vm

import scala.annotation.tailrec

import akka.util.ByteString

import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, OptionF}

sealed abstract class VM[Ctx <: StatelessContext](
    ctx: Ctx,
    frameStack: Stack[Frame[Ctx]],
    operandStack: Stack[Val]
) {
  def execute(obj: ContractObj[Ctx], methodIndex: Int, args: AVector[Val]): ExeResult[Unit] = {
    execute(obj, methodIndex, args, None)
  }

  def executeWithOutputs(
      obj: ContractObj[Ctx],
      methodIndex: Int,
      args: AVector[Val]
  ): ExeResult[AVector[Val]] = {
    var outputs: AVector[Val]                     = AVector.ofCapacity(0)
    val returnTo: AVector[Val] => ExeResult[Unit] = returns => { outputs = returns; Right(()) }
    execute(obj, methodIndex, args, Some(returnTo)).map(_ => outputs)
  }

  def startNonPayableFrame(
      obj: ContractObj[Ctx],
      ctx: Ctx,
      method: Method[Ctx],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[Ctx]]

  def startPayableFrame(
      obj: ContractObj[Ctx],
      ctx: Ctx,
      balanceState: MutBalanceState,
      method: Method[Ctx],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[Ctx]]

  protected def startPayableFrame(
      obj: ContractObj[Ctx],
      ctx: Ctx,
      method: Method[Ctx],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[Ctx]] = {
    ctx.getInitialBalances().flatMap { balances =>
      // Store the transaction caller's balance to use later if needed to cover contract creation deposits
      val txCallerBalance = MutBalanceState.from(balances)
      ctx.setTxCallerBalance(txCallerBalance)

      startPayableFrame(
        obj,
        ctx,
        txCallerBalance,
        method,
        args,
        operandStack,
        returnTo
      )
    }
  }

  def startFrame(
      obj: ContractObj[Ctx],
      ctx: Ctx,
      methodIndex: Int,
      args: AVector[Val],
      operandStack: Stack[Val],
      returnToOpt: Option[AVector[Val] => ExeResult[Unit]]
  ): ExeResult[Frame[Ctx]] = {
    for {
      method <- obj.getMethod(methodIndex)
      _      <- if (method.isPublic) okay else failed(ExternalPrivateMethodCall)
      frame <- {
        val returnTo = returnToOpt.getOrElse(VM.noReturnTo)
        if (method.usePreapprovedAssets) {
          startPayableFrame(obj, ctx, method, args, operandStack, returnTo)
        } else {
          startNonPayableFrame(obj, ctx, method, args, operandStack, returnTo)
        }
      }
    } yield frame
  }

  @inline
  private def execute(
      obj: ContractObj[Ctx],
      methodIndex: Int,
      args: AVector[Val],
      returnToOpt: Option[AVector[Val] => ExeResult[Unit]]
  ): ExeResult[Unit] = {
    for {
      startFrame <- startFrame(obj, ctx, methodIndex, args, operandStack, returnToOpt)
      _          <- frameStack.push(startFrame)
      _          <- executeFrames()
    } yield ()
  }

  @tailrec
  private def executeFrames(): ExeResult[Unit] = {
    frameStack.top match {
      case Some(topFrame) =>
        executeCurrentFrame(topFrame) match {
          case Right(_) => executeFrames()
          case error    => error
        }
      case None => Right(())
    }
  }

  private def executeCurrentFrame(currentFrame: Frame[Ctx]): ExeResult[Unit] = {
    for {
      newFrameOpt <- currentFrame.execute()
      _ <- newFrameOpt match {
        case Some(frame) => frameStack.push(frame)
        case None        => postFrame(currentFrame)
      }
    } yield ()
  }

  private def postFrame(currentFrame: Frame[Ctx]): ExeResult[Unit] = {
    for {
      _ <- frameStack.pop()
      _ <- frameStack.top match {
        case Some(previousFrame) => switchBackFrame(currentFrame, previousFrame)
        case None                => completeLastFrame(currentFrame)
      }
    } yield ()
  }

  protected def switchBackFrame(currentFrame: Frame[Ctx], nextFrame: Frame[Ctx]): ExeResult[Unit]

  protected def completeLastFrame(lastFrame: Frame[Ctx]): ExeResult[Unit]
}

object VM {
  val noReturnTo: AVector[Val] => ExeResult[Unit] = returns =>
    if (returns.nonEmpty) failed(NonEmptyReturnForMainFunction) else okay

  def checkCodeSize(
      initialGas: GasBox,
      codeBytes: ByteString,
      hardFork: HardFork
  ): ExeResult[GasBox] = {
    val maximalCodeSize = {
      if (hardFork.isRhoneEnabled()) { maximalCodeSizeRhone }
      else if (hardFork.isLemanEnabled()) { maximalCodeSizeLeman }
      else { maximalCodeSizePreLeman }
    }
    if (codeBytes.length > maximalCodeSize) {
      failed(CodeSizeTooLarge(codeBytes.length, maximalCodeSize))
    } else {
      initialGas.use(GasCall.scriptBaseGas(codeBytes.length))
    }
  }

  def checkFieldSize(initialGas: GasBox, fields: Iterable[Val]): ExeResult[GasBox] = {
    val estimatedSize = fields.foldLeft(0)(_ + _.estimateByteSize())
    if (estimatedSize >= maximalFieldSize) {
      failed(FieldsSizeTooLarge(estimatedSize))
    } else {
      initialGas.use(GasCall.fieldsBaseGas(estimatedSize))
    }
  }

  def checkContractAttoAlphAmounts(
      outputs: Iterable[TxOutput],
      hardFork: HardFork
  ): ExeResult[Unit] = {
    val minimalStorageDeposit = minimalContractStorageDeposit(hardFork)
    val contractOutputWithoutEnoughAlphOpt = outputs.find {
      case output: ContractOutput => output.amount < minimalStorageDeposit
      case _                      => false
    }
    if (hardFork.isLemanEnabled()) {
      contractOutputWithoutEnoughAlphOpt match {
        case Some(output) =>
          failed(LowerThanContractMinimalBalance(Address.from(output.lockupScript), output.amount))
        case None =>
          okay
      }
    } else {
      okay
    }
  }
}

final class StatelessVM(
    ctx: StatelessContext,
    frameStack: Stack[Frame[StatelessContext]],
    operandStack: Stack[Val]
) extends VM(ctx, frameStack, operandStack) {
  protected def switchBackFrame(
      currentFrame: Frame[StatelessContext],
      nextFrame: Frame[StatelessContext]
  ): ExeResult[Unit] = Right(())
  def startNonPayableFrame(
      obj: ContractObj[StatelessContext],
      ctx: StatelessContext,
      method: Method[StatelessContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatelessContext]] =
    Frame.stateless(ctx, obj, method, args, operandStack, returnTo)

  def startPayableFrame(
      obj: ContractObj[StatelessContext],
      ctx: StatelessContext,
      balanceState: MutBalanceState,
      method: Method[StatelessContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatelessContext]] = failed(ExpectNonPayableMethod)

  protected def completeLastFrame(lastFrame: Frame[StatelessContext]): ExeResult[Unit] = Right(())
}

final class StatefulVM(
    val ctx: StatefulContext,
    frameStack: Stack[Frame[StatefulContext]],
    operandStack: Stack[Val]
) extends VM(ctx, frameStack, operandStack) {
  def startNonPayableFrame(
      obj: ContractObj[StatefulContext],
      ctx: StatefulContext,
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatefulContext]] =
    Frame.stateful(ctx, None, None, obj, method, args, operandStack, returnTo)

  def startPayableFrame(
      obj: ContractObj[StatefulContext],
      ctx: StatefulContext,
      balanceState: MutBalanceState,
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatefulContext]] =
    Frame.stateful(ctx, None, Some(balanceState), obj, method, args, operandStack, returnTo)

  protected[vm] def switchBackFrame(
      currentFrame: Frame[StatefulContext],
      previousFrame: Frame[StatefulContext]
  ): ExeResult[Unit] = {
    if (ctx.getHardFork().isLemanEnabled()) {
      switchBackFrameSinceLeman(currentFrame, previousFrame)
    } else {
      switchBackFramePreLeman(currentFrame, previousFrame)
    }
  }

  private def wrap(resultOpt: Option[Unit]): ExeResult[Unit] = {
    resultOpt match {
      case Some(_) => okay
      case None    => failed(BalanceErrorWhenSwitchingBackFrame)
    }
  }

  protected def switchBackFrameSinceLeman(
      currentFrame: Frame[StatefulContext],
      previousFrame: Frame[StatefulContext]
  ): ExeResult[Unit] = {
    val useContractAssets = currentFrame.method.useContractAssets
    (currentFrame.balanceStateOpt, previousFrame.balanceStateOpt) match {
      case (None, _) => okay
      case (Some(currentBalances), None) =>
        wrap(for {
          _ <- mergeBack(
            ctx.outputBalances,
            currentBalances.remaining,
            useContractAssets,
            isApproved = false
          )
          _ <- mergeBack(
            ctx.outputBalances,
            currentBalances.approved,
            useContractAssets,
            isApproved = true
          )
        } yield ())
      case (Some(currentBalances), Some(previousBalances)) =>
        wrap(for {
          _ <- mergeBack(
            previousBalances.remaining,
            currentBalances.remaining,
            useContractAssets,
            isApproved = false
          )
          _ <- mergeBack(
            previousBalances.remaining,
            currentBalances.approved,
            useContractAssets,
            isApproved = true
          )
        } yield ())
    }
  }

  protected def switchBackFramePreLeman(
      currentFrame: Frame[StatefulContext],
      previousFrame: Frame[StatefulContext]
  ): ExeResult[Unit] = {
    if (currentFrame.method.usesAssetsFromInputs()) {
      val useContractAssets = currentFrame.method.useContractAssets
      wrap(for {
        currentBalances  <- currentFrame.balanceStateOpt
        previousBalances <- previousFrame.balanceStateOpt
        _ <- mergeBack(
          previousBalances.remaining,
          currentBalances.remaining,
          useContractAssets,
          isApproved = false
        )
        _ <- mergeBack(
          previousBalances.remaining,
          currentBalances.approved,
          useContractAssets,
          isApproved = true
        )
      } yield ())
    } else {
      okay
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
  @inline private def shouldKeepContractBalances(
      hardFork: HardFork,
      isApproved: Boolean,
      lockupScript: LockupScript
  ): Boolean = {
    hardFork.isRhoneEnabled() && !isApproved && lockupScript.isInstanceOf[LockupScript.P2C]
  }

  protected def mergeBack(
      previous: MutBalances,
      current: MutBalances,
      useContractAssets: Boolean,
      isApproved: Boolean
  ): Option[Unit] = {
    val hardFork = ctx.getHardFork()

    OptionF.foreach(current.all) { case (lockupScript, balancesPerLockup) =>
      val keepContractBalances = shouldKeepContractBalances(hardFork, isApproved, lockupScript)

      if (hardFork.isDanubeEnabled()) {
        if (balancesPerLockup.scopeDepth <= 0) {
          mergeBackScopeDepthLessThanOne(keepContractBalances, lockupScript, balancesPerLockup)
        } else {
          if (keepContractBalances && useContractAssets) {
            Some(())
          } else {
            previous.add(lockupScript, balancesPerLockup)
          }
        }
      } else {
        if (balancesPerLockup.scopeDepth <= 0) {
          mergeBackScopeDepthLessThanOne(keepContractBalances, lockupScript, balancesPerLockup)
        } else {
          previous.add(lockupScript, balancesPerLockup)
        }
      }
    }
  }

  private def mergeBackScopeDepthLessThanOne(
      keepContractBalances: Boolean,
      lockupScript: LockupScript,
      balancesPerLockup: MutBalancesPerLockup
  ): Option[Unit] = {
    if (keepContractBalances) {
      Some(())
    } else {
      ctx.outputBalances.add(lockupScript, balancesPerLockup)
    }
  }

  protected def completeLastFrame(lastFrame: Frame[StatefulContext]): ExeResult[Unit] = {
    for {
      _ <- ctx.updateContractStates()
      _ <- cleanBalances(lastFrame)
      _ <- ctx
        .removeOutdatedContractAssets() // this must run after cleanBalances so that unused inputs are removed
    } yield ()
  }

  private def cleanBalances(lastFrame: Frame[StatefulContext]): ExeResult[Unit] = {
    if (lastFrame.method.usesAssetsFromInputs() || ctx.getHardFork().isLemanEnabled()) {
      ctx.cleanBalances()
    } else {
      okay
    }
  }
}

object StatelessVM {
  final case class AssetScriptExecution(gasRemaining: GasBox)

  def runAssetScript(
      blockEnv: BlockEnv,
      txEnv: TxEnv,
      initialGas: GasBox,
      script: StatelessScript,
      args: AVector[Val]
  )(implicit
      networkConfig: NetworkConfig,
      groupConfig: GroupConfig
  ): ExeResult[AssetScriptExecution] = {
    val context = StatelessContext(blockEnv, txEnv, initialGas)
    val obj     = script.toObject
    execute(context, obj, args)
  }

  private def default(ctx: StatelessContext): StatelessVM = {
    new StatelessVM(
      ctx,
      Stack.ofCapacity(frameStackMaxSize),
      Stack.ofCapacity(opStackMaxSize)
    )
  }

  def execute(
      context: StatelessContext,
      obj: ContractObj[StatelessContext],
      args: AVector[Val]
  ): ExeResult[AssetScriptExecution] = {
    val vm = default(context)
    vm.execute(obj, 0, args).map(_ => AssetScriptExecution(context.gasRemaining))
  }

  def executeWithOutputs(
      context: StatelessContext,
      obj: ContractObj[StatelessContext],
      args: AVector[Val]
  ): ExeResult[AVector[Val]] = {
    val vm = default(context)
    vm.executeWithOutputs(obj, 0, args)
  }
}

object StatefulVM {
  final case class TxScriptExecution(
      gasBox: GasBox,
      contractInputs: AVector[ContractOutputRef],
      contractPrevOutputs: AVector[ContractOutput],
      generatedOutputs: AVector[TxOutput]
  )

  // scalastyle:off parameter.number
  def runTxScript(
      worldState: WorldState.Staging,
      blockEnv: BlockEnv,
      tx: TransactionAbstract,
      preOutputs: AVector[AssetOutput],
      script: StatefulScript,
      gasRemaining: GasBox,
      txIndex: Int
  )(implicit
      networkConfig: NetworkConfig,
      logConfig: LogConfig,
      groupConfig: GroupConfig
  ): ExeResult[TxScriptExecution] = {
    val context = StatefulContext(blockEnv, tx, gasRemaining, worldState, preOutputs, txIndex)
    runTxScript(context, script)
  }
  // scalastyle:on parameter.number

  def runTxScript(
      context: StatefulContext,
      script: StatefulScript
  ): ExeResult[TxScriptExecution] = {
    for {
      _      <- execute(context, script.toObject, AVector.empty)
      result <- prepareResult(context)
    } yield result
  }

  // scalastyle:off parameter.number
  def runTxScriptMockup(
      worldState: WorldState.Staging,
      blockEnv: BlockEnv,
      tx: TransactionAbstract,
      preOutputs: AVector[AssetOutput],
      script: StatefulScript,
      gasRemaining: GasBox
  )(implicit
      networkConfig: NetworkConfig,
      logConfig: LogConfig,
      groupConfig: GroupConfig
  ): ExeResult[TxScriptExecution] = {
    val context = StatefulContext(blockEnv, tx, gasRemaining, worldState, preOutputs, 0)
    runTxScriptMockup(context, script)
  }
  // scalastyle:on parameter.number

  def runTxScriptMockup(
      context: StatefulContext,
      script: StatefulScript
  ): ExeResult[TxScriptExecution] = {
    for {
      _ <- execute(context, script.toObject, AVector.empty)
    } yield prepareResultMockup(context)
  }

  private def prepareResultMockup(context: StatefulContext): TxScriptExecution = {
    TxScriptExecution(
      context.gasRemaining,
      AVector.from(context.contractInputs.view.map(_._1)),
      AVector.from(context.contractInputs.view.map(_._2)),
      AVector.from(context.generatedOutputs)
    )
  }

  def runTxScriptWithOutputsTestOnly(
      context: StatefulContext,
      script: StatefulScript
  ): ExeResult[(AVector[Val], TxScriptExecution)] = {
    for {
      outputs <- executeWithOutputs(context, script.toObject, AVector.empty)
      result  <- prepareResult(context)
    } yield (outputs, result)
  }

  def runCallerContractWithOutputsTestOnly(
      context: StatefulContext,
      contract: StatefulContract,
      contractId: ContractId
  ): ExeResult[(AVector[Val], TxScriptExecution)] = {
    for {
      outputs <- executeWithOutputs(
        context,
        contract.toHalfDecoded().toObjectUnsafeTestOnly(contractId, AVector.empty, AVector.empty),
        AVector.empty
      )
      result <- prepareResult(context)
    } yield (outputs, result)
  }

  private def prepareResult(context: StatefulContext): ExeResult[TxScriptExecution] = {
    for {
      _ <- checkRemainingSignatures(context)
      _ <- VM.checkContractAttoAlphAmounts(context.generatedOutputs, context.getHardFork())
    } yield {
      TxScriptExecution(
        context.gasRemaining,
        AVector.from(context.contractInputs.view.map(_._1)),
        AVector.from(context.contractInputs.view.map(_._2)),
        AVector.from(context.generatedOutputs)
      )
    }
  }

  def checkRemainingSignatures(context: StatefulContext): ExeResult[Unit] = {
    if (context.txEnv.signatures.isEmpty) {
      okay
    } else {
      failed(TooManySignatures(context.txEnv.signatures.size))
    }
  }

  private[vm] def default(ctx: StatefulContext): StatefulVM = {
    new StatefulVM(ctx, Stack.ofCapacity(frameStackMaxSize), Stack.ofCapacity(opStackMaxSize))
  }

  def execute(
      context: StatefulContext,
      obj: ContractObj[StatefulContext],
      args: AVector[Val]
  ): ExeResult[Unit] = {
    val vm      = default(context)
    val results = vm.execute(obj, 0, args)
    maybeShowDebug(context)
    results
  }

  def executeWithOutputs(
      context: StatefulContext,
      obj: ContractObj[StatefulContext],
      args: AVector[Val],
      methodIndex: Int
  ): ExeResult[AVector[Val]] = {
    val vm = default(context)
    vm.executeWithOutputs(obj, methodIndex, args)
  }

  def executeWithOutputs(
      context: StatefulContext,
      obj: ContractObj[StatefulContext],
      args: AVector[Val]
  ): ExeResult[AVector[Val]] = {
    val results = executeWithOutputs(context, obj, args, 0)
    maybeShowDebug(context)
    results
  }

  private def maybeShowDebug(context: StatefulContext): Unit = {
    val networkId = context.networkConfig.networkId
    if (networkId.networkType != NetworkId.MainNet) {
      context.worldState.nodeIndexesState.logState.getNewLogs().foreach { logStates =>
        logStates.states.foreach { logState =>
          if (logState.index == debugEventIndex.v.v.intValue().toByte) {
            logState.fields.headOption.foreach {
              case Val.ByteVec(bytes) =>
                print(
                  s"> Contract @ ${Address.contract(logStates.contractId).toBase58} - ${bytes.utf8String}\n"
                )
              case _ => ()
            }
          }
        }
      }
    }
  }
}
