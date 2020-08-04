package org.alephium.protocol.vm

import org.alephium.serde._
import org.alephium.util._

class VMSpec extends AlephiumSpec with ContextGenerators {
  it should "execute the following script" in {
    val method =
      Method[StatefulContext](
        localsType = AVector(Val.U64),
        returnType = AVector(Val.U64),
        instrs     = AVector(LoadLocal(0), LoadField(1), U64Add, U64Const5, U64Add, Return))
    val contract = StatefulContract(AVector(Val.U64, Val.U64), methods = AVector(method))
    val (obj, context) =
      prepareContract(contract, AVector[Val](Val.U64(U64.Zero), Val.U64(U64.One)))
    StatefulVM.execute(context, obj, 0, AVector(Val.U64(U64.Two))) isE AVector[Val](
      Val.U64(U64.unsafe(8)))
  }

  it should "call method" in {
    val method0 = Method[StatelessContext](localsType = AVector(Val.U64),
                                           returnType = AVector(Val.U64),
                                           instrs     = AVector(LoadLocal(0), CallLocal(1), Return))
    val method1 =
      Method[StatelessContext](localsType = AVector(Val.U64),
                               returnType = AVector(Val.U64),
                               instrs     = AVector(LoadLocal(0), U64Const1, U64Add, Return))
    val script = StatelessScript(methods = AVector(method0, method1))
    val obj    = script.toObject
    StatelessVM.execute(statelessContext, obj, 0, AVector(Val.U64(U64.Two))) isE
      AVector[Val](Val.U64(U64.unsafe(3)))
  }

  it should "serde instructions" in {
    Instr.statelessInstrs.foreach {
      case instrCompanion: InstrCompanion0 =>
        deserialize[Instr[StatelessContext]](instrCompanion.serialize()).toOption.get is instrCompanion
      case _ => ()
    }
  }

  it should "serde script" in {
    val method =
      Method[StatefulContext](
        localsType = AVector(Val.U64),
        returnType = AVector.empty,
        instrs     = AVector(LoadLocal(0), LoadField(1), U64Add, U64Const1, U64Add, StoreField(1)))
    val contract = StatefulContract(AVector(Val.U64, Val.U64), methods = AVector(method))
    serialize(contract)(StatefulContract.serde).nonEmpty is true
  }
}
