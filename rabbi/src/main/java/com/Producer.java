
package com;

import com.rabbitmq.client.*;
import proto.Messages.Historical;
import proto.Messages.HistoricalValue;
import proto.Messages.Universal;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Producer class responsible for initializing and sending messages
public class Producer {

    public static void main(String[] args) {
        Producer producer = new Producer();
        producer.startProducing();
    }


    
    private static final Logger logger = LoggerFactory.getLogger(Producer.class);
    static final String QUEUE_NAME = "hello";
    private static final ConnectionFactory factory = new ConnectionFactory();
    static {
        factory.setHost("localhost");
    }

   

    // Method to start the message production process
    private void startProducing() {
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            channel.queueDeclare(QUEUE_NAME, true, false, false, null);
            logger.info("Producer started. Sending messages...");

            MessagePublisher publisher = new MessagePublisher(channel);
            MessageSender sender = new MessageSender(publisher);

            sender.sendMessagesContinuously();
        } catch (Exception e) {
            logger.error("Failed to establish RabbitMQ connection or channel", e);
        }
    }
}

// MessageSender class responsible for sending messages at a fixed interval
class MessageSender {
    private final MessagePublisher publisher;
    private long messageCounter = 0;

    public MessageSender(MessagePublisher publisher) {
        this.publisher = publisher;
    }

    // Method to continuously send messages with a delay
    public void sendMessagesContinuously() {
        while (true) {
            try {
                Universal universalMessage = UniversalMessageFactory.createUniversalMessage();
                sendMessagesInUniversalMessage(universalMessage);
            } catch (Exception e) {
                Logger logger = LoggerFactory.getLogger(MessageSender.class);
                logger.error("Error while sending message", e);
            }
        }
    }

    // Method to send each Historical message within a Universal message
    private void sendMessagesInUniversalMessage(Universal universalMessage) throws InterruptedException, Exception {
        for (ByteString historicalMessage : universalMessage.getMessagesList()) {
            Universal singleHistoricalMessage = Universal.newBuilder()
                    .addType(1)  // Example type, can be adjusted as needed
                    .addMessages(ByteString.copyFrom(historicalMessage.toByteArray()))
                    .build();
                    
            publisher.publishMessage(singleHistoricalMessage);
            messageCounter++;
            Logger logger = LoggerFactory.getLogger(MessageSender.class);
            logger.info("Sent Universal message with one Historical message. Total messages sent: {}", messageCounter);

            // Delay of 1 second before sending the next Historical message
            Thread.sleep(1000);  // 1000 milliseconds = 1 second
        }
    }
}

// MessagePublisher class responsible for sending messages to RabbitMQ
class MessagePublisher {
    private final Channel channel;

    public MessagePublisher(Channel channel) {
        this.channel = channel;
    }

    // Method to publish a serialized Universal message to RabbitMQ
    public void publishMessage(Universal universalMessage) throws Exception {
        byte[] serializedUniversalMessage = universalMessage.toByteArray();
        channel.basicPublish("", Producer.QUEUE_NAME, null, serializedUniversalMessage);
    }
}

// Factory class responsible for creating Historical messages
class HistoricalMessageFactory {
    public static Historical createHistoricalMessage(String sensor) {
        return Historical.newBuilder()
                .setBatchid(System.currentTimeMillis())
                .setSensor(sensor)
                .addValues(HistoricalValue.newBuilder()
                        .setT(System.currentTimeMillis())
                        .setV(1 + Math.random() * 9))  // Random value between 1 and 10
                .build();
    }
}

// Factory class responsible for creating Universal messages
class UniversalMessageFactory {
    public static Universal createUniversalMessage() {
        Universal.Builder universalBuilder = Universal.newBuilder()
                .addType(1);  // Example type, can be adjusted as needed

        for (int i = 0; i < 7; i++) {
            Historical historicalMessage = HistoricalMessageFactory.createHistoricalMessage("sensor" + (i + 1));
            universalBuilder.addMessages(ByteString.copyFrom(historicalMessage.toByteArray()));
        }

        return universalBuilder.build();
    }
}