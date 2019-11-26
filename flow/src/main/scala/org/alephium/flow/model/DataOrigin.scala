package org.alephium.flow.model

import org.alephium.protocol.model.{BrokerInfo, CliqueId}

sealed trait DataOrigin {
  def isFrom(another: CliqueId): Boolean

  def isFrom(cliqueId: CliqueId, brokerInfo: BrokerInfo): Boolean

  def isSyncing: Boolean
}

object DataOrigin {
  case object LocalMining extends DataOrigin {
    override def isFrom(another: CliqueId): Boolean = false

    override def isFrom(cliqueId: CliqueId, brokerInfo: BrokerInfo): Boolean = false

    override def isSyncing: Boolean = false
  }
  case class Remote(cliqueId: CliqueId, brokerInfo: BrokerInfo, isSyncing: Boolean)
      extends DataOrigin {
    override def isFrom(another: CliqueId): Boolean = cliqueId == another

    override def isFrom(_cliqueId: CliqueId, _brokerInfo: BrokerInfo): Boolean =
      cliqueId == _cliqueId && _brokerInfo == brokerInfo
  }
}
