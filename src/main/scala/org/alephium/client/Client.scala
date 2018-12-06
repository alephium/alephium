package org.alephium.client

import java.math.BigInteger

import akka.actor.{ActorRef, Props}
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey, Keccak256}
import org.alephium.protocol.message.{Message, SendBlock}
import org.alephium.protocol.model.{Block, Transaction, TxOutput, UnsignedTransaction}
import org.alephium.storage.BlockPool
import org.alephium.util.BaseActor

import scala.annotation.tailrec

case class Client(privateKey: ED25519PrivateKey,
                  publicKey: ED25519PublicKey,
                  blockPool: ActorRef,
                  tcpHandler: ActorRef)
    extends BaseActor {
  import Client._

  private val address: ED25519PublicKey = publicKey

  override def receive: Receive = {
    case Transfer(toAddress, value) =>
      blockPool ! BlockPool.GetUTXOs(address, value)
      context become transfer(toAddress, value)
  }

  def transfer(toAddress: ED25519PublicKey, value: BigInteger): Receive = {
    case BlockPool.UTXOs(header, txInputs, total) =>
      val txOutput1   = TxOutput(value, toAddress)
      val txOutput2   = TxOutput(total subtract value, address)
      val txOutputs   = Seq(txOutput1, txOutput2)
      val unsigned    = UnsignedTransaction(txInputs, txOutputs)
      val transaction = Transaction.from(unsigned, privateKey)
      val block       = Client.mine(Seq(header), Seq(transaction))

      blockPool ! BlockPool.AddBlocks(Seq(block))
      val message = Message(SendBlock(block))
      tcpHandler ! message

      context become receive
    case BlockPool.NoEnoughBalance =>
      logger.info(s"Not able to transfer $value Aleph")
      context become receive
  }
}

object Client {
  def props(privateKey: ED25519PrivateKey,
            publicKey: ED25519PublicKey,
            blockPool: ActorRef,
            tcpHandler: ActorRef): Props =
    Props(new Client(privateKey, publicKey, blockPool, tcpHandler))

  sealed trait Command
  case class Transfer(toAddress: ED25519PublicKey, value: BigInteger) extends Command

  sealed trait Event
  case object TransferSuccess extends Event
  case object TransferFailed extends Event

  def mine(deps: Seq[Keccak256], transactions: Seq[Transaction]): Block = {
    @tailrec
    def loop(nonce: BigInteger): Block = {
      val block = Block.from(deps, transactions, nonce)
      if (isDifficult(Keccak256.hash(block.hash))) {
        block
      } else loop(nonce add BigInteger.ONE)
    }
    loop(BigInteger.ZERO)
  }

  def isDifficult(hash: Keccak256): Boolean = {
    hash.bytes(0) == 0 //TODO: improve this
  }
}
