# Alephium

## Installation

### Requierments

You must have the following dependencies installed on your system in order to run our JAR delivrable:

- java (>= 8, 11 is recommended)

### Running

You can obtain our latest single JAR distribution from the GitHub releases and start the application using the following command:

   java -jar alephium-<VERSION>.jar

## Build from source

### Requierments

In order to build the project from source the following dependencies must be installed on your system:
- java (>= 8, 11 is recommended)
- Python 3
- [SBT](https://docs.scala-lang.org/getting-started/sbt-track/getting-started-with-scala-and-sbt-on-the-command-line.html)

### Single JAR

In order to build a single runnable JAR use the following command:
  ./make assembly

The resulting assembly file will appear in `/app-server/target/scala-2.13/` directory.

### Univeral Zip distribution

In order to build a zip distribution including launch scripts use the following command:
  ./make package

The resulting package file will appear in the `app-server/target/scala-2.12/universal` directory.

## Configuration

You can define user specific settings in the file `$ALEPHIUM_HOME/user.conf`, where by default $ALEPHIUM_HOME points to `~/.alephium`.

## Testing

There are two kinds of tests: 

1) Unit and property tests, which can be run with the `./make test` command.
2) Integration tests, `./make ittest`.

