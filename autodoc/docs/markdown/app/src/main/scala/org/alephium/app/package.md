[View code on GitHub](https://github.com/alephium/alephium/blob/master/app/src/main/scala/org/alephium/app/package.scala)

This file contains a package object for the Alephium project, which defines two utility functions and a type alias. 

The `FutureTry` type alias is defined as a shorthand for `Future[Try[T]]`, where `Try` is a type that represents the result of a computation that may either succeed with a value of type `T` or fail with an exception. This alias is likely used throughout the project to simplify the syntax of asynchronous code that returns a `Try` result.

The `wrapCompilerResult` function takes an `Either` value that represents the result of a compilation process, where the left side of the `Either` contains an error message and the right side contains the compiled value. If the `Either` contains an error message, the function maps it to a `badRequest` response from the Alephium API, which likely indicates that the compilation failed due to invalid input. Otherwise, the function returns the compiled value wrapped in a `Try`. This function is likely used to handle compilation errors in various parts of the project.

The `wrapError` function is similar to `wrapCompilerResult`, but it takes an `Either` value that represents any kind of error, where the left side contains an error message and the right side contains a successful result. If the `Either` contains an error message, the function maps it to a `badRequest` response from the Alephium API. Otherwise, the function returns the successful result wrapped in a `Try`. This function is likely used to handle errors in various parts of the project that are not related to compilation.

Overall, this package object provides some useful utility functions and a type alias that simplify error handling and asynchronous code in the Alephium project.
## Questions: 
 1. What is the purpose of this code file?
   This code file contains the license information and a package object for the `org.alephium.app` package.

2. What is the `FutureTry` type alias used for?
   The `FutureTry` type alias is used to represent a `Future` that will eventually contain a `Try` result.

3. What do the `wrapCompilerResult` and `wrapError` functions do?
   The `wrapCompilerResult` function takes an `Either` result from a `Compiler` and maps any errors to a `badRequest` response. The `wrapError` function takes an `Either` result from a computation and maps any errors to a `badRequest` response.