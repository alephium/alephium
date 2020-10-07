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
import org.alephium.protocol.model.{ContractOutputRef, TransactionAbstract, TxOutput}
import org.alephium.serde._
import org.alephium.util.{AVector, U64}

sealed abstract class VM[Ctx <: Context](ctx: Ctx,
                                         frameStack: Stack[Frame[Ctx]],
                                         operandStack: Stack[Val]) {
  def execute(obj: ContractObj[Ctx], methodIndex: Int, args: AVector[Val]): ExeResult[Unit] = {
    for {
      startFrame <- obj.startFrame(ctx, methodIndex, args, operandStack)
      _          <- frameStack.push(startFrame)
      _          <- executeFrames()
    } yield ()
  }

  def executeWithOutputs(obj: ContractObj[Ctx],
                         methodIndex: Int,
                         args: AVector[Val]): ExeResult[AVector[Val]] = {
    var outputs: AVector[Val] = AVector.ofSize(0)
    val returnTo: AVector[Val] => ExeResult[Unit] = returns => { outputs = returns; Right(()) }
    for {
      startFrame <- obj.startFrameWithOutputs(ctx, methodIndex, args, operandStack, returnTo)
      _          <- frameStack.push(startFrame)
      _          <- executeFrames()
    } yield outputs
  }

  @tailrec
  private def executeFrames(): ExeResult[Unit] = {
    if (frameStack.nonEmpty) {
      executeCurrentFrame(frameStack.topUnsafe) match {
        case Right(_)    => executeFrames()
        case Left(error) => Left(error)
      }
    } else {
      Right(())
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
        case Some(nextFrame) =>
          for {
            _ <- nextFrame.reloadFields()
            _ <- checkBalances(currentFrame, nextFrame)
          } yield ()
        case None =>
          checkFinalBalances(currentFrame)
      }
    } yield ()
  }

  protected def checkBalances(currentFrame: Frame[Ctx], nextFrame: Frame[Ctx]): ExeResult[Unit]

  protected def checkFinalBalances(lastFrame: Frame[Ctx]): ExeResult[Unit]
}

final class StatelessVM(ctx: StatelessContext,
                        frameStack: Stack[Frame[StatelessContext]],
                        operandStack: Stack[Val])
    extends VM(ctx, frameStack, operandStack) {
  protected def checkBalances(currentFrame: Frame[StatelessContext],
                              nextFrame: Frame[StatelessContext]): ExeResult[Unit] = Right(())

  protected def checkFinalBalances(lastFrame: Frame[StatelessContext]): ExeResult[Unit] = Right(())
}

final class StatefulVM(ctx: StatefulContext,
                       frameStack: Stack[Frame[StatefulContext]],
                       operandStack: Stack[Val])
    extends VM(ctx, frameStack, operandStack) {
  protected def checkBalances(currentFrame: Frame[StatefulContext],
                              nextFrame: Frame[StatefulContext]): ExeResult[Unit] = {
    if (currentFrame.method.isPayable) {
      val resultOpt = for {
        currentBalances <- currentFrame.balanceStateOpt
        nextBalances    <- nextFrame.balanceStateOpt
        _               <- nextBalances.remaining.merge(currentBalances.remaining)
        _               <- nextBalances.remaining.merge(currentBalances.approved)
      } yield ()
      resultOpt.toRight(InvalidBalances)
    } else Right(())
  }

  protected def checkFinalBalances(lastFrame: Frame[StatefulContext]): ExeResult[Unit] = {
    if (lastFrame.method.isPayable) {
      val resultOpt = for {
        balances <- lastFrame.balanceStateOpt
        _        <- balances.remaining.merge(balances.approved)
      } yield outputRemaining(balances.remaining)
      resultOpt.toRight(InvalidBalances)
    } else Right(())
  }

  private def outputRemaining(remaining: Frame.Balances): Unit = {
    remaining.all.foreach {
      case (lockupScript, balances) =>
        balances.toTxOutput(lockupScript).foreach(ctx.generatedOutputs.addOne)
    }
  }
}

object StatelessVM {
  def runAssetScript(txHash: Hash,
                     script: StatelessScript,
                     args: AVector[Val],
                     signature: Signature): ExeResult[Unit] = {
    val context = StatelessContext(txHash, signature)
    val obj     = script.toObject
    execute(context, obj, args)
  }

  def runAssetScript(txHash: Hash,
                     script: StatelessScript,
                     args: AVector[Val],
                     signatures: Stack[Signature]): ExeResult[Unit] = {
    val context = StatelessContext(txHash, signatures)
    val obj     = script.toObject
    execute(context, obj, args)
  }

  def execute(context: StatelessContext,
              obj: ContractObj[StatelessContext],
              args: AVector[Val]): ExeResult[Unit] = {
    val vm = new StatelessVM(context,
                             Stack.ofCapacity(frameStackMaxSize),
                             Stack.ofCapacity(opStackMaxSize))
    vm.execute(obj, 0, args)
  }

  def executeWithOutputs(context: StatelessContext,
                         obj: ContractObj[StatelessContext],
                         args: AVector[Val]): ExeResult[AVector[Val]] = {
    val vm = new StatelessVM(context,
                             Stack.ofCapacity(frameStackMaxSize),
                             Stack.ofCapacity(opStackMaxSize))
    vm.executeWithOutputs(obj, 0, args)
  }
}

object StatefulVM {
  def contractCreation(code: StatefulContract,
                       initialState: AVector[Val],
                       lockupScript: LockupScript,
                       alfAmount: U64): StatefulScript = {
    val codeRaw  = serialize(code)
    val stateRaw = serialize(initialState)
    val method = Method[StatefulContext](
      isPublic   = true,
      isPayable  = true,
      localsType = AVector.empty,
      returnType = AVector.empty,
      instrs = AVector(
        U64Const(Val.U64(alfAmount)),
        AddressConst(Val.Address(lockupScript)),
        ApproveAlf,
        BytesConst(Val.ByteVec(mutable.ArraySeq.from(stateRaw))),
        BytesConst(Val.ByteVec(mutable.ArraySeq.from(codeRaw))),
        CreateContract
      )
    )
    StatefulScript(AVector(method))
  }

  final case class TxScriptExecution(contractInputs: AVector[ContractOutputRef],
                                     generatedOutputs: AVector[TxOutput],
                                     worldState: WorldState)

  def runTxScript(worldState: WorldState,
                  tx: TransactionAbstract,
                  script: StatefulScript): ExeResult[TxScriptExecution] = {
    val context = if (script.methods.head.isPayable) {
      StatefulContext.payable(tx, worldState)
    } else {
      StatefulContext.nonPayable(tx.hash, worldState)
    }
    val obj = script.toObject
    execute(context, obj, AVector.empty).map(
      worldState =>
        TxScriptExecution(AVector.from(context.contractInputs),
                          AVector.from(context.generatedOutputs),
                          worldState))
  }

  def execute(context: StatefulContext,
              obj: ContractObj[StatefulContext],
              args: AVector[Val]): ExeResult[WorldState] = {
    val vm =
      new StatefulVM(context, Stack.ofCapacity(frameStackMaxSize), Stack.ofCapacity(opStackMaxSize))
    vm.execute(obj, 0, args).map(_ => context.worldState)
  }

  def executeWithOutputs(context: StatefulContext,
                         obj: ContractObj[StatefulContext],
                         args: AVector[Val]): ExeResult[AVector[Val]] = {
    val vm =
      new StatefulVM(context, Stack.ofCapacity(frameStackMaxSize), Stack.ofCapacity(opStackMaxSize))
    vm.executeWithOutputs(obj, 0, args)
  }
}
