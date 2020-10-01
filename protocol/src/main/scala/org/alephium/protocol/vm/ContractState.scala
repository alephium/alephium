package org.alephium.protocol.vm

import org.alephium.protocol.model.ContractOutputRef
import org.alephium.serde.Serde
import org.alephium.util.AVector

final case class ContractState(code: StatefulContract,
                               fields: AVector[Val],
                               contractOutputRef: ContractOutputRef)

object ContractState {
  implicit val serde: Serde[ContractState] =
    Serde.forProduct3(ContractState.apply, t => (t.code, t.fields, t.contractOutputRef))

  val forMPt: ContractState =
    ContractState(StatefulContract.forMPT, AVector.empty, ContractOutputRef.forMPT)
}
