gear-sdk
===============

Usage:

```
String host = "127.0.0.1";
int port = 5672;
String userName = "guest";
String password = "guest";
String service = "GEAR";
final MQClient client = MQClient.getInstance();
client.init(host, port, userName, password, service);

// p2p
client.sendMessage("GEAR", "msg");

// multicast
client.sendMessageToAll("GEAR", "msg");

// broadcast
client.sendMessageToAll("GEAR");

// consume
new Thread(new Runnable() {
    @Override
    public void run() {
        client.consumeMessage(new MessageHandler() {
            @Override
            public boolean handle(Message message) {
                // message handler.
                    System.out.println(message);
                    return true;
                }
            });
        }
    }).start();
}
```

