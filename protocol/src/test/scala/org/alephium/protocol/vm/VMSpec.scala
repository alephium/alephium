package org.alephium.protocol.vm

import org.alephium.serde._
import org.alephium.util._

class VMSpec extends AlephiumSpec {
  it should "execute the following script" in {
    val method =
      Method[StatelessContext](
        localsType = AVector(Val.U64),
        instrs     = AVector(LoadLocal(0), LoadField(1), U64Add, U64Const5, U64Add, U64Return))
    val script = StatelessScript(AVector(Val.U64, Val.U64), methods = AVector(method))
    StatelessVM.execute(StatelessContext.test,
                        script,
                        AVector(Val.U64(U64.Zero), Val.U64(U64.One)),
                        AVector(Val.U64(U64.Two))) isE Seq(Val.U64(U64.unsafe(8)))
  }

  it should "call method" in {
    val method0 = Method[StatelessContext](localsType = AVector(Val.U64),
                                           instrs = AVector(LoadLocal(0), CallLocal(1), U64Return))
    val method1 = Method[StatelessContext](localsType = AVector(Val.U64),
                                           instrs =
                                             AVector(LoadLocal(0), U64Const1, U64Add, U64Return))
    val script = StatelessScript(AVector.empty, methods = AVector(method0, method1))
    StatelessVM.execute(StatelessContext.test, script, AVector.empty, AVector(Val.U64(U64.Two))) isE
      Seq(Val.U64(U64.unsafe(3)))
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
      Method[StatelessContext](
        localsType = AVector(Val.U64),
        instrs     = AVector(LoadLocal(0), LoadField(1), U64Add, U64Const1, U64Add, StoreField(1)))
    val script = StatelessScript(AVector(Val.U64, Val.U64), methods = AVector(method))
    serialize(script)(StatelessScript.serde).nonEmpty is true
  }
}
