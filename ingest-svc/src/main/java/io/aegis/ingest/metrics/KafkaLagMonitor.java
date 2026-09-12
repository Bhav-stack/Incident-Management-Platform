package io.aegis.ingest.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Exposes this consumer group's lag per topic as the {@code aegis_kafka_lag}
 * gauge (tagged by {@code group} and {@code topic}).
 *
 * <p>Lag is read from the broker through the admin API rather than from the
 * consumer instance, so the number is real even when no consumer is currently
 * assigned (during a restart, or with zero traffic).
 *
 * <p>Each topic owns a stable {@link AtomicLong} that the meter reads on
 * scrape. That matters: {@code MeterRegistry.gauge(name, tags, number)} binds
 * the value it was first called with and silently ignores later calls, so a
 * naive implementation freezes the first sample forever.
 */
@Component
public class KafkaLagMonitor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KafkaLagMonitor.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final AdminClient admin;
    private final List<String> topics;
    private final String groupId;
    private final Map<String, AtomicLong> lagByTopic = new ConcurrentHashMap<>();

    public KafkaLagMonitor(
            ConsumerFactory<String, String> consumerFactory,
            MeterRegistry metrics,
            @Value("${metrics.kafka-lag-topics:raw.events}") List<String> topics,
            @Value("${spring.kafka.consumer.group-id:ingest}") String groupId) {

        this.topics = topics;
        this.groupId = groupId;

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                consumerFactory.getConfigurationProperties()
                        .get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        this.admin = AdminClient.create(props);

        for (String topic : topics) {
            AtomicLong lag = lagByTopic.computeIfAbsent(topic, ignored -> new AtomicLong());
            metrics.gauge("aegis_kafka_lag",
                    Tags.of("group", groupId, "topic", topic),
                    lag, AtomicLong::doubleValue);
        }
    }

    /** Samples every configured topic; a failure keeps the last known value. */
    @Scheduled(fixedDelayString = "${metrics.kafka-lag-interval:15s}")
    public void sample() {
        for (String topic : topics) {
            try {
                long lag = lagFor(topic);
                lagByTopic.get(topic).set(lag);
                log.debug("Kafka lag {} / {}: {}", groupId, topic, lag);
            } catch (Exception e) {
                // Broker unreachable or the topic does not exist yet: this is
                // normal during startup and outages, so keep sampling.
                log.debug("Kafka lag sample for {} failed: {}", topic, e.toString());
            }
        }
    }

    private long lagFor(String topic) throws Exception {

        List<TopicPartition> partitions =
                admin.describeTopics(List.of(topic))
                        .allTopicNames()
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .get(topic)
                        .partitions()
                        .stream()
                        .map(partition -> new TopicPartition(topic, partition.partition()))
                        .toList();

        if (partitions.isEmpty()) {
            return 0L;
        }

        Map<TopicPartition, OffsetAndMetadata> committed =
                admin.listConsumerGroupOffsets(groupId)
                        .partitionsToOffsetAndMetadata()
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        Map<TopicPartition, Long> ends =
                admin.listOffsets(partitions.stream()
                                .collect(Collectors.toMap(
                                        partition -> partition,
                                        partition -> OffsetSpec.latest())))
                        .all()
                        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .entrySet()
                        .stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().offset()));

        long lag = 0L;
        for (TopicPartition partition : partitions) {
            long end = ends.getOrDefault(partition, 0L);
            OffsetAndMetadata committedOffset = committed.get(partition);
            long position = committedOffset == null ? 0L : committedOffset.offset();
            lag += Math.max(0L, end - position);
        }
        return lag;
    }

    @Override
    public void destroy() {
        admin.close(Duration.ofSeconds(5));
    }
}
