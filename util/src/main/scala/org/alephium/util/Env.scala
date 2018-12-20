package org.alephium.util

sealed trait Env {
  def name: String = toString.toLowerCase
}

object Env {
  case object Prod  extends Env
  case object Debug extends Env
  case object Test  extends Env

  def resolve(): Env = {
    val env = System.getenv("ALEPHIUM_ENV")
    env match {
      case "prod"  => Prod
      case "debug" => Debug
      case "test"  => Test
    }
  }
}
