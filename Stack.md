# Stack Example Local

You must be running Java 23 or higher. Build the code with:

```bash
mvn -DNO_LOGGING=true clean test
```

Run jshell with:

```bash
jshell --enable-preview --class-path ./trex-lib/target/classes:./trex-lib/target/test-classes
```

To exist press Ctrl+d

Inside jshell import the code then set the logging to what you would like to see and create a two node cluster:

```
jshell> import com.github.trex_paxos.*

jshell> StackClusterImpl.setLogLevel(java.util.logging.Level.WARNING)

jshell> var stack = new StackClusterImpl()
```

You may need to hit enter to see the command prompt come back.

Now you can do push strings, peek and pop and get back a Response. Define some methods to print the result:

```
void push(String x){
    stack.push(x);
}
void peek(){
    System.out.println(stack.peek().value().get());
}
void pop(){
    System.out.println(stack.pop().value().get());
}
```

With that you can:

```
jshell> push("hello")

jshell> push("world")

jshell> peek();
world

jshell> peek();
world

jshell> pop()
world

jshell> pop()
hello

jshell> pop()
Dec 27, 2024 3:28:17 PM com.github.trex_paxos.StackServiceImpl lambda$new$4
WARNING: Attempted operation on empty stack
Stack is empty
```

You can paste all of that as:

```java
// @formatter:off
import com.github.trex_paxos.*;
StackClusterImpl.setLogLevel(java.util.logging.Level.WARNING);
var stack = new StackClusterImpl();
void push(String x){ stack.push(x); }
void peek(){ System.out.println(stack.peek().value().get()); }
void pop(){ System.out.println(stack.pop().value().get()); }
// then do
push("hello");
push("world");
peek();
peek();
pop();
pop();
// @formatter:on
```
