[View code on GitHub](https://github.com/alephium/alephium/util/src/main/scala/org/alephium/util/Base58.scala)

The `Base58` object provides functionality for encoding and decoding data using the Base58 encoding scheme. This encoding scheme is commonly used in Bitcoin and other cryptocurrencies to represent data in a compact and human-readable format.

The `Base58` object defines two methods: `encode` and `decode`. The `encode` method takes a `ByteString` as input and returns a Base58-encoded string. The `decode` method takes a Base58-encoded string as input and returns an optional `ByteString`. If the input string is not a valid Base58-encoded string, `None` is returned.

The `Base58` object defines a `alphabet` string that contains the characters used in the Base58 encoding scheme. The `toBase58` array is used to convert a Base58 character to its corresponding integer value. The `toBase58` method is used to convert a character to its integer value. If the character is not a valid Base58 character, `-1` is returned.

The `encode` method first checks if the input `ByteString` is empty. If it is, an empty string is returned. Otherwise, the method calculates the number of leading zeros in the input `ByteString` and creates a prefix string consisting of that many `alphabet(0)` characters. The method then converts the input `ByteString` to a `BigInt` and iteratively divides it by 58, appending the corresponding Base58 character to a `StringBuilder` until the `BigInt` value is zero. The resulting `StringBuilder` is then converted to a string and the prefix is prepended to the string.

The `decode` method first calculates the number of leading ones in the input string and creates a `ByteString` consisting of that many zeros. The method then iterates over the input string, converting each character to its integer value using the `toBase58` method. If the character is not a valid Base58 character, `None` is returned. Otherwise, the integer value is multiplied by the appropriate power of 58 and added to a `BigInt`. The resulting `BigInt` is then converted to a `ByteString` by first converting it to an array of bytes and dropping any leading zeros.

Overall, the `Base58` object provides a simple and efficient implementation of the Base58 encoding scheme that can be used in a variety of contexts, such as encoding and decoding cryptocurrency addresses and transaction data. Here is an example usage of the `Base58` object:

```scala
import org.alephium.util.Base58
import akka.util.ByteString

val data = ByteString("Hello, world!")
val encoded = Base58.encode(data)
val decoded = Base58.decode(encoded)

assert(decoded.contains(data))
```
## Questions: 
 1. What is the purpose of the `Base58` object?
    
    The `Base58` object provides methods for encoding and decoding data using the Base58 encoding scheme.

2. What is the significance of the `alphabet` string?

    The `alphabet` string defines the set of characters used in the Base58 encoding scheme.

3. What is the purpose of the `toBase58` array?

    The `toBase58` array maps ASCII values to their corresponding Base58 values, with -1 indicating an invalid character.