package org.alephium.protocol.vm

import scala.annotation.tailrec

import org.alephium.crypto.ED25519Signature
import org.alephium.protocol.ALF
import org.alephium.protocol.model.Block
import org.alephium.util.AVector

class Runtime[Ctx <: Context](val stack: Stack[Frame[Ctx]], var returnTo: AVector[Val])

object Runtime {
  def apply[Ctx <: Context](stack: Stack[Frame[Ctx]]): Runtime[Ctx] =
    new Runtime(stack, AVector.ofSize(0))
}

sealed trait VM[Ctx <: Context] {
  def execute(ctx: Ctx,
              obj: ContractObj[Ctx],
              methodIndex: Int,
              args: AVector[Val]): ExeResult[AVector[Val]] = {
    val stack = Stack.ofCapacity[Frame[Ctx]](frameStackMaxSize)
    val rt    = Runtime[Ctx](stack)

    stack.push(obj.startFrame(ctx, methodIndex, args, value => Right(rt.returnTo = value)))
    execute(stack).map(_ => rt.returnTo)
  }

  @tailrec
  private def execute(stack: Stack[Frame[Ctx]]): ExeResult[Unit] = {
    if (!stack.isEmpty) {
      val currentFrame = stack.topUnsafe
      if (currentFrame.isComplete) {
        stack.pop()
        execute(stack)
      } else {
        currentFrame.execute()
      }
    } else {
      Right(())
    }
  }
}

object StatelessVM extends VM[StatelessContext] {
  def runAssetScript(worldState: WorldState,
                     txHash: ALF.Hash,
                     script: StatelessScript,
                     args: AVector[Val],
                     signature: ED25519Signature): ExeResult[WorldState] = {
    val context = StatelessContext(txHash, signature, worldState)
    val obj     = script.toObject(args)
    execute(context, obj, 0, AVector.empty).map(_ => context.worldState)
  }
}

object StatefulVM extends VM[StatefulContext] {
  def runTxScripts(worldState: WorldState, block: Block): ExeResult[WorldState] = {
    block.transactions.foldE(worldState) {
      case (worldState, tx) =>
        tx.unsigned.scriptOpt match {
          case Some(script) => runTxScript(worldState, tx.hash, script)
          case None         => Right(worldState)
        }
    }
  }

  def runTxScript(worldState: WorldState,
                  txHash: ALF.Hash,
                  script: StatefulScript): ExeResult[WorldState] = {
    val context = StatefulContext(txHash, worldState)
    val obj     = script.toObject(AVector.empty)
    execute(context, obj, 0, AVector.empty).map(_ => context.worldState)
  }

  def runContract(worldState: WorldState,
                  txHash: ALF.Hash,
                  contract: StatefulContract,
                  address: ALF.Hash,
                  fields: AVector[Val],
                  args: AVector[Val]): ExeResult[WorldState] = {
    val context = StatefulContext(txHash, worldState)
    val obj     = contract.toObject(address, fields)
    execute(context, obj, 0, args).map(_ => context.worldState)
  }
}
