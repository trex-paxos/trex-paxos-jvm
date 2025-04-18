# Coding Standards #

The following coding styles must be applied to all code in this repository. In summary:

- Always use Records for data structures
- Prefer static methods with Records as parameters
- Use package-private scope by default
- Prefer package-by-feature rather than package-by-layer
- Only use public when compiler forces it for cross-package access
- Minimize the number of packages so that they are cohesive modules of functionality else protocols containing records 
- Always use `JEP 467` Markdown style for documentation in code
- Always use Data-Oriented Programming in Java and avoid use of Object-Oriented Programming except to provide a public API
- Always use Stream Operations instead of while, do-while or for-loops
- Always use switch expressions that structure records instead of if-else statements
- Always use sealed interfaces to avoid using a default case in switch expressions
- Always use anonymous variables in record distructuring and switch expressions to avoid unnecessary variable names
- Always use `final var` for local variables, method parameters and destructured record fields
- Use `JEP 371` “Local Classes and Interfaces” to keep logic within one Java file with a narrow public API for the file.

## Java Data-Oriented Programming (DOP)

Data-Oriented Programming in Java represents a paradigm shift focusing on separating data from behavior, contrasting with traditional object-oriented programming. Key aspects include:

1.	Immutable Data: Using records and immutable collections to prevent side effects
2.	Generic Data Structures: Favoring maps, lists, and sets over specialized classes
3.	Pure Functions: Operations that don’t modify state and produce consistent outputs
4.	Separated Behavior: Logic lives in utility classes rather than being coupled with data

Java 21+ features enabling DOP:
•	Records for immutable data representation
•	Pattern matching for structural decomposition
•	Sealed classes for exhaustive switch statements
•	Virtual threads for concurrent data processing

Benefits include simpler testing, better concurrency support, and more maintainable code through reduced coupling. This approach aligns with functional programming principles while leveraging Java’s type system.

Example:

```java
record Customer(String id, String name, Map<String, Object> attributes) {}

// Behavior in utility class
public class CustomerService {
  // ...
}
```

Note in that example the record is not public nor nested and can be in the same compile unit as the logic that uses it. 
This is the Java language feature is called  “Local Classes and Interfaces” introduced in Java 15 as part of `JEP 371`

Key points about this compact coding feature:

1. Multiple classes can be defined in a single source file. 
2. Only one class in the file can be declared as public. 
3. The public class must have the same name as the source file. 
4. Non-public classes are accessible only within the same package.

This feature compliments and enhances code organization and encapsulation, especially for smaller, related classes that don’t need to be exposed outside their package. This reduces the cognitive load on developers and allows for a more modular design and 
a flatter package structure. This then avoid spaghetti code and the need for excessive package namespacing. It then allows
for a very minimalistic public API to any package that makes it far easier to understand and use.

### Package-Level Encapsulation and Default Access

The principle of package-by-feature rather than package-by-layer, combined with effective use of Java’s default access modifier, leads to more maintainable code. The approach emphasizes:

* Using default (package-private) access as the preferred visibility level
* Limiting `public` only to APIs that genuinely need cross-package accessibility
* Leveraging package-private static methods for better testability and encapsulation
* Limiting `private` only to security related code as data oriented immutable coding does not need private fields or methods as a way to attempt to protect the integrity of data or class invariants.

This approach is part of what’s sometimes called Clean Architecture or Hexagonal Architecture when applied thoughtfully, rather than dogmatically.

This style rejects the anti-patterns of legacy Java programming that makes developers leave it to use other languages:

1. Boilerplate-heavy OOP - Excessive inheritance and scattered and hidden behaviours that need to be altered to introduce new features. 
2. Excessive layering - Artificial package boundaries that force everything to be public leading to spaghetti code and poor testability.
3. Dependency injection overuse - When it is used to solve problems created by poor package design and excessive layering.

## Records + Static Methods = Functional Style

The combination of:

* Java Records (introduced in Java 16)
* Static methods that operate on these records
* Package-level encapsulation

Creates a programming model similar to what is found in functional languages. This approach:

