package org.alephium.protocol.script

import akka.util.ByteString

import org.alephium.protocol.config.ScriptConfig

object Script {
  def run(data: ByteString, pubScript: PubScript, witness: Witness)(
      implicit config: ScriptConfig): RunResult[Unit] = {
    val contextPri = RunContext(data, witness.privateScript)
    val contextPub = RunContext(data, pubScript.instructions)

    val statePri = RunState.empty(contextPri, witness.signatures)
    for {
      _ <- statePri.run()
      statePub = statePri.reload(contextPub)
      _ <- statePub.run()
      _ <- if (statePub.isValidFinalState) Right(()) else Left(InvalidFinalState)
    } yield ()
  }
}
