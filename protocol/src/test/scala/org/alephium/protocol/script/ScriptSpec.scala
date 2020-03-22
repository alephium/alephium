package org.alephium.protocol.script

import akka.util.ByteString
import org.scalatest.EitherValues._

import org.alephium.crypto._
import org.alephium.protocol.config.ScriptConfig
import org.alephium.util.{AlephiumSpec, AVector}

class ScriptSpec extends AlephiumSpec {
  trait Fixture {
    val data      = ByteString.fromInts(1, 2)
    val data0     = ByteString.fromInts(3, 4)
    val (sk, pk)  = ED25519.generatePriPub()
    val pkHash    = Keccak256.hash(pk.bytes)
    val signature = ED25519.sign(data, sk)

    implicit val config: ScriptConfig = new ScriptConfig { override def maxStackSize: Int = 100 }
  }

  it should "test for public key hash scripts" in new Fixture {
    val pubScript = PubScript(
      AVector[Instruction](OP_DUP.unsafe(1),
                           OP_KECCAK256,
                           OP_PUSH.unsafe(pkHash.bytes),
                           OP_EQUALVERIFY,
                           OP_CHECKSIG))
    val priScript  = AVector[Instruction](OP_PUSH.unsafe(pk.bytes))
    val signatures = AVector(signature)
    val witness    = Witness(priScript, signatures)
    val witness0   = Witness(priScript, AVector.empty)
    val witness1   = Witness(priScript.init, signatures)
    val witness2   = Witness(OP_PUSH.unsafe(pk.bytes) +: priScript, signatures)

    Script.run(data, pubScript, witness).isRight is true
    Script.run(data0, pubScript, witness).left.value is VerificationFailed
    Script.run(data, pubScript, witness0).left.value is StackUnderflow
    Script.run(data, pubScript, witness1).left.value is IndexOverflow
    Script.run(data, pubScript, witness2).left.value is InvalidFinalState
  }

  it should "test for script hash" in new Fixture {
    val scriptToHash = AVector[Instruction](OP_PUSH.unsafe(pk.bytes), OP_CHECKSIG)
    val scriptRaw    = Instruction.serializeScript(scriptToHash)
    val scriptHash   = Keccak256.hash(scriptRaw)
    val pubScript    = PubScript(AVector[Instruction](OP_SCRIPTKECCAK256.from(scriptHash)))
    val priScript    = AVector[Instruction](OP_PUSH.unsafe(scriptRaw))
    val signatures   = AVector(signature)
    val witness      = Witness(priScript, signatures)
    val witness0     = Witness(priScript, AVector.empty)
    val witness1     = Witness(priScript.init, signatures)
    val witness2     = Witness(OP_PUSH.unsafe(pk.bytes) +: priScript, signatures)

    Script.run(data, pubScript, witness).isRight is true
    Script.run(data0, pubScript, witness).left.value is VerificationFailed
    Script.run(data, pubScript, witness0).left.value is StackUnderflow
    Script.run(data, pubScript, witness1).left.value is StackUnderflow
    Script.run(data, pubScript, witness2).left.value is InvalidFinalState
  }
}
