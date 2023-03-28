[View code on GitHub](https://github.com/alephium/alephium/blob/master/util/src/main/scala/org/alephium/util/TimeStamp.scala)

The `TimeStamp` class and its companion object in the `org.alephium.util` package provide functionality for working with timestamps. The class represents a timestamp as a number of milliseconds since the Unix epoch (January 1, 1970, 00:00:00 UTC). The class is defined as a value class, which means that it is optimized for performance and memory usage.

The `TimeStamp` class provides methods for adding and subtracting durations of time to and from a timestamp. These methods return `Option[TimeStamp]` values, which are used to handle cases where the result of the operation would be negative or otherwise invalid. For example, the `plusMillis` method adds a specified number of milliseconds to a timestamp and returns an `Option[TimeStamp]` value that is `Some` if the result is valid and `None` otherwise.

The `TimeStamp` class also provides methods for comparing timestamps and calculating the difference between two timestamps as a duration of time. These methods return `Boolean` and `Option[Duration]` values, respectively.

The companion object provides factory methods for creating `TimeStamp` instances from a number of milliseconds and from the current system time. It also provides a `zero` timestamp and a `Max` timestamp that represent the minimum and maximum possible timestamps, respectively.

Overall, the `TimeStamp` class and its companion object provide a convenient and efficient way to work with timestamps in the Alephium project. Here is an example of how to use the `TimeStamp` class to add a duration of time to a timestamp:

```scala
val timestamp = TimeStamp.now()
val duration = Duration.fromSeconds(30).get
val newTimestamp = timestamp.plus(duration).getOrElse(timestamp)
```
## Questions: 
 1. What is the purpose of the `TimeStamp` class and what operations can be performed on it?
- The `TimeStamp` class represents a point in time and provides methods to add or subtract time durations in milliseconds, seconds, minutes, and hours. It also allows comparison with other `TimeStamp` instances and conversion to a string representation.

2. What is the difference between the `plus` and `plusUnsafe` methods?
- The `plus` method returns an `Option[TimeStamp]` that may be empty if the resulting timestamp would be negative, while `plusUnsafe` returns a non-empty `TimeStamp` instance regardless of the result.

3. What is the purpose of the `unsafe` and `from` methods in the `TimeStamp` companion object?
- The `unsafe` method creates a new `TimeStamp` instance from a given number of milliseconds, assuming that it is non-negative. The `from` method returns an `Option[TimeStamp]` that may be empty if the input is negative, providing a safer alternative to `unsafe`.