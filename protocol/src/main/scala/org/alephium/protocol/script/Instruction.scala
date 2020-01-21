package org.alephium.protocol.script

import akka.util.ByteString

sealed trait Instruction extends Product with Serializable

// Control Instructions
//case object OP_IF     extends Instruction
//case object OP_ELSE   extends Instruction
//case object OP_ENDIF  extends Instruction
//case object OP_OUTPUT extends Instruction

// Stack Instructions
case class OP_PUSH(bytes: ByteString) extends Instruction
//case object OP_DUP                    extends Instruction

// BitwiseInstructions
case object OP_EQUALVERIFY extends Instruction

// Crypto Instructions
case object OP_KECCAK256 extends Instruction
case object OP_CHECKSIG  extends Instruction
