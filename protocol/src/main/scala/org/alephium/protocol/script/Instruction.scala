package org.alephium.protocol.script

import akka.util.ByteString

import org.alephium.serde._

sealed trait Instruction extends Product with Serializable

object Instruction {
  //scalastyle:off magic.number
  implicit val serde: Serde[Instruction] = new Serde[Instruction] {
    override def serialize(input: Instruction): ByteString = input match {
      case x: OP_PUSH     => ByteString.apply(0x01) ++ bytestringSerde.serialize(x.bytes)
      case OP_EQUALVERIFY => ByteString.apply(0x02)
      case OP_KECCAK256   => ByteString.apply(0x03)
      case OP_CHECKSIG    => ByteString.apply(0x04)
    }

    override def _deserialize(input: ByteString): SerdeResult[(Instruction, ByteString)] = {
      byteSerde._deserialize(input).flatMap {
        case (opCode, rest) =>
          opCode match {
            case 0x01 =>
              bytestringSerde._deserialize(rest).map {
                case (bytes, rest1) => (OP_PUSH(bytes), rest1)
              }
            case 0x02 =>
              Right((OP_EQUALVERIFY, rest))
            case 0x03 =>
              Right((OP_KECCAK256, rest))
            case 0x04 =>
              Right((OP_CHECKSIG, rest))
          }
      }
    }
  }
  //scalastyle:on magic.number
}

// TODO: Control Instructions

// Stack Instructions
case class OP_PUSH(bytes: ByteString) extends Instruction

// BitwiseInstructions
case object OP_EQUALVERIFY extends Instruction

// Crypto Instructions
case object OP_KECCAK256 extends Instruction
case object OP_CHECKSIG  extends Instruction
