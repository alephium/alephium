package org.alephium.flow.core.validation

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.platform.PlatformConfig
import org.alephium.io.IOResult
import org.alephium.protocol.model.Transaction

// Note: only non-coinbase transations are validated here
object TxValidation {
  import ValidationStatus.convert

  def validateNonCoinbase(tx: Transaction, flow: BlockFlow)(
      implicit config: PlatformConfig): IOResult[TxStatus] = {
    convert(Validation.validateNonCoinbaseTx(tx, flow), ValidTx)
  }
}
