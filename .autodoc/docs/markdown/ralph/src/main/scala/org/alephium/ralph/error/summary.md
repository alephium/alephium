[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/ralph/src/main/scala/org/alephium/ralph/error)

The code in this folder is related to handling and formatting compiler errors for the Alephium project. It provides a set of error messages that can be used to provide feedback to users when there is an issue with their code during compilation. These error messages can be used to help users identify and fix issues in their code more easily.

`CompilerError.scala` defines a set of error messages that can be produced by the compiler. It includes a base trait `CompilerError` and several case classes representing specific types of errors, such as `FastParseError`, `Expected an I256 value`, and `Invalid byteVec`. These error messages can be used to provide feedback to users when there is an issue with their code during compilation.

`CompilerErrorFormatter.scala` defines a class that builds a formatted error message. The class takes in several parameters such as `errorTitle`, `errorLine`, `foundLength`, `errorMessage`, `errorFooter`, and `sourcePosition`. These parameters are used to format the error message. The `format` method formats the error message by adding color to the error message and building the error body and footer. The `getErroredLine` method fetches the line where the error occurred. The `highlight` method wraps the input string to be colored.

Example usage of `CompilerErrorFormatter`:

```scala
val error = CompilerErrorFormatter(
  errorTitle = "Syntax error",
  errorLine = "val x = 1 +",
  foundLength = 1,
  errorMessage = "missing operand",
  errorFooter = Some("Make sure to add an operand to the expression."),
  sourcePosition = SourcePosition(1, 8)
)

println(error.format(Some(Console.RED)))
```

`FastParseErrorUtil.scala` provides a set of functions to handle errors that occur during parsing of Alephium code. The `apply` function takes a `Parsed.TracedFailure` object as input and returns a `CompilerError.FastParseError` object. The `getLatestErrorMessage` function takes a `Parsed.TracedFailure` object and an integer index as input and returns a string that represents the most recent error message for the given index.

Example usage of `FastParseErrorUtil`:

```scala
import org.alephium.ralph.error.FastParseErrorUtil
import fastparse.Parsed

val input = "1 + 2 * 3"
val result = fastparse.parse(input, Parser.expr(_))

result match {
  case Parsed.Success(value, _) => println(value)
  case Parsed.Failure(traced) => {
    val error = FastParseErrorUtil(traced)
    println(error.message)
  }
}
```

`FastParseExtension.scala` provides an object called `FastParseExtension` that extends the functionality of the `fastparse` library. The `LastIndex` method takes a `parser` and a `ctx` as input parameters and returns the tail/last index after the parser run.

Example usage of `LastIndex` method:

```scala
import fastparse._
import org.alephium.ralph.error.FastParseExtension._

val parser = P("hello" ~ "world").rep(1)
val input = "hello world hello world"

val result = parser.parse(input)
val lastIndex = LastIndex(parser)(result)

println(lastIndex) // Output: 23
```

Overall, the code in this folder is essential for providing clear and concise error messages to users when there is an error in their code during compilation. The formatted error messages can be used to debug the code and fix the error.
