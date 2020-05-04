package org.example.kafkalatencytest;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.junit.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This test helps to measure average end-to-end latency of a messages produced to Kafka and consumed back from a
 * different thread. Each message is given a timestamp by the producer. Latency is computed by the consumer by
 * subtracting the producer timestamp from the current time.
 *
 * Here is some sample output:
 *
 * [Thread-0] INFO org.example.kafkalatencytest.TestConsumer - finished: total latency 6971
 * [Thread-0] INFO org.example.kafkalatencytest.TestConsumer - average latency: 0.6971
 *
 * With a local installation of Confluent Kafka 5.4, a number of 10000 messages, sleep time of 1ms between
 * messages, consumer start up sleep time fo 1000ms, batch size of 1000, the average latency of a round trip is
 * clearly under a millisecond on modern hardware.
 *
 * Key results:
 * * producer batch size does not have a significant impact on latency
 * * best latency is achieved with linger.ms = 0
 * * When sending at very high throughput, latency suffers
 * * Testing with a small amount of messages results in higher average latency
 * * increasing linger.ms with a high batch size significantly increases latency
 * * high linger.ms with small batch size has only little more latency than the default settings
 * * zstd compression significantly increases latency (factor 10) when used with timestamps as message payloads
 *   and a small batch size (100).
 *   With a larger batch size (10000, 1000000) latency only slightly increases.
 *   snappy compression has lower impact on latency with this type of message
 * * latency slightly increases when increasing the number of partitions on a single node kafka cluster.
 *
 */

@Slf4j
public class LatencyTest {

    public static final String TOPIC = UUID.randomUUID().toString();
    public static final int NUM_RECORDS = 10000;
    private static final long PRODUCER_SLEEP = 1l;
    private static final long CONSUMER_STARTUP_SLEEP = 1000L;
    private static final int NUM_PARTITIONS = 12;

    private Properties producerProperties() {
        Properties properties = new Properties();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, IntegerSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, LongSerializer.class);
        properties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        properties.put(ProducerConfig.BATCH_SIZE_CONFIG, 100000);
        // properties.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        return properties;
    }

    @Test
    public void test() throws InterruptedException, ExecutionException {
        log.info("Testing on topic {}", TOPIC);
        Thread consumerThread = new Thread(new TestConsumer());
        AdminClient adminClient = AdminClient.create(producerProperties());
        adminClient.createTopics(Collections.singleton(new NewTopic(TOPIC, NUM_PARTITIONS, (short) 1))).all().get();
        consumerThread.start();
        Thread.sleep(CONSUMER_STARTUP_SLEEP); // give the consumer some time to start.
        KafkaProducer<Integer, Long> kafkaProducer =
                new KafkaProducer<>(producerProperties());
        for (int i = 0; i < NUM_RECORDS + 100; i++) {
            Thread.sleep(PRODUCER_SLEEP);
            kafkaProducer.send(new ProducerRecord<>(TOPIC, i, System.currentTimeMillis()));
        }
        consumerThread.join();
    }
}

@Slf4j
class TestConsumer implements Runnable {

    private Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, IntegerDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer");
        log.info(properties.toString());
        return properties;
    }

    @Override
    public void run() {
        KafkaConsumer<Integer, Long> kafkaConsumer =
                new KafkaConsumer<>(consumerProperties());
        kafkaConsumer.subscribe(Collections.singleton(LatencyTest.TOPIC));
        AtomicInteger received = new AtomicInteger();
        AtomicLong totalLatency = new AtomicLong();
        while (received.get() < LatencyTest.NUM_RECORDS) {
            ConsumerRecords<Integer, Long> result = kafkaConsumer.poll(Duration.ofHours(1));
            result.iterator().forEachRemaining(
                    record -> {
                        received.getAndIncrement();
                        totalLatency.getAndAdd(System.currentTimeMillis() - record.value());
                    }
            );
        }
        log.info("finished: total latency {}", totalLatency.get());
        log.info("average latency: {}", totalLatency.get() / (LatencyTest.NUM_RECORDS + 0.0));
    }
}
