package org.alephium.protocol.script

import akka.util.ByteString
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.crypto.{ED25519, ED25519Signature, Keccak256}
import org.alephium.protocol.config.ScriptConfig
import org.alephium.util.{AlephiumSpec, AVector}

class InstructionSpec extends AlephiumSpec {
  trait Fixture {
    implicit val config: ScriptConfig = new ScriptConfig { override def maxStackSize: Int = 3 }

    val data = ByteString.fromInts(1)

    def toByteString(i: BigInt): ByteString = ByteString(i.toByteArray)

    def buildState(instruction: Instruction,
                   rawData: ByteString                   = ByteString.empty,
                   stackElems: AVector[ByteString]       = AVector.empty,
                   signatures: AVector[ED25519Signature] = AVector.empty): RunState = {
      buildState1(AVector(instruction), rawData, stackElems, signatures)
    }

    def buildState1(instructions: AVector[Instruction],
                    rawData: ByteString                   = ByteString.empty,
                    stackElems: AVector[ByteString]       = AVector.empty,
                    signatures: AVector[ED25519Signature] = AVector.empty): RunState = {
      val context        = RunContext(rawData, instructions)
      val stack          = Stack.unsafe(stackElems, config.maxStackSize)
      val signatureStack = Stack.popOnly(signatures)
      RunState(context, 0, stack, signatureStack)
    }
  }

  it should "test OP_PUSH" in new Fixture {
    val state = buildState(OP_PUSH.unsafe(data))

    state.run().isRight is true
    state.instructionIndex is 1
    state.stack.isEmpty is false

    val data0 = state.stack.pop().right.value
    data0 is data
    state.stack.pop().isLeft is true
  }

  it should "serde OP_PUSH" in {
    def opGen(n: Int): OP_PUSH = OP_PUSH.unsafe(ByteString(Array.ofDim[Byte](n)))
    def test(n: Int, prefix: Int*): Assertion = {
      val op = opGen(n)
      op.serialize() is (ByteString(prefix: _*) ++ op.bytes)
      OP_PUSH.deserialize(op.serialize()).right.value is (op -> ByteString.empty)
    }

    assertThrows[AssertionError](test(0))
    test(1, 0x00)
    test(2, 0x01)
    test(4, 0x02)
    test(8, 0x03)
    test(16, 0x04)
    test(32, 0x05)
    test(64, 0x06)
    test(17, 0x07, 17)
    test(65, 0x07, 65)
    test(255, 0x07, 255)
    assertThrows[AssertionError](test(256, 0x07, 255))
  }

  it should "test OP_DUP" in new Fixture {
    val state = buildState(OP_DUP.unsafe(1), stackElems = AVector(data))

    state.run().isRight is true
    state.instructionIndex is 1
    state.stack.size is 2

    val data1 = state.stack.pop().right.value
    data1 is data
    state.stack.size is 1
  }

  it should "serde OP_DUP" in {
    def opGen(index: Int): OP_DUP = OP_DUP.unsafe(index)
    def test0(index: Int): Assertion = {
      val op = opGen(index)
      op.serialize() is ByteString(0x0F + index)
      OP_DUP.deserialize(op.serialize()).right.value is (op -> ByteString.empty)
    }
    def test1(index: Int): Assertion = {
      val op = opGen(index)
      op.serialize() is ByteString(0x1F, index)
      OP_DUP.deserialize(op.serialize()).right.value is (op -> ByteString.empty)
    }

    assertThrows[AssertionError](test0(0))
    (1 to 15).foreach(index => test0(index))
    test1(16)
    test1(33)
    test1(255)
    assertThrows[AssertionError](test0(256))
    assertThrows[AssertionError](test1(256))
  }

  it should "test OP_SWAP" in new Fixture {
    val state = buildState(OP_SWAP.unsafe(2), stackElems = AVector(data, data ++ data))
    state.stack.peek(1).right.value is data ++ data
    state.stack.peek(2).right.value is data

    state.run().isRight is true
    state.instructionIndex is 1
    state.stack.size is 2

    val data0 = state.stack.pop().right.value
    data0 is data
    val data1 = state.stack.pop().right.value
    data1 is data ++ data
  }

