package org.alephium.protocol.vm

trait RunTime[Ctx <: Context] {
  def context: Ctx
  def frame: Frame[Ctx]
}

trait VM[Ctx <: Context] {
  def execute(script: Script[Ctx]): ExeResult[Unit] = {
    val startFrame = script.startFrame
    startFrame.execute()
  }
}

object StatelessVM extends VM[StatelessContext]

object StatefulVM extends VM[StatefulContext]
