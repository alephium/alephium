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
      val context        = RunContext(rawData, AVector(instruction))
      val stack          = Stack.unsafe(stackElems, config.maxStackSize)
      val signatureStack = Stack.popOnly(signatures)
      RunState(context, 0, stack, signatureStack)
    }
  }

  it should "test OP_PUSH" in new Fixture {
    val state = buildState(OP_PUSH(data))

    state.run().isRight is true
    state.instructionCount is 1
    state.stack.isEmpty is false

    val data0 = state.stack.pop().right.value
    data0 is data
    state.stack.pop().isLeft is true
  }

  it should "test OP_DUP" in new Fixture {
    val state = buildState(OP_DUP.unsafe(1), stackElems = AVector(data))

    state.run().isRight is true
    state.instructionCount is 1
    state.stack.size is 2

    val data1 = state.stack.pop().right.value
    data1 is data
    state.stack.size is 1
  }

  it should "test OP_EQUALVERIFY" in new Fixture {
    val state = buildState(OP_EQUALVERIFY, stackElems = AVector(data, data))

    state.run().right.value
    state.instructionCount is 1
    state.stack.isEmpty is true

    val data0  = ByteString.fromInts(2)
    val state0 = buildState(OP_EQUALVERIFY, stackElems = AVector(data, data0))
    state0.run().left.value is VerificationFailed
  }

  it should "test OP_KECCAK256" in new Fixture {
    val state = buildState(OP_KECCAK256, stackElems = AVector(data))

    state.run().isRight is true
    state.instructionCount is 1
    state.stack.size is 1

    val data0 = state.stack.pop().right.value
    data0 is Keccak256.hash(data).bytes
    state.stack.isEmpty is true
  }

  it should "test OP_CHECKSIG" in new Fixture {
    val (priKey, pubKey) = ED25519.generatePriPub()
    val signature        = ED25519.sign(data, priKey)

    val state = buildState(OP_CHECKSIG,
                           rawData    = data,
                           stackElems = AVector(pubKey.bytes),
                           signatures = AVector(signature))

    state.run().isRight is true
    state.instructionCount is 1
    state.stack.isEmpty is true
  }
}
