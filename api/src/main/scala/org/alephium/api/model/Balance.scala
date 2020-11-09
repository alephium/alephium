package org.alephium.api.model

import org.alephium.util.U256

final case class Balance(balance: U256, utxoNum: Int)

  object Balance {
    def apply(balance_utxoNum: (U256, Int)): Balance = {
      Balance(balance_utxoNum._1, balance_utxoNum._2)
    }
  }
