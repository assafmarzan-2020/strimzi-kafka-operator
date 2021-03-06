/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.debezium.kafka.KafkaCluster;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaConnectorList;
import io.strimzi.api.kafka.model.DoneableKafkaConnector;
import io.strimzi.api.kafka.model.KafkaConnector;
import io.strimzi.api.kafka.model.KafkaConnectorBuilder;
import io.strimzi.operator.KubernetesVersion;
import io.strimzi.operator.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.KafkaVersionTestUtils;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.strimzi.test.mockkube.MockKube;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
public class KafkaConnectorIT {

    private static final Logger log = LogManager.getLogger(KafkaConnectorIT.class.getName());

    private KafkaCluster cluster;
    private static Vertx vertx;
    private ConnectCluster connectCluster;

    @BeforeEach
    public void before() throws IOException, InterruptedException {
        vertx = Vertx.vertx();

        // Start a 3 node Kafka cluster
        cluster = new KafkaCluster();
        cluster.addBrokers(3);
        cluster.deleteDataPriorToStartup(true);
        cluster.deleteDataUponShutdown(true);
        cluster.usingDirectory(Files.createTempDirectory("operator-integration-test").toFile());
        cluster.startup();
        cluster.createTopics(getClass().getSimpleName() + "-offsets", getClass().getSimpleName() + "-config", getClass().getSimpleName() + "-status");

        // Start a N node connect cluster
        connectCluster = new ConnectCluster()
                .usingBrokers(cluster)
                .addConnectNodes(3);
        connectCluster.startup();
    }

    @AfterEach
    public void after() {
        if (connectCluster != null) {
            connectCluster.shutdown();
        }
        if (cluster != null) {
            cluster.shutdown();
        }
    }

    @AfterAll
    public static void closeVertx() {
        vertx.close();
    }

    @Test
    public void test(VertxTestContext context) throws InterruptedException {
        KafkaConnectApiImpl connectClient = new KafkaConnectApiImpl(vertx);

        KubernetesClient kubeClient = new MockKube()
                .withCustomResourceDefinition(Crds.kafkaConnector(), KafkaConnector.class,
                        KafkaConnectorList.class, DoneableKafkaConnector.class).end()
                .build();
        PlatformFeaturesAvailability pfa = new PlatformFeaturesAvailability(false, KubernetesVersion.V1_14);
        String namespace = "ns";
        String connectorName = "my-connector";
        LinkedHashMap<String, Object> config = new LinkedHashMap<>();
        config.put(TestingConnector.START_TIME_MS, 1_000);
        config.put(TestingConnector.STOP_TIME_MS, 0);
        config.put(TestingConnector.TASK_START_TIME_MS, 1_000);
        config.put(TestingConnector.TASK_STOP_TIME_MS, 0);
        config.put(TestingConnector.TASK_POLL_TIME_MS, 1_000);
        config.put(TestingConnector.TASK_POLL_RECORDS, 100);
        config.put(TestingConnector.NUM_PARTITIONS, 1);
        config.put(TestingConnector.TOPIC_NAME, "my-topic");
        KafkaConnector connector = makeConnectorWithConfig(namespace, connectorName, config);
        Crds.kafkaConnectorOperation(kubeClient).inNamespace(namespace).create(connector);
        // We have to bridge between CrdOperator and MockKube, because there's no Fabric8 API for status update
        // So we intercept stuff CrdOperator level
        CrdOperator connectCrdOperator = mock(CrdOperator.class);
        when(connectCrdOperator.updateStatusAsync(any())).thenAnswer(invocation -> {
            try {
                return Future.succeededFuture(Crds.kafkaConnectorOperation(kubeClient).inNamespace(namespace).withName(connectorName).patch(invocation.getArgument(0)));
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        });
        when(connectCrdOperator.getAsync(any(), any())).thenAnswer(invocationOnMock -> {
            try {
                return Future.succeededFuture(Crds.kafkaConnectorOperation(kubeClient).inNamespace(namespace).withName(connectorName).get());
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        });
        KafkaConnectAssemblyOperator operator = new KafkaConnectAssemblyOperator(vertx, pfa,
                new ResourceOperatorSupplier(
                        null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null,
                        null, connectCrdOperator, null, null, null, null),
                ClusterOperatorConfig.fromMap(Collections.emptyMap(), KafkaVersionTestUtils.getKafkaVersionLookup()),
            connect -> new KafkaConnectApiImpl(vertx),
            connectCluster.getPort() + 2) { };

        operator.reconcileConnector(new Reconciliation("test", "KafkaConnect", namespace, "bogus"),
                "localhost", connectClient, true, connectorName,
                connector
        ).compose(r -> {
            return connectorIsRunning(context, kubeClient, namespace, connectorName);
        }).compose(ignored -> {
            config.remove(TestingConnector.START_TIME_MS, 1_000);
            config.put(TestingConnector.START_TIME_MS, 1_000);
            Crds.kafkaConnectorOperation(kubeClient).inNamespace(namespace).withName(connectorName).patch(makeConnectorWithConfig(namespace, connectorName, config));
            return operator.reconcileConnector(new Reconciliation("test", "KafkaConnect", namespace, "bogus"),
                    "localhost", connectClient, true, connectorName,
                    connector);
        }).compose(r -> {
            return connectorIsRunning(context, kubeClient, namespace, connectorName);
        }).setHandler(context.succeeding(v -> {
            context.completeNow();
        }));
    }

    private KafkaConnector makeConnectorWithConfig(String namespace, String connectorName, LinkedHashMap<String, Object> config) {
        return new KafkaConnectorBuilder()
                    .withNewMetadata()
                        .withName(connectorName)
                        .withNamespace(namespace)
                    .endMetadata()
                    .withNewSpec()
                        .withClassName(TestingConnector.class.getName())
                        .withTasksMax(1)
                        .withConfig(config)
                    .endSpec()
                    .build();
    }

    private Future<Void> connectorIsRunning(VertxTestContext context, KubernetesClient kubeClient, String namespace, String connectorName) {
        Promise<Void> p = Promise.promise();
        KafkaConnector kafkaConnector = Crds.kafkaConnectorOperation(kubeClient).inNamespace(namespace).withName(connectorName).get();
        assertThat(kafkaConnector, notNullValue());
        assertThat(kafkaConnector.getStatus(), notNullValue());
        assertThat(kafkaConnector.getStatus().getConnectorStatus(), notNullValue());
        assertThat(kafkaConnector.getStatus().getConnectorStatus().get("connector"), instanceOf(Map.class));
        assertThat(((Map) kafkaConnector.getStatus().getConnectorStatus().get("connector")).get("state"), is("RUNNING"));
        p.complete();
        return p.future();
    }
}
