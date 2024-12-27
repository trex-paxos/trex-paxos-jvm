# Stack Example Local

You must be running Java 23 or higher. Build the code with: 

```bash
pushd trex2-lib 
mvn -DNO_LOGGING=true clean test
popd
```

Run jshell with: 

```bash
jshell --enable-preview --class-path ./trex-locks/target/classes:./trex-locks/target/test-classes:./trex2-lib/target/classes:./trex2-lib/target/test-classes
```

To exist press Ctrl+d

Inside jshell import the coee then set the logging to what you would like to see and create a two node cluster: 

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
Dec 27, 2024 3:28:17 PM com.github.trex_paxos.StackClusterImpl lambda$new$4
WARNING: Attempted operation on empty stack
Stack is empty
```