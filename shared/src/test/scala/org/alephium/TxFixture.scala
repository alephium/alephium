package org.alephium

import io.circe.parser.parse
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.protocol.Genesis
import org.alephium.protocol.model._
import org.alephium.util.Hex

import scala.io.Source

trait TxFixture {

  def blockForTransfer(to: ED25519PublicKey, value: BigInt): Block = {
    assert(value >= 0)

    val txOutput1 = TxOutput(value, to)
    val txOutput2 = TxOutput(testBalance - value, testPublicKey)
    val txInput   = TxInput(Genesis.block.transactions.head.hash, 0)
    val transaction = Transaction.from(
      UnsignedTransaction(Seq(txInput), Seq(txOutput1, txOutput2)),
      testPrivateKey
    )
    Block.from(Seq(Genesis.block.hash), Seq(transaction), 0)
  }

  private val json = parse(Source.fromResource("genesis.json").mkString).right.get

  private val test = json.hcursor.downField("test")
  val testPrivateKey: ED25519PrivateKey =
    ED25519PrivateKey.unsafeFrom(Hex(test.get[String]("privateKey").right.get))
  val testPublicKey: ED25519PublicKey =
    ED25519PublicKey.unsafeFrom(Hex(test.get[String]("publicKey").right.get))
  val testBalance: BigInt = test.get[BigInt]("balance").right.get
}
