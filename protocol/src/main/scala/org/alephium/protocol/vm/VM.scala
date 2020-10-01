package org.alephium.protocol.vm

import scala.annotation.tailrec
import scala.collection.mutable

import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.model.{TransactionAbstract, TxOutput}
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
      val currentFrame = frameStack.topUnsafe
      val frameResult = for {
        newFrameOpt <- currentFrame.execute()
        _ <- newFrameOpt match {
          case Some(frame) => frameStack.push(frame)
          case None =>
            for {
              _ <- frameStack.pop()
              _ <- frameStack.top match {
                case Some(frame) => frame.reloadFields()
                case None        => Right(())
              }
            } yield ()
        }
      } yield ()
      frameResult match {
        case Right(_)    => executeFrames()
        case Left(error) => Left(error)
      }
    } else {
      Right(())
    }
  }
}

final class StatelessVM(ctx: StatelessContext,
                        frameStack: Stack[Frame[StatelessContext]],
                        operandStack: Stack[Val])
    extends VM(ctx, frameStack, operandStack)

final class StatefulVM(ctx: StatefulContext,
                       frameStack: Stack[Frame[StatefulContext]],
                       operandStack: Stack[Val])
    extends VM(ctx, frameStack, operandStack)

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

  def runTxScript(worldState: WorldState,
                  tx: TransactionAbstract,
                  script: StatefulScript): ExeResult[(AVector[TxOutput], WorldState)] = {
    val context = if (script.methods.head.isPayable) {
      StatefulContext.payable(tx, worldState)
    } else {
      StatefulContext.nonPayable(tx.hash, worldState)
    }
    val obj = script.toObject
    execute(context, obj, AVector.empty).map(_ =>
      AVector.from(context.generatedOutputs) -> context.worldState)
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
