package org.alephium.protocol.script

import akka.util.ByteString
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.crypto.{ED25519, ED25519PublicKey, ED25519Signature, Keccak256}
import org.alephium.protocol.config.ScriptConfig
import org.alephium.serde.SerdeError
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

  behavior of "Instruction helper functions"

  it should "test op_code register" in {
    assertThrows[RuntimeException](Instruction.register(0, OP_CHECKMULTISIGVERIFY))
    assertThrows[RuntimeException](Instruction.register(-1, OP_CHECKMULTISIGVERIFY))
    assertThrows[RuntimeException](Instruction.register(256, OP_CHECKMULTISIGVERIFY))
  }

  it should "fail serde" in {
    Instruction.serde.deserialize(ByteString.empty).left.value is a[SerdeError.NotEnoughBytes]
    Instruction.serde.deserialize(ByteString(0xFF)).left.value is a[SerdeError.Validation]
  }

  it should "serde scripts" in {
    val script       = AVector[Instruction](OP_IF, OP_ELSE, OP_ENDIF)
    val serialized   = Instruction.serializeScript(script)
    val deserialized = Instruction.deserializeScript(serialized).right.value
    deserialized is script

    Instruction.deserializeScript(ByteString(0xFF)).left.value is a[InvalidScript]
  }

  it should "decode publicKey" in {
    val publicKey = ED25519PublicKey.generate
    Instruction.decodePublicKey(publicKey.bytes) isE publicKey
    Instruction.decodePublicKey(publicKey.bytes.init).left.value is InvalidPublicKey
  }

  it should "test safeHead/safeTake" in {
    Instruction.safeHead(ByteString(0)).right.value._1 is 0.toByte
    Instruction.safeHead(ByteString.empty).left.value is a[SerdeError.NotEnoughBytes]
    Instruction.safeTake(ByteString(0), 1).right.value._1 is ByteString(0)
    Instruction.safeTake(ByteString(0), 2).left.value is a[SerdeError.NotEnoughBytes]
  }

  it should "fail serialization of SimpleInstruction" in {
    OP_IF.deserialize(ByteString(0)).left.value is a[SerdeError.Validation]
  }

  behavior of "Stack Instructions"

  it should "test the constructors of OP_PUSH" in {
    OP_PUSH.from(ByteString(0, 1)).isRight is true

    val bytes0 = ByteString.empty
    assertThrows[AssertionError](OP_PUSH.unsafe(bytes0))
    OP_PUSH.from(bytes0).isLeft is true

    val bytes1 = ByteString.fromArray(Array.ofDim[Byte](0xFF + 1))
    assertThrows[AssertionError](OP_PUSH.unsafe(bytes1))
    OP_PUSH.from(bytes1).isLeft is true
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
      OP_PUSH.deserialize(op.serialize()) isE (op -> ByteString.empty)
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

    val data1 = ByteString(0x07, 0xFF) ++ ByteString.fromArray(Array.fill(0x100)(0x07))
    OP_PUSH.deserialize(data1).isRight is true
    val data2 = ByteString(0x07, 0x100) ++ ByteString.fromArray(Array.fill(0x101)(0x07))
    OP_PUSH.deserialize(data2).left.value is a[SerdeError.Validation]
    val data3 = ByteString(0x08, 0xFF) ++ ByteString.fromArray(Array.fill(0x100)(0x07))
    OP_PUSH.deserialize(data3).left.value is a[SerdeError.Validation]
  }

  it should "test the constructors of OP_DUP" in {
    assertThrows[AssertionError](OP_DUP.unsafe(0))
    assertThrows[AssertionError](OP_DUP.unsafe(0x100))
    OP_DUP.from(0).isLeft is true
    OP_DUP.from(1).isRight is true
    OP_DUP.from(0x100).isLeft is true
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
      OP_DUP.deserialize(op.serialize()) isE (op -> ByteString.empty)
    }
    def test1(index: Int): Assertion = {
      val op = opGen(index)
      op.serialize() is ByteString(0x1F, index)
      OP_DUP.deserialize(op.serialize()) isE (op -> ByteString.empty)
    }

    assertThrows[AssertionError](test0(0))
    (1 to 15).foreach(index => test0(index))
    test1(16)
    test1(33)
    test1(255)
    assertThrows[AssertionError](test0(256))
    assertThrows[AssertionError](test1(256))

    OP_DUP.deserialize(ByteString(0x0F)).left.value is a[SerdeError.Validation]
    OP_DUP.deserialize(ByteString(0x20)).left.value is a[SerdeError.Validation]
  }

  it should "test the constructors of OP_SWAP" in {
    assertThrows[AssertionError](OP_SWAP.unsafe(0))
    assertThrows[AssertionError](OP_SWAP.unsafe(1))
    assertThrows[AssertionError](OP_SWAP.unsafe(0x100))
    OP_SWAP.from(0).isLeft is true
    OP_SWAP.from(1).isLeft is true
    OP_SWAP.from(2).isRight is true
    OP_SWAP.from(0x100).isLeft is true
  }

  it should "test OP_SWAP" in new Fixture {
    val state = buildState(OP_SWAP.unsafe(2), stackElems = AVector(data, data ++ data))
    state.stack.peek(1) isE data ++ data
    state.stack.peek(2) isE data

    state.run().isRight is true
    state.instructionIndex is 1
    state.stack.size is 2

    val data0 = state.stack.pop().right.value
    data0 is data
    val data1 = state.stack.pop().right.value
    data1 is data ++ data

    OP_SWAP.deserialize(ByteString(0x1F)).left.value is a[SerdeError.Validation]
    OP_SWAP.deserialize(ByteString(0x30)).left.value is a[SerdeError.Validation]
  }

  it should "serde OP_SWAP" in {
    def opGen(index: Int): OP_SWAP = OP_SWAP.unsafe(index)
    def test0(index: Int): Assertion = {
      val op = opGen(index)
      op.serialize() is ByteString(0x1E + index)
      OP_SWAP.deserialize(op.serialize()) isE (op -> ByteString.empty)
    }
    def test1(index: Int): Assertion = {
      val op = opGen(index)
      op.serialize() is ByteString(0x2F, index)
      OP_SWAP.deserialize(op.serialize()) isE (op -> ByteString.empty)
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

  it should "test the constructors of OP_POP" in {
    assertThrows[AssertionError](OP_POP.unsafe(0))
    assertThrows[AssertionError](OP_POP.unsafe(0x100))
    OP_POP.from(0).isLeft is true
    OP_POP.from(1).isRight is true
    OP_POP.from(0x100).isLeft is true
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
      OP_POP.deserialize(op.serialize()) isE (op -> ByteString.empty)
    }
    def test1(index: Int): Assertion = {
      val op = opGen(index)
      op.serialize() is ByteString(0x3F, index)
      OP_POP.deserialize(op.serialize()) isE (op -> ByteString.empty)
    }

    assertThrows[AssertionError](test0(0))
    (1 to 15).foreach(test0)
    test1(16)
    test1(33)
    test1(255)
    assertThrows[AssertionError](test0(256))
    assertThrows[AssertionError](test1(256))

    OP_POP.deserialize(ByteString(0x2F)).left.value is a[SerdeError.Validation]
    OP_POP.deserialize(ByteString(0x40)).left.value is a[SerdeError.Validation]
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
        state.stack.pop() isE expected.get
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
        test(x, x)
        test(x, y)
        test(y, y)
      }
    }

    def test(x: Int, y: Int): Assertion = {
      val state = buildState(OP, stackElems = AVector(toByteString(y), toByteString(x)))

      state.run().isRight is true
      state.instructionIndex is 1
      state.stack.size is 1

      val expected = if (op(x, y)) Instruction.True else Instruction.False
      state.stack.pop() isE expected
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
    test()
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

  it should "test OP_CHECKSIGVERIFY" in new Fixture {
    val (priKey, pubKey) = ED25519.generatePriPub()
    val signature        = ED25519.sign(data, priKey)

    val state = buildState(OP_CHECKSIGVERIFY,
                           rawData    = data,
                           stackElems = AVector(pubKey.bytes),
                           signatures = AVector(signature))

    state.run().isRight is true
    state.instructionIndex is 1
    state.stack.isEmpty is true
  }

  it should "test OP_CHECKMULTISIGVERIFY" in new Fixture {
    val (priKey0, pubKey0) = ED25519.generatePriPub()
    val signature0         = ED25519.sign(data, priKey0)
    val (priKey1, pubKey1) = ED25519.generatePriPub()
    val signature1         = ED25519.sign(data, priKey1)

    val state0 = buildState(OP_CHECKMULTISIGVERIFY,
                            rawData    = data,
                            stackElems = AVector(pubKey0.bytes, pubKey1.bytes, ByteString(2)),
                            signatures = AVector(signature0, signature1))
    state0.run().isRight is true
    state0.instructionIndex is 1
    state0.stack.isEmpty is true

    val state1 = buildState(OP_CHECKMULTISIGVERIFY,
                            rawData    = data,
                            stackElems = AVector(pubKey0.bytes, ByteString(2)),
                            signatures = AVector(signature0, signature1))
    state1.run().left.value is StackUnderflow

    val state2 = buildState(OP_CHECKMULTISIGVERIFY,
                            rawData    = data,
                            stackElems = AVector(pubKey0.bytes, pubKey1.bytes, ByteString(2)),
                            signatures = AVector(signature0, signature0))
    state2.run().left.value is VerificationFailed

    val state3 = buildState(OP_CHECKMULTISIGVERIFY, stackElems = AVector(ByteString(0)))
    state3.run().left.value is InvalidParameters

    val state4 = buildState(OP_CHECKMULTISIGVERIFY, stackElems = AVector(ByteString(0, 1)))
    state4.run().left.value is InvalidParameters
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
    state0.stack.peek(1) isE scriptRaw

    val state1 = state0.reload(AVector[Instruction](OP_SCRIPTKECCAK256.from(hash)))
    state1.run().isRight is true
    state1.stack.size is 1
    state1.stack.pop() isE data ++ data

    val state2 = buildState(OP_PUSH.unsafe(scriptRaw ++ ByteString(0)))
    state2.run().isRight is true
    val state3 = state2.reload(AVector[Instruction](OP_SCRIPTKECCAK256.from(hash)))
    state3.run().left.value is InvalidScriptHash
  }

  it should "serde OP_SCRIPTKECCAK256" in {
    val instruction = OP_SCRIPTKECCAK256.from(Keccak256.random)
    Instruction.serde.deserialize(instruction.serialize()) isE instruction

    OP_SCRIPTKECCAK256.deserialize(ByteString(0)).left.value is a[SerdeError.Validation]
  }
}
