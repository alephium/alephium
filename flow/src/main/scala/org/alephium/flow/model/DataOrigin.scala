package org.alephium.flow.model

import org.alephium.protocol.model.{BrokerInfo, CliqueId}

sealed trait DataOrigin {
  def isFrom(another: CliqueId): Boolean

  def isFrom(cliqueId: CliqueId, brokerInfo: BrokerInfo): Boolean

  def isSyncing: Boolean
}

object DataOrigin {
  case object Local extends DataOrigin {
    override def isFrom(another: CliqueId): Boolean = false

    override def isFrom(cliqueId: CliqueId, brokerInfo: BrokerInfo): Boolean = false

    override def isSyncing: Boolean = false
  }

  trait FromClique extends DataOrigin {
    def cliqueId: CliqueId
    def brokerInfo: BrokerInfo

    override def isFrom(another: CliqueId): Boolean = cliqueId == another

    override def isFrom(_cliqueId: CliqueId, _brokerInfo: BrokerInfo): Boolean =
      cliqueId == _cliqueId && _brokerInfo == brokerInfo
  }
  case class InterClique(cliqueId: CliqueId, brokerInfo: BrokerInfo, isSyncing: Boolean)
      extends FromClique
  case class IntraClique(cliqueId: CliqueId, brokerInfo: BrokerInfo) extends FromClique {
    override def isSyncing: Boolean = false
  }
}
