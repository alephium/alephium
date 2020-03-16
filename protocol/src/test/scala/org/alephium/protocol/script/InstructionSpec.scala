package org.alephium.protocol.script

import akka.util.ByteString
import org.scalatest.EitherValues._

import org.alephium.crypto.{ED25519, ED25519Signature, Keccak256}
import org.alephium.protocol.config.ScriptConfig
import org.alephium.util.{AlephiumSpec, AVector}

class InstructionSpec extends AlephiumSpec {
  trait Fixture {
    implicit val config: ScriptConfig = new ScriptConfig { override def maxStackSize: Int = 3 }

    val data = ByteString.fromInts(1)

    def buildState(instruction: Instruction,
                   rawData: ByteString                   = ByteString.empty,
                   stackElems: AVector[ByteString]       = AVector.empty,
                   signatures: AVector[ED25519Signature] = AVector.empty): RunState = {
      val context = RunContext(rawData, AVector(instruction))
      val stack   = Stack.unsafe(stackElems)
      RunState(context, 0, stack, signatures)
    }
  }

  it should "test OP_PUSH" in new Fixture {
    val state0 = buildState(OP_PUSH(data))

    val state1 = state0.run().right.value
    state1.instructionCount is 1
    state1.stack.isEmpty is false

    val (data1, stack1) = state1.stack.pop().right.value
    data1 is data
    stack1.pop().isLeft is true
  }

  it should "test OP_DUP" in new Fixture {
    val state0 = buildState(OP_DUP.unsafe(1), stackElems = AVector(data))

    val state1 = state0.run().right.value
    state1.instructionCount is 1
    state1.stack.size is 2

    val (data1, stack1) = state1.stack.pop().right.value
    data1 is data
    stack1.size is 1
  }

  it should "test OP_EQUALVERIFY" in new Fixture {
    val state0 = buildState(OP_EQUALVERIFY, stackElems = AVector(data, data))

    val state1 = state0.run().right.value
    state1.instructionCount is 1
    state1.stack.isEmpty is true

    val data0  = ByteString.fromInts(2)
    val state3 = buildState(OP_EQUALVERIFY, stackElems = AVector(data, data0))
    state3.run().left.value is VerificationFailed
  }

  it should "test OP_KECCAK256" in new Fixture {
    val state0 = buildState(OP_KECCAK256, stackElems = AVector(data))

    val state1 = state0.run().right.value
    state1.instructionCount is 1
    state1.stack.size is 1

    val (data1, stack1) = state1.stack.pop().right.value
    data1 is Keccak256.hash(data).bytes
    stack1.isEmpty is true
  }

  it should "test OP_CHECKSIG" in new Fixture {
    val (priKey, pubKey) = ED25519.generatePriPub()
    val signature        = ED25519.sign(data, priKey)

    val state0 = buildState(OP_CHECKSIG,
                            rawData    = data,
                            stackElems = AVector(pubKey.bytes),
                            signatures = AVector(signature))

    val state1 = state0.run().right.value
    state1.instructionCount is 1
    state1.stack.isEmpty is true
  }
}
