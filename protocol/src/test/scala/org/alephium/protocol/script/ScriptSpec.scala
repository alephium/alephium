package org.alephium.protocol.script

import akka.util.ByteString

import org.alephium.crypto._
import org.alephium.util.{AlephiumSpec, AVector}

class ScriptSpec extends AlephiumSpec {
  it should "check for public key hash" in {
    forAll { n: Int =>
      val data      = ByteString.fromInts(n)
      val data0     = ByteString.fromInts(n - 1)
      val (sk, pk)  = ED25519.generatePriPub()
      val pkHash    = Keccak256.hash(pk.bytes)
      val signature = ED25519.sign(data, sk)

      val pubScript =
        AVector[Instruction](OP_KECCAK256, OP_PUSH(pkHash.bytes), OP_EQUALVERIFY, OP_CHECKSIG)
      val priScript  = AVector[Instruction](OP_PUSH(pk.bytes))
      val signatures = AVector(signature.bytes)

      Script.run(data, pubScript, Witness(priScript, signatures)) is ExeSuccessful
      Script.run(data0, pubScript, Witness(priScript, signatures)) is VerificationFailed
      Script.run(data, pubScript, Witness(priScript, AVector.empty)) is a[NonCategorized]
      Script.run(data, pubScript, Witness(priScript.init, signatures)) is a[NonCategorized]
      Script.run(data, pubScript, Witness(OP_PUSH(pk.bytes) +: priScript, signatures)) is InvalidFinalState
    }
  }
}
