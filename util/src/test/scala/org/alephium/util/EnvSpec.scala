package org.alephium.util

class EnvSpec extends AlephiumSpec {
  behavior of "Env"

  it should "named correctly" in {
    Env.Prod.name is "prod"
    Env.Debug.name is "debug"
    Env.Test.name is "test"
    Env.Integration.name is "it"
  }

  it should "resolve correctly" in {
    Env.resolve("prod") is Env.Prod
    Env.resolve("debug") is Env.Debug
    Env.resolve("test") is Env.Test
    Env.resolve("it") is Env.Integration

    Env.resolve() is Env.Test
  }
}
