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
import scala.collection.mutable

import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, EitherF}

sealed abstract class VM[Ctx <: Context](
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

  @inline
  private def execute(
      obj: ContractObj[Ctx],
      methodIndex: Int,
      args: AVector[Val],
      returnToOpt: Option[AVector[Val] => ExeResult[Unit]]
  ): ExeResult[Unit] = {
    for {
      startFrame <- obj.startFrame(ctx, methodIndex, args, operandStack, returnToOpt)
      _          <- frameStack.push(startFrame)
      _          <- executeFrames()
    } yield ()
  }

  @tailrec
  private def executeFrames(): ExeResult[Unit] = {
    frameStack.top match {
      case Some(topFrame) =>
        executeCurrentFrame(topFrame) match {
          case Right(_)    => executeFrames()
          case Left(error) => Left(error)
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

final class StatelessVM(
    ctx: StatelessContext,
    frameStack: Stack[Frame[StatelessContext]],
    operandStack: Stack[Val]
) extends VM(ctx, frameStack, operandStack) {
  protected def switchBackFrame(
      currentFrame: Frame[StatelessContext],
      nextFrame: Frame[StatelessContext]
  ): ExeResult[Unit] = Right(())

  protected def completeLastFrame(lastFrame: Frame[StatelessContext]): ExeResult[Unit] = Right(())
}

final class StatefulVM(
    ctx: StatefulContext,
    frameStack: Stack[Frame[StatefulContext]],
    operandStack: Stack[Val]
) extends VM(ctx, frameStack, operandStack) {
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
      } yield ()
    } else {
      Right(())
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def outputGeneratedBalances(outputBalances: Balances): ExeResult[Unit] = {
    EitherF.foreachTry(outputBalances.all) { case (lockupScript, balances) =>
      balances.toTxOutput(lockupScript).map { outputOpt =>
        outputOpt.foreach { output =>
          lockupScript match {
            case LockupScript.P2C(contractId) =>
              val contractOutput = output.asInstanceOf[ContractOutput]
              val outputRef      = ctx.nextContractOutputRef(contractOutput)
              ctx.updateContractAsset(contractId, outputRef, contractOutput)
            case _ => ()
          }
          ctx.generatedOutputs.addOne(output)
          ()
        }
      }
    }
  }
}

object StatelessVM {
  final case class AssetScriptExecution(gasRemaining: GasBox)

  def runAssetScript(
      txId: Hash,
      initialGas: GasBox,
      script: StatelessScript,
      args: AVector[Val],
      signature: Signature
  ): ExeResult[AssetScriptExecution] = {
    val stack = Stack.unsafe[Signature](mutable.ArraySeq(signature), 1)
    runAssetScript(txId, initialGas, script, args, stack)
  }

  def runAssetScript(
      txId: Hash,
      initialGas: GasBox,
      script: StatelessScript,
      args: AVector[Val],
      signatures: Stack[Signature]
  ): ExeResult[AssetScriptExecution] = {
    val context = StatelessContext(txId, initialGas, signatures)
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

  private def execute(
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
      generatedOutputs: AVector[TxOutput]
  )

  // dryrun will not commit worldstate changes, which is efficient for tx validation
  def dryrunTxScript(
      worldState: WorldState.Cached,
      tx: TransactionAbstract,
      preOutputs: AVector[TxOutput],
      script: StatefulScript,
      gasRemaining: GasBox
  ): ExeResult[TxScriptExecution] = {
    runTxScript(worldState, tx, Some(preOutputs), script, gasRemaining)
  }

  // run will commit worldstate changes
  def runTxScript(
      worldState: WorldState.Cached,
      tx: TransactionAbstract,
      preOutputsOpt: Option[AVector[TxOutput]],
      script: StatefulScript,
      gasRemaining: GasBox
  ): ExeResult[TxScriptExecution] = {
    for {
      context <- StatefulContext.build(tx, gasRemaining, worldState, preOutputsOpt)
      _       <- execute(context, script.toObject, AVector.empty)
    } yield {
      context.commitStates()
      TxScriptExecution(
        context.gasRemaining,
        AVector.from(context.contractInputs),
        AVector.from(context.generatedOutputs)
      )
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
      args: AVector[Val]
  ): ExeResult[AVector[Val]] = {
    val vm = default(context)
    vm.executeWithOutputs(obj, 0, args)
  }
}
