package io.aegis.incident.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Exposes the consumer group's lag per topic as {@code aegis_kafka_lag}
 * gauges. Lag = sum over partitions of (log end offset - committed offset),
 * read from the broker via the admin client, so it works even when no
 * consumer instance is currently assigned (e.g. a crashed replica).
 *
 * <p>Spring Kafka also auto-registers {@code kafka.consumer.*} metrics
 * (fetch.manager.records.lag per partition); this gauge is the coarse
 * per-topic number the Grafana dashboard watches. Sampling is resilient:
 * a broker outage logs at debug and never fails the app.
 */
@Component
public class KafkaLagMonitor {

    private static final Logger log = LoggerFactory.getLogger(KafkaLagMonitor.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ConsumerFactory<String, String> consumerFactory;
    private final MeterRegistry metrics;
    private final List<String> topics;
    private final String groupId;

    public KafkaLagMonitor(ConsumerFactory<String, String> consumerFactory,
                           MeterRegistry metrics,
                           @Value("${metrics.kafka-lag-topics:anomalies,action.commands}") List<String> topics,
                           @Value("${spring.kafka.consumer.group-id:incident}") String groupId) {
        this.consumerFactory = consumerFactory;
        this.metrics = metrics;
        this.topics = topics;
        this.groupId = groupId;
    }

    @Scheduled(fixedDelayString = "${metrics.kafka-lag-interval:15s}")
    public void sample() {
        Properties props = new Properties();
        props.putAll(consumerFactory.getConfigurationProperties());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        try (AdminClient admin = AdminClient.create(props)) {
            for (String topic : topics) {
                long lag = lagFor(admin, topic);
                metrics.gauge("aegis_kafka_lag", Tags.of("group", groupId, "topic", topic), lag);
                log.debug("Kafka lag {} / {}: {}", groupId, topic, lag);
            }
        } catch (Exception e) {
            // Broker down or topic not created yet: keep last gauge values.
            log.debug("Kafka lag sampling failed: {}", e.getMessage());
        }
    }

    private long lagFor(AdminClient admin, String topic) throws Exception {
        List<TopicPartition> partitions = admin.describeTopics(List.of(topic))
                .allTopicNames().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .get(topic).partitions().stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .toList();
        if (partitions.isEmpty()) {
            return 0L;
        }
        Map<TopicPartition, OffsetAndMetadata> committed = admin
                .listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        Map<TopicPartition, Long> ends = admin.listOffsets(partitions.stream()
                .collect(java.util.stream.Collectors.toMap(p -> p, p -> OffsetSpec.latest())))
                .all().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        long lag = 0L;
        for (TopicPartition partition : partitions) {
            long end = ends.getOrDefault(partition, 0L);
            OffsetAndMetadata offset = committed.get(partition);
            long position = offset == null ? 0L : offset.offset();
            lag += Math.max(0L, end - position);
        }
        return lag;
    }
}