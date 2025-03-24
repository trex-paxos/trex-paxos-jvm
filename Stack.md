# Stack Example Local

You must be running Java 23 or higher. Build the code with:

```bash
mvn -DNO_LOGGING=true clean test
```

Run jshell with:

```bash
jshell --enable-preview --class-path ./trex-lib/target/classes:./trex-lib/target/test-classes:./trex-paxe/target/classes:./trex-paxe/target/test-classes
```

To exit press Ctrl+d

Inside jshell import the code then set the logging to what you would like to see and create a two node cluster:

```
import com.github.trex_paxos.paxe.*
import com.github.trex_paxos.*
import com.github.trex_paxos.network.*

// Setup logging
StackServiceImpl.setLogLevel(java.util.logging.Level.INFO)
final var LOGGER = StackServiceImpl.LOGGER;

// This is a helper to setup a two node cluster using ephemeral ports
var harness = new NetworkTestHarness();

// Here we get the network objects for the two nodes along with the ephemeral port numbers
NetworkWithTempPort network1 = harness.createNetwork((short) 1);
NetworkWithTempPort network2 = harness.createNetwork((short) 2);

// This is a short pause to allow the sockets to bind
harness.waitForNetworkEstablishment();

// We need a supplier of the membership for the cluster for the networks to contract each other
Supplier<ClusterMembership> members = () -> new ClusterMembership(
    Map.of(new NodeId((short) 1), new NetworkAddress(network1.port()),
        new NodeId((short) 2), new NetworkAddress(network2.port())));
        
// Here we create the two nodes of the cluster with their network objects
var stackService1 = new StackServiceImpl((short)1, members, network1.network());
var stackService2 = new StackServiceImpl((short)2, members, network2.network());

// This is a short duration for awaiting on futures
Duration TEST_TIMEOUT = Duration.ofMillis(200);
```

You may need to hit enter to see the command prompt come back.

Now you can do push strings, peek and pop and get back a Response. Define some methods to print the result:

```
void push(String x, StackServiceImpl stackService) throws Exception {
    CompletableFuture<StackService.Response> future = new CompletableFuture<>();
    stackService.app().submitValue(new StackService.Push("first"), future);
    future.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
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
jshell> push("hello", stackService1)

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
