package org.alephium.protocol.vm

import org.alephium.protocol.model.Hint
import org.alephium.serde.Serde
import org.alephium.util.AVector

final case class ContractState(hint: Hint, fields: AVector[Val])

object ContractState {
  implicit val serde: Serde[ContractState] =
    Serde.forProduct2(ContractState.apply, t => (t.hint, t.fields))
}
