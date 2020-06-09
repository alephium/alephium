//package org.alephium.protocol.vm.lang
//
//import org.alephium.util.AlephiumSpec
//
//class OldParserSpec extends AlephiumSpec {
//  it should "parse tokens" in {
//    fastparse.parse("U64", OldParser.tpe(_)).isSuccess is true
//    fastparse.parse("U64", OldParser.returnTypes(_)).isSuccess is true
//    fastparse.parse("(U64, U64)", OldParser.returnTypes(_)).isSuccess is true
//    fastparse.parse("#x: U64", OldParser.field(_)).isSuccess is true
//    fastparse.parse("def (U64): U64", OldParser.statelessMethod(_)).isSuccess is false
//    fastparse.parse("def (): U64\nU64Add\n", OldParser.statelessMethod(_)).isSuccess is true
//    fastparse.parse("def (U64): U64\nU64Add\n", OldParser.statelessMethod(_)).isSuccess is true
//  }
//
//  it should "parse script" in {
//    val script =
//      """
//        |#x : U64
//        |#y : U64
//        |
//        |#z : U256
//        |
//        |def ():
//        |  LoadField #x
//        |  U64Const1
//        |  U64Add
//        |  StoreField #x
//        |
//        |def (U64) : U64
//        |
//        |  LoadField #x
//        |  LoadField #y
//        |
//        |  U64Add
//        |
//        |def (U32, U32) : (U32, U32)
//        |
//        |  LoadLocal 0
//        |  LoadLocal 1
//        |  Swap
//        |""".stripMargin
//    val parsed = OldParser.parseStateless(script).get.value
//    parsed.fields.length is 3
//    parsed.methods.length is 3
//  }
//}
