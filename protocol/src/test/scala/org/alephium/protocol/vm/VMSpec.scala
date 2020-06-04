package org.alephium.protocol.vm

import org.alephium.serde._
import org.alephium.util._

class VMSpec extends AlephiumSpec {
  it should "execute the following script" in {
    val method =
      Method[StatelessContext](
        localsType = AVector(Val.U64),
        instrs     = AVector(LoadLocal(0), LoadField(1), U64Add, U64Const1, U64Add, StoreField(1)))
    val script = StatelessScript(AVector(Val.U64, Val.U64), methods = AVector(method))
    StatelessVM.execute(script).isRight is true
  }

  it should "serde instructions" in {
    Instr.statelessInstrs.foreach {
      case instrCompanion: SimpleInstr =>
        deserialize[Instr[StatelessContext]](instrCompanion.serialize()).toOption.get is instrCompanion
      case instrCompanion: InstrCompanion1 =>
        val instr0 = instrCompanion.apply(0)
        deserialize[Instr[StatelessContext]](instr0.serialize()).toOption.get is instr0
        val instr1 = instrCompanion.apply(0xFF)
        deserialize[Instr[StatelessContext]](instr1.serialize()).toOption.get is instr1
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