  it should "serde OP_SWAP" in {
    def opGen(index: Int): OP_SWAP = OP_SWAP.unsafe(index)
    def test0(index: Int): Assertion = {
      val op = opGen(index)
      op.serialize() is ByteString(0x1E + index)
      OP_SWAP.deserialize(op.serialize()).right.value is (op -> ByteString.empty)
    }
    def test1(index: Int): Assertion = {
      val op = opGen(index)
      op.serialize() is ByteString(0x2F, index)
      OP_SWAP.deserialize(op.serialize()).right.value is (op -> ByteString.empty)
    }

    assertThrows[AssertionError](test0(0))
    assertThrows[AssertionError](test0(1))
    (2 to 16).foreach(index => test0(index))
    test1(17)
    test1(33)
    test1(255)
    assertThrows[AssertionError](test0(256))
    assertThrows[AssertionError](test1(256))
  }

  it should "test OP_POP" in new Fixture {
    val state = buildState(OP_POP.unsafe(2), stackElems = AVector(data, data))

    state.run().isRight is true
    state.instructionIndex is 1
    state.stack.isEmpty is true
  }

  it should "serde OP_POP" in {
    def opGen(index: Int): OP_POP = OP_POP.unsafe(index)
    def test0(index: Int): Assertion = {
      val op = opGen(index)
      op.serialize() is ByteString(0x2F + index)
      OP_POP.deserialize(op.serialize()).right.value is (op -> ByteString.empty)
    }
    def test1(index: Int): Assertion = {
      val op = opGen(index)
      op.serialize() is ByteString(0x3F, index)
      OP_POP.deserialize(op.serialize()).right.value is (op -> ByteString.empty)
    }

    assertThrows[AssertionError](test0(0))
    (1 to 15).foreach(test0)
    test1(16)
    test1(33)
    test1(255)
    assertThrows[AssertionError](test0(256))
    assertThrows[AssertionError](test1(256))
  }

  abstract class ArithmeticFixture(OP: SimpleInstruction) extends Fixture {
    def validate(bs: ByteString): Assertion = {
      (bs.size <= 32) is true
    }

    def test(a: BigInt, b: BigInt, expected: BigInt): Assertion = {
      val state = buildState(OP, stackElems = AVector(toByteString(b), toByteString(a)))

      state.run().isRight is true
      state.instructionIndex is 1
      state.stack.size is 1

      val data0 = state.stack.peek(1).right.value
      validate(data0)
      data0 is toByteString(expected)
    }

    def testFailure(a: BigInt, b: BigInt, failure: RunFailure = IntegerOverFlow): Assertion = {
      val state = buildState(OP, stackElems = AVector(toByteString(b), toByteString(a)))

      state.run().left.value is failure
    }
  }

  it should "test OP_ADD" in new ArithmeticFixture(OP_ADD) {
    forAll { (x: Int, y: Int) =>
      test(BigInt(x), BigInt(y), BigInt(x) + BigInt(y))
    }

    test(BigInt(1) << 254, BigInt(0), BigInt(1) << 254)
    testFailure(BigInt(1) << 255, BigInt(0))
  }

  it should "test OP_SUB" in new ArithmeticFixture(OP_SUB) {
    forAll { (x: Int, y: Int) =>
      test(BigInt(x), BigInt(y), BigInt(x) - BigInt(y))
    }

    test(BigInt(1) << 254, BigInt(0), BigInt(1) << 254)
    testFailure(BigInt(1) << 255, BigInt(0))
    testFailure(BigInt(0), BigInt(1) << 255 + 1)
  }

  it should "test OP_MUL" in new ArithmeticFixture(OP_MUL) {
    forAll { (x: Int, y: Int) =>
      test(BigInt(x), BigInt(y), BigInt(x) * BigInt(y))
    }

    test(BigInt(1) << 127, BigInt(1) << 127, BigInt(1) << 254)
    testFailure(BigInt(1) << 128, BigInt(1) << 127)
  }

  it should "test OP_DIV" in new ArithmeticFixture(OP_DIV) {
    forAll { (x: Int, y: Int) =>
      whenever(y != 0) {
        test(BigInt(x), BigInt(y), BigInt(x) / BigInt(y))
      }
    }

    test(BigInt(1) << 255 - 1, BigInt(1), BigInt(1) << 255 - 1)
    testFailure(BigInt(1) << 255, BigInt(1))
    testFailure(BigInt(1), BigInt(0), ArithmeticError("BigInteger divide by zero"))
  }

