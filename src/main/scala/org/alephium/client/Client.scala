package org.alephium.client

import akka.actor.ActorRef
import com.typesafe.scalalogging.StrictLogging
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.protocol.message.{Message, SendBlock}
import org.alephium.protocol.model.{Block, Transaction, TxOutput, UnsignedTransaction}
import org.alephium.storage.BlockPool

case class Client(privateKey: ED25519PrivateKey,
                  publicKey: ED25519PublicKey,
                  blockPool: BlockPool,
                  tcpHandler: ActorRef)
    extends StrictLogging {
  val address: ED25519PublicKey = publicKey

  def getBlance: Int = blockPool.getBalance(address)._2

  def transfer(toAddress: ED25519PublicKey, value: Int): Unit = {
    blockPool.getUTXOs(address, value) match {
      case Some((txInputs, totalValue)) =>
        val txOutput1   = TxOutput(value, toAddress)
        val txOutput2   = TxOutput(totalValue - value, address)
        val txOutputs   = Seq(txOutput1, txOutput2)
        val unsigned    = UnsignedTransaction(txInputs, txOutputs)
        val transaction = Transaction.from(unsigned, privateKey)
        val block       = Block.from(Seq.empty, Seq(transaction))
        val message     = Message(SendBlock(block))
        tcpHandler ! message
      case None =>
        logger.info(s"Not able to transfer $value Aleph")
    }
  }
}
