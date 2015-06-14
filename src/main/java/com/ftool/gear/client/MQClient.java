package com.ftool.gear.client;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import com.alibaba.fastjson.JSON;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.QueueingConsumer;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;

public class MQClient {
    private static Logger logger = Logger.getLogger(MQClient.class);

    private static int DEFAULT_PORT = 5672;

    private static int DEFAULT_WAIT_TIMEOUT = 30000;

    private static String DEFAULT_EXCHANGE = "x";

    private static String TAG_PUBLISH = "publish_tag";
    private static String TAG_CONSUME = "consume_tag";

    private static MQClient client;

    private Connection connection = null;

    private Map<String, Channel> chanMap = new ConcurrentHashMap<String, Channel>();

    private String brokeHost;

    private int brokePort;

    private String userName;

    private String password;

    private String service;

    private MQClient() {
    }

    public static MQClient getInstance() {
        synchronized (MQClient.class) {
            if (client == null) {
                client = new MQClient();
            }
        }
        return client;
    }

    private Connection obtainConnection() {
        if (connection != null) {
            return connection;
        }
        chanMap.clear();
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(brokeHost);
        factory.setPort(brokePort);
        factory.setUsername(userName);
        factory.setPassword(password);
        while (true) {
            try {
                connection = factory.newConnection();
                connection.addShutdownListener(new ShutdownListener() {
                    @Override
                    public void shutdownCompleted(ShutdownSignalException cause) {
                        connection = null;
                        chanMap.clear();
                    }
                });
                return connection;
            } catch (Exception e) {
                logger.warn("create connection error! " + e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignore) {
                }
            }
        }
    }

    public synchronized Channel obtainChannel(String tag) {
        while (true) {
            try {
                connection = obtainConnection();
                if (chanMap.get(tag) != null) {
                    return chanMap.get(tag);
                }
                Channel c = connection.createChannel();
                chanMap.put(tag, c);
                return c;
            } catch (Exception e) {
                logger.warn("create channel error! " + e.getMessage());
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception ignore) {
                    }
                }
                connection = null;
                chanMap.clear();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignore) {
                }
            }
        }
    }

    public void init(String brokeHost, int brokePort, String userName, String password, String service) {
        this.brokeHost = brokeHost;
        this.brokePort = brokePort;
        this.userName = userName;
        this.password = password;
        this.service = service;
        initBasicInfras(); // 基础队列组件创建。
    }

    /**
     * p2p, to=service表示发送给服务的任意一个节点。 to=service.hostname表示发送给服务的指定节点。
     * 
     * @param to
     * @param msg
     * @return
     */
    public boolean sendMessage(String to, String body) {
        return sendMessage(to, body, false);
    }

    /**
     * multicast
     * 
     * @param to
     * @param body
     * @return
     */
    public boolean sendMessageToAll(String to, String body) {
        return sendMessage(to, body, true);
    }

    /**
     * broadcast
     * 
     * @param body
     * @return
     */
    public boolean sendMessageToAll(String body) {
        return sendMessage("*", body, true);
    }

    private boolean sendMessage(String to, String body, boolean toAll) {
        String tag = TAG_PUBLISH;
        try {
            Channel c = obtainChannel(tag);
            c.confirmSelect();
            String from = this.service + "." + getHostname();
            Message msg = new Message(to, from, body);
            String m = JSON.toJSONString(msg);
            logger.info("send message:" + m);
            String routerKey = to;
            if (toAll) {
                routerKey = to + ".#";
            }
            c.basicPublish(DEFAULT_EXCHANGE, routerKey, null, m.getBytes());
            c.waitForConfirms(DEFAULT_WAIT_TIMEOUT);
            return true;
        } catch (AlreadyClosedException ace) {
            if (ace.isHardError()) { // connection error.
                connection = null;
                chanMap.clear();
            } else { // channel error.
                chanMap.remove(tag);
            }
        } catch (Exception e) {
            logger.warn("sendmessage error!" + e.getMessage());
        }
        return false;
    }

    public void consumeMessage(MessageHandler handler) {
        String tag = TAG_CONSUME;
        try {
            Channel c = obtainChannel(tag);
            LinkedBlockingQueue<QueueingConsumer.Delivery> blockingQueue = new LinkedBlockingQueue<QueueingConsumer.Delivery>();
            QueueingConsumer consumer = new QueueingConsumer(c, blockingQueue);
            c.basicConsume(this.service, false, "consume1", consumer);
            c.basicConsume(this.service + "." + getHostname(), false, "consume2", consumer);

            while (true) {
                QueueingConsumer.Delivery delivery = consumer.nextDelivery(DEFAULT_WAIT_TIMEOUT);
                if (delivery != null) {
                    long deliveryTag = delivery.getEnvelope().getDeliveryTag();
                    String m = new String(delivery.getBody());
                    try {
                        Message msg = JSON.parseObject(m, Message.class);
                        boolean result = handler.handle(msg);
                        logger.info("consume message:" + m + ", handle resule:" + result);
                        if (result) {
                            c.basicAck(deliveryTag, false);
                        } else {
                            c.basicNack(deliveryTag, false, true);
                        }
                    } catch (Exception e) {
                        logger.warn("msg handle error. " + m);
                        c.basicAck(deliveryTag, false);
                        continue;
                    }

                }
            }
        } catch (AlreadyClosedException ace) {
            if (ace.isHardError()) { // connection error.
                connection = null;
                chanMap.clear();
            } else { // channel error.
                chanMap.remove(tag);
            }
        } catch (Exception e) {
            logger.warn("consume message error!" + e.getMessage());
        }
        consumeMessage(handler);
    }

    private void initBasicInfras() {
        try {
            // init exchange
            Channel c = obtainChannel(TAG_PUBLISH);
            c.exchangeDeclare(DEFAULT_EXCHANGE, "topic", true);

            // init queues & binding
            c.queueDeclare(this.service, true, false, false, null);
            c.queueBind(this.service, DEFAULT_EXCHANGE, this.service);

            String hostname = getHostname();
            c.queueDeclare(this.service + "." + hostname, true, false, false, null);
            c.queueBind(this.service + "." + hostname, DEFAULT_EXCHANGE, this.service + "." + hostname);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getHostname() {
        String hostName = null;
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            logger.debug("cat not get local hostname, try to read /etc/hostname");
            try {
                hostName = FileUtils.readFileToString(new File("/etc/hostname"));
            } catch (Exception error) {
                logger.error("Can not get Local Host Name," + error.getMessage(), error);
            }
        }
        if (StringUtils.isBlank(hostName)) {
            hostName = "unknown";
        }
        return hostName;
    }

    public static void main(String[] args) {
    }
}