  behavior of "Flow Control"

  trait IfFixture extends Fixture {
    def instructions: AVector[Instruction]
    def test(input: ByteString): Assertion                       = test(input, None)
    def test(input: ByteString, expected: ByteString): Assertion = test(input, Some(expected))
    def test(input: ByteString, expected: Option[ByteString]): Assertion = {
      val state = buildState1(instructions, stackElems = AVector(input))

      state.run().isRight is true
      state.instructionIndex is instructions.length

      if (expected.nonEmpty) {
        state.stack.size is 1
        state.stack.pop().right.value is expected.get
      } else {
        state.stack.size is 0
      }
    }
    def testFailure(initial: ByteString, expected: RunFailure): Assertion = {
      val state = buildState1(instructions, stackElems = AVector(initial))
      state.run().left.value is expected
    }
  }

  it should "test OP_IF case 0" in new IfFixture {
    val instructions = AVector[Instruction](OP_IF, OP_PUSH.unsafe(data), OP_ENDIF)
    test(Instruction.True, data)
    test(Instruction.False)
  }

  it should "test OP_IF case 1" in new IfFixture {
    val instructions =
      AVector[Instruction](OP_IF,
                           OP_PUSH.unsafe(data),
                           OP_ELSE,
                           OP_PUSH.unsafe(data ++ data),
                           OP_ENDIF)
    test(Instruction.True, data)
    test(Instruction.False, data ++ data)
  }

  it should "test OP_IF case 2" in new IfFixture {
    val instructions =
      AVector[Instruction](OP_IF, OP_PUSH.unsafe(Instruction.True), OP_IF, OP_ENDIF, OP_ENDIF)
    test(Instruction.True)
    test(Instruction.False)
  }

  it should "test OP_IF case 3" in new IfFixture {
    val instructions =
      AVector[Instruction](OP_IF,
                           OP_PUSH.unsafe(Instruction.True),
                           OP_IF,
                           OP_ENDIF,
                           OP_ELSE,
                           OP_ENDIF)
    test(Instruction.True)
    test(Instruction.False)
  }

  it should "test OP_IF case 4" in new IfFixture {
    val instructions =
      AVector[Instruction](OP_IF,
                           OP_PUSH.unsafe(Instruction.True),
                           OP_IF,
                           OP_ELSE,
                           OP_ENDIF,
                           OP_ENDIF)
    test(Instruction.True)
    test(Instruction.False)
  }

  it should "test OP_IF case 5" in new IfFixture {
    val instructions =
      AVector[Instruction](
        OP_IF,
        OP_PUSH.unsafe(Instruction.True),
        OP_IF,
        OP_PUSH.unsafe(data),
        OP_ELSE,
        OP_ENDIF,
        OP_ELSE,
        OP_PUSH.unsafe(Instruction.False),
        OP_IF,
        OP_ELSE,
        OP_PUSH.unsafe(data ++ data),
        OP_ENDIF,
        OP_ENDIF
      )
    test(Instruction.True, data)
    test(Instruction.False, data ++ data)
  }

  it should "fail OP_IF case 0" in new IfFixture {
    val instructions = AVector[Instruction](OP_IF, OP_PUSH.unsafe(data), OP_ENDIF)
    testFailure(data ++ data, InvalidBoolean)
  }

  it should "fail OP_IF case 1" in new IfFixture {
    val instructions = AVector[Instruction](OP_IF, OP_PUSH.unsafe(data))
    testFailure(Instruction.True, IncompleteIfScript)
  }

  it should "fail OP_IF case 2" in new IfFixture {
    val instructions = AVector[Instruction](OP_IF, OP_PUSH.unsafe(data), OP_ELSE)
    testFailure(Instruction.True, IncompleteIfScript)
  }

  it should "fail OP_IF case 3" in new IfFixture {
    val instructions = AVector[Instruction](OP_PUSH.unsafe(data), OP_ENDIF)
    testFailure(Instruction.True, IncompleteIfScript)
  }