* Emphasizes immutability (records are immutable)
* Separates data (records) from behavior (static methods)
* Reduces side effects by making state transformations explicit
* Leads to fewer packages where the unit test code can be in the same package as the code being tested
* Improves testability through pure functions without complex mocking frameworks or boilerplate test configuration code

This pattern resembles Algebraic Data Types from functional languages, combined with Function Modules that operate on them. 
This far better aligning with best practices in modern software development:

1.	Separation of Concerns: ADTs separate data representation from behavior, allowing developers to focus on each aspect independently. This separation reduces cognitive load and makes the code easier to understand and maintain.
2.	Modularity: Function Modules operating on ADTs promote a modular design, enhancing code reusability and reducing duplication. This modularity leads to less overall code and easier maintenance.
3. Type Safety: ADTs provide strong typing, which helps catch errors at compile-time rather than runtime. This early detection of errors significantly reduces the number of bugs that make it to production.
4. Immutability: ADTs often encourage immutable data structures, which reduce side effects and make code behavior more predictable. This immutability contributes to fewer bugs related to unexpected state changes.
5. Exhaustiveness Checking: When combined with pattern matching, ADTs enable exhaustive checks on all possible cases, ensuring that no edge cases are overlooked. This comprehensive handling of cases leads to more robust and reliable code.
6. Extensibility: ADTs make it easy to add new functionality for existing types without modifying the original code. This extensibility allows for easier evolution of the codebase over time.

## Modern Java Stream-Based Programming

The functional programming style in modern Java that replaces traditional counting for-loops with functional constructs like `IntStream` is generally called Functional Programming or Stream-Oriented Programming. This represents a shift from imperative to declarative programming.

Why We Use Streams Instead of Counting Loops

1.	Declarative vs. Imperative: Streams express what you want to accomplish rather than how to do it
2.	Improved Readability: Code intent becomes clearer without loop mechanics
3.  Composition: Chain operations without intermediate variables
4.  Immutability Support: Encourages working with immutable data structures

Example Comparison

```
// Old imperative style
int sum = 0;
for (int i = 0; i < 100; i++) {
    if (i % 2 == 0) {
        sum += i;
    }
}

// Modern stream style
int sum = IntStream.range(0, 100)
                  .filter(i -> i % 2 == 0)
                  .sum();
```

## Legacy Java Style

The older programming constructs that have been largely replaced by streams are commonly referred to as:

* Imperative Programming Constructs
* Procedural Programming Patterns
* Mutable State Programming
* Control Flow Programming

More specifically, when discussing Java’s evolution, these older approaches are sometimes called:

* Pre-Java 8 Programming Idioms
* Traditional Loop Patterns
* Manual Iteration Constructs

This transition is part of Java’s broader evolution toward functional programming principles which leads to less 
complexity and more maintainable code.

## Documentation

IMPORTANT: You must not write JavaDoc comments that start with `/**` and end with `*/`
IMPORTANT: You must "JEP 467: Markdown Documentation Comments" that start all lines with `///`

Here is an example of the correct format for documentation comments:

```java
/// Returns a hash code value for the object. This method is
/// supported for the benefit of hash tables such as those provided by
/// [java.util.HashMap].
///
/// The general contract of `hashCode` is:
///
///   - Whenever it is invoked on the same object more than once during
///     an execution of a Java application, the `hashCode` method
///   - If two objects are equal according to the
///     [equals][#equals(Object)] method, then calling the
///   - It is _not_ required that if two objects are unequal
///     according to the [equals][#equals(Object)] method, then
///
/// @return a hash code value for this object.
/// @see     java.lang.Object#equals(java.lang.Object)
```

## JEP References

[JEP 467](https://openjdk.org/jeps/467): Markdown Documentation in JavaDoc
[JEP 371](https://openjdk.org/jeps/371): Local Classes and Interfaces
[JEP 395](https://openjdk.org/jeps/395): Records
[JEP 409](https://openjdk.org/jeps/409): Sealed Classes
[JEP 440](https://openjdk.org/jeps/440): Record Patterns
[JEP 427](https://openjdk.org/jeps/427): Pattern Matching for Switch

End. 
