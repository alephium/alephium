package org.alephium.protocol.vm.lang

import org.scalatest.Assertion

import org.alephium.protocol.{Hash, SignatureSchema}
import org.alephium.protocol.vm._
import org.alephium.serde._
import org.alephium.util._

// scalastyle:off no.equal
class CompilerSpec extends AlephiumSpec with ContextGenerators {
  it should "parse asset script" in {
    val script =
      s"""
         |// comment
         |AssetScript Foo {
         |  fn bar(a: U256, b: U256) -> (U256) {
         |    return (a + b)
         |  }
         |}
         |""".stripMargin
    Compiler.compileAssetScript(script).isRight is true
  }

  it should "parse tx script" in {
    val script =
      s"""
         |// comment
         |TxScript Foo {
         |  fn bar(a: U256, b: U256) -> (U256) {
         |    return (a + b)
         |  }
         |}
         |""".stripMargin
    Compiler.compileTxScript(script).isRight is true
  }

  it should "parse contracts" in {
    val contract =
      s"""
         |// comment
         |TxContract Foo(mut x: U64, mut y: U64, c: U64) {
         |  // comment
         |  fn add0(a: U64, b: U64) -> (U64) {
         |    return (a + b)
         |  }
         |
         |  fn add1() -> (U64) {
         |    return (x + y)
         |  }
         |
         |  fn add2(d: U64) -> () {
         |    let mut z = 0u
         |    z = d
         |    x = x + z // comment
         |    y = y + z // comment
         |    return
         |  }
         |}
         |""".stripMargin
    Compiler.compileContract(contract).isRight is true
  }

