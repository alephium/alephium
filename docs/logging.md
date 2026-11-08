# Log output style

The node and standalone wallet use colored logs by default. Set
`ALEPHIUM_LOG_STYLE=plain` before starting either application to remove the ANSI
color codes added by the bundled Logback patterns, from console and file logs:

```sh
ALEPHIUM_LOG_STYLE=plain java -jar alephium.jar
```

Use the actual release jar filename in place of `alephium.jar`. For Docker,
pass `-e ALEPHIUM_LOG_STYLE=plain` or set the same variable under `environment`
in Compose. A JVM property (`-DALEPHIUM_LOG_STYLE=plain`, before `-jar`) also
works and takes precedence over the environment variable. Configuration is
selected at startup; restart after changing it.

`plain` is case-sensitive. Unset, `color`, empty, and unrecognized values retain
the existing color pattern. Both patterns retain timestamp, thread, severity,
logger, message and exception output. This setting changes formatting only;
levels, destinations, filtering and rotation are unchanged. It does not strip
escape characters already present in a message supplied by application code.

The configuration uses Logback's built-in property condition (available in the
already pinned Logback version), with no Janino or additional dependency.
For an entirely custom pattern/configuration, Logback already supports
`-Dlogback.configurationFile=/path/to/logback.xml`. A custom configuration must
implement its own style selection. This setting does not change sbt output.
