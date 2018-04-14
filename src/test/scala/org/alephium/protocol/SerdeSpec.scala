package org.alephium.protocol

import org.alephium.AlephiumSuite
import org.alephium.crypto.{ED25519, Keccak256}
import org.alephium.serde.{serialize, deserialize}
import org.scalacheck.Gen
import org.scalatest.TryValues._

class SerdeSpec extends AlephiumSuite {

  "Block" should "be serde correctly" in {
    val (sk, pk) = ED25519.generateKeyPair()

    val txInputGen = for {
      index <- Gen.choose(0, 10)
    } yield TxInput(Keccak256.random, index)
    val txOutputGen = for {
      value <- Gen.choose(0, 100)
    } yield TxOutput(value, pk)

    val txGen = for {
      inputNum <- Gen.choose(0, 5)
      inputs <- Gen.listOfN(inputNum, txInputGen)
      outputNum <- Gen.choose(0, 5)
      outputs <- Gen.listOfN(outputNum, txOutputGen)
    } yield Transaction.from(UnsignedTransaction(inputs, outputs),sk)

    val blockGen = for {
      txNum <- Gen.choose(0, 100)
      txs <- Gen.listOfN(txNum, txGen)
    } yield Block.from(Seq.empty, txs)

    forAll(blockGen) { input =>
      val output = deserialize[Block](serialize(input)).success.value
      output shouldBe input
    }
  }
}
