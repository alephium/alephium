package org.alephium.protocol.script

import akka.util.ByteString
import org.scalatest.Assertion
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

    def priScript: AVector[Instruction]
    def pubScript: PubScript
    def signatures: AVector[ED25519Signature] = AVector(signature)
    def witness: Witness                      = Witness(priScript, signatures)

    def test(): Assertion = {
      val witness0 = Witness(priScript, AVector.empty)
      val witness1 = Witness(priScript.init, signatures)
      val witness2 = Witness(OP_PUSH.unsafe(pk.bytes) +: priScript, signatures)

      Script.run(data, pubScript, witness).isRight is true
      Script.run(data0, pubScript, witness).left.value is VerificationFailed
      Script.run(data, pubScript, witness0).left.value is StackUnderflow
      Script.run(data, pubScript, witness1).left.value is StackUnderflow
      Script.run(data, pubScript, witness2).left.value is InvalidFinalState
    }
  }

  it should "test for public key hash scripts" in new Fixture {
    val pubScript        = PubScript.p2pkh(pk)
    val priScript        = PriScript.p2pkh(pk)
    override val witness = Witness.p2pkh(pk, signature)

    test()
  }

  it should "test for script hash" in new Fixture {
    val pubScript        = PubScript.p2sh(pk)
    val priScript        = PriScript.p2sh(pk)
    override val witness = Witness.p2sh(pk, signature)

    test()
  }

  it should "test for multi signatures" in new Fixture {
    val (sk1, pk1)          = ED25519.generatePriPub()
    val signature1          = ED25519.sign(data, sk1)
    override val signatures = AVector(signature, signature1)

    val scriptToHash = AVector[Instruction](OP_PUSH.unsafe(pk.bytes),
                                            OP_PUSH.unsafe(pk1.bytes),
                                            OP_PUSH.unsafe(ByteString(2)),
                                            OP_CHECKMULTISIGVERIFY)
    val scriptRaw = Instruction.serializeScript(scriptToHash)
    val pubScript = PubScript.p2sh(scriptRaw)
    val priScript = PriScript.p2sh(scriptRaw)

    test()
  }
}
