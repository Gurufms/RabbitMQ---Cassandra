
package com;

import com.rabbitmq.client.*;
import proto.Messages.Historical;
import proto.Messages.HistoricalValue;
import proto.Messages.Universal;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;

import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Consumer {

    public static void main(String[] args) throws InterruptedException {
        int consumerCount = 2;
        String queueName = "hello";
        String host = "localhost";

        RabbitMqConsumerManager consumerManager = new RabbitMqConsumerManager(queueName, host, consumerCount);
        consumerManager.startConsumers();
    }
}

class RabbitMqConsumerManager {
    private static final Logger logger = LoggerFactory.getLogger(RabbitMqConsumerManager.class);
    private final String queueName;
    private final String host;
    private final int consumerCount;
    private final ExecutorService executorService;

    public RabbitMqConsumerManager(String queueName, String host, int consumerCount) {
        this.queueName = queueName;
        this.host = host;
        this.consumerCount = consumerCount;
        this.executorService = Executors.newFixedThreadPool(consumerCount);
    }

    public void startConsumers() throws InterruptedException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setAutomaticRecoveryEnabled(true);

        for (int i = 0; i < consumerCount; i++) {
            int consumerId = i + 1;
            executorService.submit(() -> {
                try (Connection connection = factory.newConnection()) {
                    MessageConsumer consumer = new MessageConsumer(queueName, connection, consumerId);
                    consumer.start();
                } catch (Exception e) {
                    logger.error("Consumer {} encountered an error", consumerId, e);
                }
            });
        }

        executorService.shutdown();
        executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }
}

class MessageConsumer {
    private static final Logger logger = LoggerFactory.getLogger(MessageConsumer.class);
    private static final int PREFETCH_COUNT = 10000;
    private final String queueName;
    private final Connection connection;
    private final int consumerId;

    public MessageConsumer(String queueName, Connection connection, int consumerId) {
        this.queueName = queueName;
        this.connection = connection;
        this.consumerId = consumerId;
    }

    public void start() throws Exception {
        try (Channel channel = connection.createChannel()) {
            channel.queueDeclare(queueName, true, false, false, null);
            channel.basicQos(PREFETCH_COUNT);

            logger.info("Consumer {} is ready to process messages from queue {}", consumerId, queueName);

            MessageHandler handler = new MessageHandler(channel, consumerId);
            DeliverCallback deliverCallback = handler::processMessage;

            channel.basicConsume(queueName, false, deliverCallback, consumerTag -> {
                logger.info("Consumer {} cancelled", consumerId);
            });

            new CountDownLatch(1).await();
        }
    }
}

class MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(MessageHandler.class);

    private final Channel channel;
    private final int consumerId;
    private final CqlSession session;
    private final PreparedStatement preparedStatement;
    private final PreparedStatement dailyPreparedStatement;
    private final PreparedStatement weeklyPreparedStatement;
    private final PreparedStatement monthlyPreparedStatement;

    public MessageHandler(Channel channel, int consumerId) {
        this.channel = channel;
        this.consumerId = consumerId;
        this.session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress("localhost", 9999))
                .withLocalDatacenter("datacenter1")
                .withKeyspace("rabbitmq")
                .build();

        this.preparedStatement = session.prepare(
                "INSERT INTO historical_data (batch_id, sensor, timestamp, sensor_value, current_time) VALUES (?, ?, ?, ?, ?)");

        this.dailyPreparedStatement = session.prepare(
                "INSERT INTO historical_data_daily (date, sensor, total_sensor_value) VALUES (?, ?, ?)");

        this.weeklyPreparedStatement = session.prepare(
                "INSERT INTO historical_data_weekly (week_start_date, sensor, total_sensor_value) VALUES (?, ?, ?)");

        this.monthlyPreparedStatement = session.prepare(
                "INSERT INTO historical_data_monthly (month_start_date, sensor, total_sensor_value) VALUES (?, ?, ?)");
    }

    public void processMessage(String consumerTag, Delivery delivery) {
        try {
            Universal universalMessage = Universal.parseFrom(delivery.getBody());

            for (ByteString serializedMessage : universalMessage.getMessagesList()) {
                processHistoricalMessage(serializedMessage);
            }

            // Acknowledge the message once it's processed
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
        } catch (InvalidProtocolBufferException e) {
            logger.error("Consumer {}: Failed to deserialize Universal message", consumerId, e);
            acknowledgeMessage(delivery);
        } catch (Exception e) {
            logger.error("Consumer {}: Error processing message", consumerId, e);
            acknowledgeMessage(delivery);
        }
    }

    private void acknowledgeMessage(Delivery delivery) {
        try {
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
        } catch (Exception nackEx) {
            logger.error("Consumer {}: Failed to nack message", consumerId, nackEx);
        }
    }

    private void processHistoricalMessage(ByteString serializedMessage) {
        try {
            Historical historicalMessage = Historical.parseFrom(serializedMessage);
            long batchId = historicalMessage.getBatchid();
            String sensor = historicalMessage.getSensor();
            String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());

            double totalSensorValue = 0.0;

            for (HistoricalValue value : historicalMessage.getValuesList()) {
                long timestamp = value.getT();
                double sensorValue = value.getV();

                // Insert into historical data table
                insertIntoHistoricalData(batchId, sensor, timestamp, sensorValue, currentTime);

                // Aggregate sensor value
                totalSensorValue += sensorValue;
            }

            // Insert daily, weekly, and monthly rollups
            insertDailyRollup(sensor, totalSensorValue);
            insertWeeklyRollup(sensor, totalSensorValue);
            insertMonthlyRollup(sensor, totalSensorValue);
        } catch (InvalidProtocolBufferException e) {
            logger.error("Consumer {}: Failed to deserialize Historical message", consumerId, e);
        }
    }

    private void insertIntoHistoricalData(long batchId, String sensor, long timestamp, double sensorValue, String currentTime) {
        try {
            BoundStatement boundStatement = preparedStatement.bind(
                    batchId, sensor, timestamp, sensorValue, currentTime);
            session.execute(boundStatement);
            logger.info("Inserted message into historical_data table: {} - {} - {}", sensor, timestamp, sensorValue);
        } catch (Exception e) {
            logger.error("Consumer {}: Failed to insert into historical_data table", consumerId, e);
        }
    }

    private void insertDailyRollup(String sensor, double totalSensorValue) {
        try {
            String currentDate = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            BoundStatement boundStatement = dailyPreparedStatement.bind(
                    currentDate, sensor, totalSensorValue);
            session.execute(boundStatement);
            logger.info("Inserted daily rollup into historical_data_daily table: {} - {}", sensor, totalSensorValue);
        } catch (Exception e) {
            logger.error("Consumer {}: Failed to insert daily rollup", consumerId, e);
        }
    }

    private void insertWeeklyRollup(String sensor, double totalSensorValue) {
        try {
            String weekStartDate = new SimpleDateFormat("yyyy-ww").format(new Date());
            BoundStatement boundStatement = weeklyPreparedStatement.bind(
                    weekStartDate, sensor, totalSensorValue);
            session.execute(boundStatement);
            logger.info("Inserted weekly rollup into historical_data_weekly table: {} - {}", sensor, totalSensorValue);
        } catch (Exception e) {
            logger.error("Consumer {}: Failed to insert weekly rollup", consumerId, e);
        }
    }

    private void insertMonthlyRollup(String sensor, double totalSensorValue) {
        try {
            String monthStartDate = new SimpleDateFormat("yyyy-MM").format(new Date());
            BoundStatement boundStatement = monthlyPreparedStatement.bind(
                    monthStartDate, sensor, totalSensorValue);
            session.execute(boundStatement);
            logger.info("Inserted monthly rollup into historical_data_monthly table: {} - {}", sensor, totalSensorValue);
        } catch (Exception e) {
            logger.error("Consumer {}: Failed to insert monthly rollup", consumerId, e);
        }
    }
}
