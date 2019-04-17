package org.alephium.flow.model

import org.alephium.crypto.Keccak256
import org.alephium.util.AVector

sealed trait ValidationError extends Error

object ValidationError {
  case object InvalidGroup extends ValidationError {
    override def toString: String = "Invalid group, current group or external group expected"
  }
  case object InvalidDifficulty extends ValidationError {
    override def toString: String = "Difficulty is invalid"
  }
  case class MissingDeps(deps: AVector[Keccak256]) extends ValidationError {
    override def toString: String = s"Missing #$deps.length deps"
  }
}
