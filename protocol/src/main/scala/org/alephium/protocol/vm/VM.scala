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

import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, EitherF}

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
    var outputs: AVector[Val]                     = AVector.ofSize(0)
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
      balanceState: BalanceState,
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
      startPayableFrame(
        obj,
        ctx,
        BalanceState.from(balances),
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
        if (method.isPayable) {
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

  def checkCodeSize(initialGas: GasBox, codeBytes: ByteString): ExeResult[GasBox] = {
    if (codeBytes.length > maximalScriptSize) {
      failed(CodeSizeTooLarge)
    } else {
      initialGas.use(GasCall.scriptBaseGas(codeBytes.length))
    }
  }

  def checkFieldSize(initialGas: GasBox, fields: Iterable[Val]): ExeResult[GasBox] = {
    val estimatedSize = fields.foldLeft(0)(_ + _.estimateByteSize())
    if (estimatedSize >= maximalFieldSize) {
      failed(FieldsSizeTooLarge)
    } else {
      initialGas.use(GasCall.fieldsBaseGas(estimatedSize))
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
      balanceState: BalanceState,
      method: Method[StatelessContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatelessContext]] = failed(ExpectNonPayableMethod)

  protected def completeLastFrame(lastFrame: Frame[StatelessContext]): ExeResult[Unit] = Right(())
}

final class StatefulVM(
    ctx: StatefulContext,
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
      balanceState: BalanceState,
      method: Method[StatefulContext],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]
  ): ExeResult[Frame[StatefulContext]] =
    Frame.stateful(ctx, None, Some(balanceState), obj, method, args, operandStack, returnTo)

  protected def switchBackFrame(
      currentFrame: Frame[StatefulContext],
      previousFrame: Frame[StatefulContext]
  ): ExeResult[Unit] = {
    if (currentFrame.method.isPayable) {
      val resultOpt = for {
        currentBalances  <- currentFrame.balanceStateOpt
        previousBalances <- previousFrame.balanceStateOpt
        _                <- mergeBack(previousBalances.remaining, currentBalances.remaining)
        _                <- mergeBack(previousBalances.remaining, currentBalances.approved)
      } yield ()
      resultOpt match {
        case Some(_) => okay
        case None    => failed(InvalidBalances)
      }
    } else {
      okay
    }
  }

  protected def mergeBack(previous: Balances, current: Balances): Option[Unit] = {
    @tailrec
    def iter(index: Int): Option[Unit] = {
      if (index >= current.all.length) {
        Some(())
      } else {
        val (lockupScript, balancesPerLockup) = current.all(index)
        if (balancesPerLockup.scopeDepth <= 0) {
          ctx.outputBalances.add(lockupScript, balancesPerLockup)
        } else {
          previous.add(lockupScript, balancesPerLockup) match {
            case Some(_) => iter(index + 1)
            case None    => None
          }
        }
      }
    }

    iter(0)
  }

  protected def completeLastFrame(lastFrame: Frame[StatefulContext]): ExeResult[Unit] = {
    for {
      _ <- ctx.updateContractStates()
      _ <- cleanBalances(lastFrame)
    } yield ()
  }

  private def cleanBalances(lastFrame: Frame[StatefulContext]): ExeResult[Unit] = {
    if (lastFrame.method.isPayable) {
      val resultOpt = for {
        balances <- lastFrame.balanceStateOpt
        _        <- ctx.outputBalances.merge(balances.approved)
        _        <- ctx.outputBalances.merge(balances.remaining)
      } yield ()
      for {
        _ <- resultOpt match {
          case Some(_) => okay
          case None    => failed(InvalidBalances)
        }
        _ <- outputGeneratedBalances(ctx.outputBalances)
        _ <- ctx.checkAllAssetsFlushed()
      } yield ()
    } else {
      Right(())
    }
  }

  private def outputGeneratedBalances(outputBalances: Balances): ExeResult[Unit] = {
    EitherF.foreachTry(outputBalances.all) { case (lockupScript, balances) =>
      balances.toTxOutput(lockupScript).flatMap {
        case Some(output) => ctx.generateOutput(output)
        case None         => Right(())
      }
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
  )(implicit networkConfig: NetworkConfig): ExeResult[AssetScriptExecution] = {
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

  def runTxScript(
      worldState: WorldState.Staging,
      blockEnv: BlockEnv,
      tx: TransactionAbstract,
      preOutputs: AVector[AssetOutput],
      script: StatefulScript,
      gasRemaining: GasBox
  )(implicit networkConfig: NetworkConfig, logConfig: LogConfig): ExeResult[TxScriptExecution] = {
    runTxScript(worldState, blockEnv, tx, Some(preOutputs), script, gasRemaining)
  }

  def runTxScript(
      worldState: WorldState.Staging,
      blockEnv: BlockEnv,
      tx: TransactionAbstract,
      preOutputsOpt: Option[AVector[AssetOutput]],
      script: StatefulScript,
      gasRemaining: GasBox
  )(implicit networkConfig: NetworkConfig, logConfig: LogConfig): ExeResult[TxScriptExecution] = {
    for {
      context <- StatefulContext.build(blockEnv, tx, gasRemaining, worldState, preOutputsOpt)
      result  <- runTxScript(context, script)
    } yield result
  }

  def runTxScript(
      context: StatefulContext,
      script: StatefulScript
  ): ExeResult[TxScriptExecution] = {
    for {
      _      <- execute(context, script.toObject, AVector.empty)
      result <- prepareResult(context)
    } yield result
  }

  def runTxScriptWithOutputs(
      context: StatefulContext,
      script: StatefulScript
  ): ExeResult[(AVector[Val], TxScriptExecution)] = {
    for {
      outputs <- executeWithOutputs(context, script.toObject, AVector.empty)
      result  <- prepareResult(context)
    } yield (outputs, result)
  }

  private def prepareResult(context: StatefulContext): ExeResult[TxScriptExecution] = {
    for {
      _ <- checkRemainingSignatures(context)
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
      failed(TooManySignatures)
    }
  }

  private def default(ctx: StatefulContext): StatefulVM = {
    new StatefulVM(ctx, Stack.ofCapacity(frameStackMaxSize), Stack.ofCapacity(opStackMaxSize))
  }

  def execute(
      context: StatefulContext,
      obj: ContractObj[StatefulContext],
      args: AVector[Val]
  ): ExeResult[Unit] = {
    val vm = default(context)
    vm.execute(obj, 0, args)
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
    executeWithOutputs(context, obj, args, 0)
  }
}
