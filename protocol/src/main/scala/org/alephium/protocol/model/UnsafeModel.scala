package org.alephium.protocol.model

import org.alephium.protocol.config.GroupConfig

trait UnsafeModel[T] {
  def validate(implicit config: GroupConfig): Either[String, T]
}
