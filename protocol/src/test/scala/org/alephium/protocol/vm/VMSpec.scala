package org.alephium.protocol.vm

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
}
