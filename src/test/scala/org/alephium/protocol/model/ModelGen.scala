package org.alephium.protocol.model

import org.alephium.AlephiumSpec
import org.alephium.constant.Genesis
import org.alephium.crypto._
import org.scalacheck.Gen

object ModelGen {
  private val (sk, pk) = ED25519.generateKeyPair()

  val txInputGen = for {
    index <- Gen.choose(0, 10)
  } yield TxInput(Keccak256.zero, index) // Has to use zero here to pass test on ubuntu

  val txOutputGen = for {
    value <- Gen.choose(0, 100)
  } yield TxOutput(value, pk)

  val transactionGen = for {
    inputNum  <- Gen.choose(0, 5)
    inputs    <- Gen.listOfN(inputNum, txInputGen)
    outputNum <- Gen.choose(0, 5)
    outputs   <- Gen.listOfN(outputNum, txOutputGen)
  } yield Transaction.from(UnsignedTransaction(inputs, outputs), sk)

  val blockGen = for {
    txNum <- Gen.choose(0, 100)
    txs   <- Gen.listOfN(txNum, transactionGen)
  } yield Block.from(Seq.empty, txs)

  def blockGenWith(deps: Seq[Keccak256]): Gen[Block] =
    for {
      txNum <- Gen.choose(0, 100)
      txs   <- Gen.listOfN(txNum, transactionGen)
    } yield Block.from(deps, txs)

  def blockForTransfer(to: ED25519PublicKey, value: Int): Block = {
    val txOutput1 = TxOutput(value, to)
    val txOutput2 = TxOutput(AlephiumSpec.testBalance - value, AlephiumSpec.testPublicKey)
    val txInput   = TxInput(Genesis.block.transactions.head.hash, 0)
    val transaction = Transaction.from(
      UnsignedTransaction(Seq(txInput), Seq(txOutput1, txOutput2)),
      AlephiumSpec.testPrivateKey
    )
    Block.from(Seq(Genesis.block.hash), Seq(transaction))
  }
}
