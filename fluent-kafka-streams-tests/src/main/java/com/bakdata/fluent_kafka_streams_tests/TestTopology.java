package com.bakdata.fluent_kafka_streams_tests;

import com.bakdata.schemaregistrymock.SchemaRegistryMock;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Wither;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyDescription;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TestTopology<DefaultK, DefaultV> implements BeforeEachCallback, AfterEachCallback {
    @NonNull
    private final Properties properties;

    private final SchemaRegistryMock schemaRegistry = new SchemaRegistryMock();

    @Getter
    private TopologyTestDriver testDriver;

    @NonNull
    private Function<Properties, Topology> topologyFactory;

    @Getter(lazy = true)
    private final StreamsConfig streamsConfig = new StreamsConfig(properties);

    private final Set<String> inputTopics = new HashSet<>();
    private final Set<String> outputTopics = new HashSet<>();

    @Wither
    private Serde<DefaultK> defaultKeySerde;
    @Wither
    private Serde<DefaultV> defaultValueSerde;

    public TestTopology(Function<Properties, Topology> topologyFactory, Properties properties) {
        this.topologyFactory = topologyFactory;
        this.properties = new Properties();
        if (properties != null) {
            this.properties.putAll(properties);
        }
        this.defaultKeySerde = getStreamsConfig().defaultKeySerde();
        this.defaultValueSerde = getStreamsConfig().defaultValueSerde();
    }

    public TestTopology(Topology topology, Properties properties) {
        this((Properties) -> topology, properties);
    }

    public TestTopology(Supplier<Topology> topologyFactory, Properties properties) {
        this((Properties) -> topologyFactory.get(), properties);
    }

    private Serde<DefaultK> getDefaultKeySerde() {
        return defaultKeySerde;
    }

    private Serde<DefaultV> getDefaultValueSerde() {
        return defaultValueSerde;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        testDriver.close();
        schemaRegistry.afterEach(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        schemaRegistry.beforeEach(context);
        properties.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, getSchemaRegistryUrl());
        final Topology topology = topologyFactory.apply(properties);
        testDriver = new TopologyTestDriver(topology, properties);

        this.inputTopics.clear();
        this.outputTopics.clear();

        BiConsumer<String, Set<String>> addTopics = (topics, topicStore) -> {
            if (topics.contains("KSTREAM-") || topics.contains("KTABLE-")) {
                // Internal node created by Kafka. Not relevant for testing.
                return;
            }

            List<String> allTopics = new ArrayList<>();
            if (topics.startsWith("[")) {
                allTopics.addAll(Arrays.asList(topics.substring(1, topics.length() - 1).split(",")));
            } else {
                // Only one topic present without leading '['
                allTopics.add(topics);
            }

            topicStore.addAll(allTopics);
        };

        // TODO: get serializers
        for (TopologyDescription.Subtopology subtopology : topology.describe().subtopologies()) {
            for (TopologyDescription.Node node : subtopology.nodes()) {
                if (node instanceof TopologyDescription.Source) {
                    final String topics = ((TopologyDescription.Source) node).topics();
                    addTopics.accept(topics, inputTopics);
                } else if (node instanceof TopologyDescription.Sink) {
                    final String topic = ((TopologyDescription.Sink) node).topic();
                    addTopics.accept(topic, outputTopics);
                }
            }
        }
    }

    public TestInput<DefaultK, DefaultV> input() {
        if(inputTopics.size() != 1) {
            throw new IllegalStateException("Please use #input(String) to select a topic");
        }
        return input(inputTopics.iterator().next());
    }

    public TestInput<DefaultK, DefaultV> input(String topic) {
        if (!inputTopics.contains(topic)) {
            throw new NoSuchElementException(String.format("Input topic '%s' not found", topic));
        }
        return new TestInput<>(testDriver, topic, getDefaultKeySerde(), getDefaultValueSerde());
    }

    public TestOutput<DefaultK, DefaultV> streamOutput() {
        if(outputTopics.size() != 1) {
            throw new IllegalStateException("Please use #output(String) to select a topic");
        }
        return streamOutput(outputTopics.iterator().next());
    }

    public TestOutput<DefaultK, DefaultV> streamOutput(String topic) {
        if (!outputTopics.contains(topic)) {
            throw new NoSuchElementException(String.format("Output topic '%s' not found", topic));
        }
        return new StreamOutput<>(testDriver, topic, getDefaultKeySerde(), getDefaultValueSerde());
    }

    public TestOutput<DefaultK, DefaultV> tableOutput() {
        return streamOutput().asTable();
    }

    public TestOutput<DefaultK, DefaultV> tableOutput(String topic) {
        return streamOutput(topic).asTable();
    }

    public SchemaRegistryClient getSchemaRegistry() {
        return schemaRegistry.getSchemaRegistryClient();
    }

    public String getSchemaRegistryUrl() {
        return schemaRegistry.url();
    }
}