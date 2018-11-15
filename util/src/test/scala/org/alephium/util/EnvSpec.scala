package org.alephium.util

class EnvSpec extends AlephiumSpec {
  behavior of "Env"

  it should "named correctly" in {
    Env.Prod.name is "prod"
    Env.Debug.name is "debug"
    Env.Test.name is "test"
  }
}