  it should "fail OP_IF case 4" in new IfFixture {
    val instructions = AVector[Instruction](OP_PUSH.unsafe(data), OP_ELSE, OP_ENDIF)
    testFailure(Instruction.True, IncompleteIfScript)
  }

  it should "test OP_ELSE" in new Fixture {
    val state = buildState(OP_ELSE)
    state.run().left.value is IncompleteIfScript
  }

  it should "test OP_ENDIF" in new Fixture {
    val state = buildState(OP_ENDIF)
    state.run().left.value is IncompleteIfScript
  }

  it should "test OP_VERIFY" in new Fixture {
    val state0 = buildState(OP_VERIFY, stackElems = AVector(Instruction.True))
    state0.run().isRight is true

    val state1 = buildState(OP_VERIFY, stackElems = AVector(Instruction.False))
    state1.run().left.value is VerificationFailed
  }

  behavior of "Logic Instructions"

  abstract class LogicFixture(OP: Instruction) extends Fixture {
    def op(x: Int, y: Int): Boolean

    def test(): Assertion = {
      forAll { (x: Int, y: Int) =>
        val state = buildState(OP, stackElems = AVector(toByteString(y), toByteString(x)))

        state.run().isRight is true
        state.instructionIndex is 1
        state.stack.size is 1

        val expected = if (op(x, y)) Instruction.True else Instruction.False
        state.stack.pop().right.value is expected
      }
    }
  }

  it should "test OP_EQ" in new LogicFixture(OP_EQ) {
    override def op(x: Int, y: Int): Boolean = x.equals(y)
    test()
  }

  it should "test OP_NE" in new LogicFixture(OP_NE) {
    override def op(x: Int, y: Int): Boolean = x != y
    test()
  }

  it should "test OP_LT" in new LogicFixture(OP_LT) {
    override def op(x: Int, y: Int): Boolean = x < y
    test()
  }

  it should "test OP_GT" in new LogicFixture(OP_GT) {
    override def op(x: Int, y: Int): Boolean = x > y
    test()
  }

  it should "test OP_LE" in new LogicFixture(OP_LE) {
    override def op(x: Int, y: Int): Boolean = x <= y
    test()
  }

  it should "test OP_GE" in new LogicFixture(OP_GE) {
    override def op(x: Int, y: Int): Boolean = x >= y
    test()
  }

  it should "test OP_EQUAL" in new LogicFixture(OP_EQUAL) {
    override def op(x: Int, y: Int): Boolean = x.equals(y)
  }

  it should "test OP_EQUALVERIFY" in new Fixture {
    val state = buildState(OP_EQUALVERIFY, stackElems = AVector(data, data))

    state.run().isRight is true
    state.instructionIndex is 1
    state.stack.isEmpty is true

    val data0  = ByteString.fromInts(2)
    val state0 = buildState(OP_EQUALVERIFY, stackElems = AVector(data, data0))
    state0.run().left.value is VerificationFailed
  }

  behavior of "Crypto Instructions"

  it should "test OP_KECCAK256" in new Fixture {
    val state = buildState(OP_KECCAK256, stackElems = AVector(data))

    state.run().isRight is true
    state.instructionIndex is 1
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
    state.instructionIndex is 1
    state.stack.isEmpty is true
  }

  behavior of "Script Instructions"

  it should "test OP_SCRIPTKECCAK256" in new Fixture {
    val script =
      AVector[Instruction](OP_PUSH.unsafe(data), OP_POP.unsafe(1), OP_PUSH.unsafe(data ++ data))
    val scriptRaw = Instruction.serializeScript(script)
    val hash      = Keccak256.hash(scriptRaw)

    val state0 = buildState(OP_PUSH.unsafe(scriptRaw))
    state0.run().isRight is true
    state0.stack.size is 1
    state0.stack.peek(1).right.value is scriptRaw

    val state1 = state0.reload(AVector[Instruction](OP_SCRIPTKECCAK256.from(hash)))
    state1.run().isRight is true
    state1.stack.size is 1
    state1.stack.pop().right.value is data ++ data

    val state2 = buildState(OP_PUSH.unsafe(scriptRaw ++ ByteString(0)))
    state2.run().isRight is true
    val state3 = state2.reload(AVector[Instruction](OP_SCRIPTKECCAK256.from(hash)))
    state3.run().left.value is InvalidScriptHash
  }
}
