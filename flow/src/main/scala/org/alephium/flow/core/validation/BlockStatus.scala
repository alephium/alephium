package org.alephium.flow.core.validation

sealed trait BlockStatus
sealed trait InvalidBlockStatus extends BlockStatus
final case object ValidBlock    extends BlockStatus

sealed trait HeaderStatus
sealed trait InvalidHeaderStatus extends HeaderStatus with InvalidBlockStatus
final case object ValidHeader    extends HeaderStatus

//TBD: final case object InvalidBlockSize     extends InvalidBlockStatus
final case object InvalidGroup         extends InvalidBlockStatus
final case object InvalidTimeStamp     extends InvalidHeaderStatus
final case object InvalidWorkAmount    extends InvalidHeaderStatus
final case object InvalidWorkTarget    extends InvalidHeaderStatus
final case object MissingParent        extends InvalidHeaderStatus
final case object MissingDeps          extends InvalidHeaderStatus
final case object EmptyTransactionList extends InvalidBlockStatus
final case object InvalidCoinbase      extends InvalidBlockStatus
final case object InvalidMerkleRoot    extends InvalidBlockStatus

sealed trait InvalidTransactions extends InvalidBlockStatus
final case object DoubleSpent    extends InvalidTransactions
final case object InvalidCoins   extends InvalidTransactions