  it should "infer types" in {
    def check(xMut: String,
              a: String,
              aType: String,
              b: String,
              bType: String,
              rType: String,
              fname: String,
              validity: Boolean = false) = {
      val contract =
        s"""
         |TxContract Foo($xMut x: U64) {
         |  fn add($a: $aType, $b: $bType) -> ($rType) {
         |    x = a + b
         |    return (a - b)
         |  }
         |
         |  fn $fname() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
      Compiler.compileContract(contract).isRight is validity
    }

    check("mut", "a", "U64", "b", "U64", "U64", "foo", true)
    check("", "a", "U64", "b", "U64", "U64", "foo")
    check("mut", "x", "U64", "b", "U64", "U64", "foo")
    check("mut", "a", "U64", "x", "U64", "U64", "foo")
    check("mut", "a", "I64", "b", "U64", "U64", "foo")
    check("mut", "a", "U64", "b", "I64", "U64", "foo")
    check("mut", "a", "U64", "b", "U64", "I64", "foo")
    check("mut", "a", "U64", "b", "U64", "U64, U64", "foo")
    check("mut", "a", "U64", "b", "U64", "U64", "add")
  }

  it should "parse multiple contracts" in {
    val input =
      s"""
         |TxContract Foo() {
         |  fn foo(bar: Bar) -> () {
         |    return bar.foo()
         |  }
         |
         |  fn bar() -> () {
         |    return
         |  }
         |}
         |
         |TxScript Bar {
         |  fn bar(foo: Foo) -> () {
         |    return foo.bar()
         |  }
         |
         |  fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    Compiler.compileContract(input, 0).isRight is true
    Compiler.compileTxScript(input, 1).isRight is true
  }

  trait Fixture {
    def test(input: String,
             args: AVector[Val],
             output: AVector[Val] = AVector.empty,
             fields: AVector[Val] = AVector.empty): Assertion = {
      val contract = Compiler.compileContract(input).toOption.get

      deserialize[StatefulContract](serialize(contract)) isE contract
      val (obj, context) = prepareContract(contract, fields)
      StatefulVM.execute(context, obj, 0, args) isE output
    }
  }

  it should "generate IR code" in new Fixture {
    val input =
      s"""
         |TxContract Foo(x: U64) {
         |
         |  fn add(a: U64) -> (U64) {
         |    return square(x) + square(a)
         |  }
         |
         |  fn square(n: U64) -> (U64) {
         |    return n * n
         |  }
         |}
         |""".stripMargin

    test(input,
         AVector(Val.U64(U64.Two)),
         AVector(Val.U64(U64.unsafe(5))),
         AVector(Val.U64(U64.One)))
  }

  it should "verify signature" in {
    def input(hash: Hash) =
      s"""
         |AssetScript P2PKH {
         |  fn verify(pk: ByteVec) -> () {
         |    let hash = @${hash.toHexString}
         |    checkEq!(hash, blake2b!(pk))
         |    checkSignature!(pk)
         |    return
         |  }
         |}
         |""".stripMargin

    val (priKey, pubKey) = SignatureSchema.generatePriPub()
    val pubKeyHash       = Hash.hash(pubKey.bytes)
    val signature        = SignatureSchema.sign(Hash.zero.bytes, priKey)

    val script = Compiler.compileAssetScript(input(pubKeyHash)).toOption.get
    deserialize[StatelessScript](serialize(script)) isE script

    val args = AVector[Val](Val.ByteVec.from(pubKey))
    StatelessVM
      .runAssetScript(cachedWorldState, Hash.zero, script, args, signature)
      .isRight is true
  }

  it should "converse values" in new Fixture {
    test(
      s"""
         |TxContract Conversion() {
         |  fn main() -> () {
         |    let mut x = 5u
         |    x = u64!(5i)
         |    x = u64!(5I)
         |    x = u64!(5U)
         |    return
         |  }
         |}
         |""".stripMargin,
      AVector.empty
    )
  }

  it should "test while" in new Fixture {
    test(
      s"""
         |TxContract While() {
         |  fn main() -> (U64) {
         |    let mut x = 5
         |    let mut done = false
         |    while !done {
         |      x = x + x - 3
         |      if x % 5 == 0 { done = true }
         |    }
         |    return x
         |  }
         |}
         |""".stripMargin,
      AVector.empty,
      AVector(Val.U64(U64.unsafe(35)))
    )
  }

  it should "test the following typical examples" in new Fixture {
    test(
      s"""
         |TxContract Main() {
         |
         |  fn main() -> () {
         |    let an_i64 = 5i // Suffix annotation
         |    let an_u64 = 5u
         |    let an_i256 = 5I
         |    let an_u256 = 5U
         |
         |    // Or a default will be used.
         |    let default_integer = 7   // `U64`
         |
         |    // A mutable variable's value can be changed.
         |    let mut another_i64 = 5i
         |    let mut another_u64 = 5u
         |    let mut another_i256 = 5I
         |    let mut another_u256 = 5U
         |    another_i64 = 6i
         |    another_u64 = 6u
         |    another_i256 = 6I
         |    another_u256 = 6U
         |
         |    let mut bool = true
         |    bool = false
         |
         |    return
         |  }
         |}
         |""".stripMargin,
      AVector.empty
    )

    test(
      s"""
         |TxContract Fibonacci() {
         |  fn f(n: I64) -> (I64) {
         |    if n < 2i {
         |      return n
         |    } else {
         |      return f(n-1i) + f(n-2i)
         |    }
         |  }
         |}
         |""".stripMargin,
      AVector(Val.I64(I64.from(10))),
      AVector[Val](Val.I64(I64.from(55)))
    )

    test(
      s"""
         |TxContract Fibonacci() {
         |  fn f(n: U64) -> (U64) {
         |    if n < 2 {
         |      return n
         |    } else {
         |      return f(n-1) + f(n-2)
         |    }
         |  }
         |}
         |""".stripMargin,
      AVector(Val.U64(U64.unsafe(10))),
      AVector[Val](Val.U64(U64.unsafe(55)))
    )

    test(
      s"""
         |TxContract Fibonacci() {
         |  fn f(n: I256) -> (I256) {
         |    if n < 2I {
         |      return n
         |    } else {
         |      return f(n-1I) + f(n-2I)
         |    }
         |  }
         |}
         |""".stripMargin,
      AVector(Val.I256(I256.from(10))),
      AVector[Val](Val.I256(I256.from(55)))
    )

    test(
      s"""
         |TxContract Fibonacci() {
         |  fn f(n: U256) -> (U256) {
         |    if n < 2U {
         |      return n
         |    } else {
         |      return f(n-1U) + f(n-2U)
         |    }
         |  }
         |}
         |""".stripMargin,
      AVector(Val.U256(U256.unsafe(10))),
      AVector[Val](Val.U256(U256.unsafe(55)))
    )

    test(
      s"""
         |TxContract Test() {
         |  fn main() -> (Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool, Bool) {
         |    let b0 = 1 == 1
         |    let b1 = 1 == 2
         |    let b2 = 1 != 2
         |    let b3 = 1 != 1
         |    let b4 = 1 < 2
         |    let b5 = 1 < 0
         |    let b6 = 1 <= 2
         |    let b7 = 1 <= 1
         |    let b8 = 1 > 0
         |    let b9 = 1 > 2
         |    let b10 = 1 >= 0
         |    let b11 = 1 >= 1
         |    return b0, b1, b2, b3, b4, b5, b6, b7, b8, b9, b10, b11
         |  }
         |}
         |""".stripMargin,
      AVector.empty,
      AVector[Val](Val.True,
                   Val.False,
                   Val.True,
                   Val.False,
                   Val.True,
                   Val.False,
                   Val.True,
                   Val.True,
                   Val.True,
                   Val.False,
                   Val.True,
                   Val.True)
    )

    test(
      s"""
         |TxContract Foo() {
         |  fn f(mut n: U64) -> (U64) {
         |    if n < 2 {
         |      n = n + 1
         |    }
         |    return n
         |  }
         |}
         |""".stripMargin,
      AVector(Val.U64(U64.unsafe(2))),
      AVector[Val](Val.U64(U64.unsafe(2)))
    )
  }

  it should "execute quasi uniswap" in new Fixture {
    val contract =
      s"""
         |TxContract Uniswap(
         |  mut alfReserve: U64,
         |  mut btcReserve: U64
         |) {
         |  fn exchange(alfAmount: U64) -> (U64) {
         |    let tokenAmount = u64!(u256!(btcReserve) * u256!(alfAmount) / u256!(alfReserve + alfAmount))
         |    alfReserve = alfReserve + alfAmount
         |    btcReserve = btcReserve - tokenAmount
         |    return tokenAmount
         |  }
         |}
         |""".stripMargin

    test(
      contract,
      AVector(Val.U64(U64.unsafe(1000))),
      AVector(Val.U64(U64.unsafe(99))),
      AVector(Val.U64(U64.unsafe(1000000)), Val.U64(U64.unsafe(100000)))
    )

    test(
      contract,
      AVector(Val.U64(U64.unsafe(1000))),
      AVector(Val.U64(U64.unsafe(99))),
      AVector(Val.U64(U64.MaxValue divUnsafe U64.unsafe(10)),
              Val.U64(U64.MaxValue divUnsafe U64.unsafe(100)))
    )
  }

  it should "test operator precedence" in new Fixture {
    val contract =
      s"""
         |TxContract Operator() {
         |  fn main() -> (U64, Bool, Bool) {
         |    let x = 1 + 2 * 3 - 2 / 2
         |    let y = 1 < 2 <= 2 < 3
         |    let z = !false && false || false
         |
         |    return x, y, z
         |  }
         |}
         |""".stripMargin
    test(contract, AVector.empty, AVector(Val.U64(U64.unsafe(6)), Val.True, Val.False))
  }
}
