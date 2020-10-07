package org.alephium.util

sealed trait Env {
  def name: String
}

object Env {
  case object Prod        extends Env { override def name: String = "prod" }
  case object Debug       extends Env { override def name: String = "debug" }
  case object Test        extends Env { override def name: String = "test" }
  case object Integration extends Env { override def name: String = "it" }

  def resolve(): Env =
    resolve(sys.env.getOrElse("ALEPHIUM_ENV", "prod"))

  def resolve(env: String): Env = {
    env match {
      case "prod"  => Prod
      case "debug" => Debug
      case "test"  => Test
      case "it"    => Integration
    }
  }
}
