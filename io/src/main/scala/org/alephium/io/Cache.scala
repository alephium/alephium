package org.alephium.io

sealed trait Cache[V]

sealed trait ValueExisted[V] {
  def value: V
}

sealed trait KeyExistedInDB

final case class Cached[V](value: V) extends Cache[V] with KeyExistedInDB with ValueExisted[V]

sealed trait Modified[V]               extends Cache[V]
final case class Inserted[V](value: V) extends Modified[V] with ValueExisted[V]
final case class Removed[V]()          extends Modified[V] with KeyExistedInDB
final case class Updated[V](value: V)  extends Modified[V] with KeyExistedInDB with ValueExisted[V]
