package org.alephium.constant

import io.circe.Json
import io.circe.parser.parse
import org.alephium.crypto.{ED25519PublicKey, ED25519Signature}
import org.alephium.protocol.model.{Block, Transaction, TxOutput, UnsignedTransaction}
import org.alephium.util.{Hex, UInt}

import scala.io.Source

object Genesis {
  def loadGenesis(file: String): Block = {
    val genesisJson: Json =
      parse(Source.fromResource(file).mkString).right.get

    loadGenesis(genesisJson)
  }

  def loadGenesis(genesisJson: Json): Block = {
    val transactions = {
      val cursor   = genesisJson.hcursor
      val balances = cursor.downField("balance")
      balances.keys.get map { key =>
        val publicKey = ED25519PublicKey.unsafeFrom(Hex(key))
        val balance   = UInt.fromString(balances.get[Int](key).right.get.toString)
        val unsigned =
          UnsignedTransaction(Seq.empty, Seq(TxOutput(balance, publicKey)))
        Transaction(unsigned, ED25519Signature.zero)
      }
    }

    Block.from(Seq.empty, transactions.toSeq, UInt.zero)
  }

  val block: Block                   = loadGenesis("genesis.json")
  val transactions: Seq[Transaction] = block.transactions
}
