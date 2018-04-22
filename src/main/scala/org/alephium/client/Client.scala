package org.alephium.client

import akka.actor.ActorRef
import com.typesafe.scalalogging.StrictLogging
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey, Keccak256}
import org.alephium.protocol.message.{Message, SendBlock}
import org.alephium.protocol.model.{Block, Transaction, TxOutput, UnsignedTransaction}
import org.alephium.storage.BlockPool
import org.alephium.util.UInt

import scala.annotation.tailrec

case class Client(privateKey: ED25519PrivateKey,
                  publicKey: ED25519PublicKey,
                  blockPool: BlockPool,
                  tcpHandler: ActorRef)
    extends StrictLogging {
  val address: ED25519PublicKey = publicKey

  def getBlance: UInt = blockPool.getBalance(address)._2

  def transfer(toAddress: ED25519PublicKey, value: UInt): Unit = {
    blockPool.getUTXOs(address, value) match {
      case Some((txInputs, totalValue)) =>
        val txOutput1   = TxOutput(value, toAddress)
        val txOutput2   = TxOutput(totalValue minus value, address)
        val txOutputs   = Seq(txOutput1, txOutput2)
        val unsigned    = UnsignedTransaction(txInputs, txOutputs)
        val transaction = Transaction.from(unsigned, privateKey)
        val prevBlock   = blockPool.getBestHeader.hash
        val block       = Client.mine(Seq(prevBlock), Seq(transaction))

        blockPool.addBlock(block)
        val message = Message(SendBlock(block))
        tcpHandler ! message
      case None =>
        logger.info(s"Not able to transfer $value Aleph")
    }
  }
}

object Client {
  def mine(deps: Seq[Keccak256], transactions: Seq[Transaction]): Block = {
    @tailrec
    def loop(nonce: UInt): Block = {
      val block = Block.from(deps, transactions, nonce)
      if (isDifficult(Keccak256.hash(block.hash))) {
        block
      } else loop(nonce plus UInt.one)
    }
    loop(UInt.zero)
  }

  def isDifficult(hash: Keccak256): Boolean = {
    hash.bytes(0) == 0 //TODO: improve this
  }
}
