package com.fnklabs.nast.network.echo;

import com.fnklabs.metrics.Counter;
import com.fnklabs.metrics.MetricsFactory;
import com.fnklabs.nast.commons.Executors;
import com.fnklabs.nast.network.io.ClientChannel;
import com.fnklabs.nast.network.io.ServerChannel;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.Futures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class IntegrationTest {

    public static final int ATTEMPTS = 1_00;
    private static final Logger log = LoggerFactory.getLogger(IntegrationTest.class);
    public static final int CONNECTOR_POOL_SIZE = 1;

    private HostAndPort hostAndPort = HostAndPort.fromParts("127.0.0.1", 10_000);

    private ServerChannel server;
    private ExecutorService executorService;


    @BeforeEach
    public void setUp() throws Exception {}

    @AfterEach
    public void tearDown() throws Exception {
        if (executorService != null) {
            Executors.shutdown(executorService);
        }
        server.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    public void syncRequests(int parallelClients) throws Exception {
        server = new ServerChannel(hostAndPort, new ServerEchoChannelHandler(1000), parallelClients);
        executorService = Executors.fixedPool("test", parallelClients, ATTEMPTS);

        ClientChannel[] clients = new ClientChannel[parallelClients];

        ClientChannelHandler communicationHandler = new ClientChannelHandler(1000);

        for (int i = 0; i < parallelClients; i++) {
            clients[i] = new ClientChannel(hostAndPort, communicationHandler);
        }


        CountDownLatch countDownLatch = new CountDownLatch(ATTEMPTS);

        for (int i = 0; i < ATTEMPTS; i++) {
            int idx = i;

            executorService.submit(() -> {
                CompletableFuture<Integer> replyFuture = new CompletableFuture<>();

                ClientChannel client = clients[idx % parallelClients];

                communicationHandler.REPLY_FUTURES.put(idx, replyFuture);

                ByteBuffer dataBuffer = ByteBuffer.allocate(8);
                dataBuffer.putInt(idx);
                dataBuffer.putInt(idx);

                dataBuffer.flip();

                log.debug("send {} to {}", idx, client);

                CompletableFuture<Void> completableFuture = client.send(dataBuffer);

                completableFuture.exceptionally(e -> {
                    replyFuture.completeExceptionally(e);

                    return null;
                });


                log.debug("await {}", idx);

                Futures.getUnchecked(replyFuture);

                countDownLatch.countDown();

            });
        }

        countDownLatch.await(15, TimeUnit.SECONDS);

        for (int i = 0; i < 1000; i++) {
            CompletableFuture<Void> compositeFuture = CompletableFuture.allOf(communicationHandler.REPLY_FUTURES.values().toArray(new CompletableFuture[0]));

            try {
                compositeFuture.get(5, TimeUnit.SECONDS);

                break;
            } catch (Exception e) {
                log.info("futures {}", communicationHandler.REPLY_FUTURES);
            }
        }

        for (ClientChannel client : clients) {
            client.close();
        }
    }


    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    public void asyncRequests(int parallelClients) throws Exception {
        server = new ServerChannel(hostAndPort, new ServerEchoChannelHandler(1000), parallelClients);

        executorService = Executors.fixedPool("test", parallelClients, ATTEMPTS);

        ClientChannel[] clients = new ClientChannel[parallelClients];

        ClientChannelHandler communicationHandler = new ClientChannelHandler(1000);

        for (int i = 0; i < parallelClients; i++) {
            clients[i] = new ClientChannel(hostAndPort, communicationHandler);
        }

        Counter pendingRequests = MetricsFactory.getMetrics().getCounter("test.pending.request");

        CountDownLatch latch = new CountDownLatch(ATTEMPTS);


        for (int i = 0; i < ATTEMPTS; i++) {
            int idx = i;

            CompletableFuture<Integer> replyFuture = new CompletableFuture<>();

            executorService.submit(() -> {
                int clientIdx = idx % parallelClients;

                ClientChannel client = clients[clientIdx];

                communicationHandler.REPLY_FUTURES.put(idx, replyFuture);

                ByteBuffer dataBuffer = ByteBuffer.allocate(8);
                dataBuffer.putInt(idx);
                dataBuffer.putInt(idx);

                dataBuffer.flip();

                log.debug("send {} via {} client", idx, clientIdx);

                CompletableFuture<Void> completableFuture = client.send(dataBuffer);

                completableFuture.exceptionally(e -> {
                    log.warn("can't send data {}", idx, e);
                    replyFuture.completeExceptionally(e);

                    return null;
                });

                completableFuture.thenAccept(r -> {
                    log.debug("data was send {}", idx);

                    pendingRequests.inc();
                });

                replyFuture.thenAccept(r -> {
                    pendingRequests.dec();
                });

                latch.countDown();

            });
        }

        latch.await();

        for (int i = 0; i < 1000; i++) {
            CompletableFuture<Void> compositeFuture = CompletableFuture.allOf(communicationHandler.REPLY_FUTURES.values().toArray(new CompletableFuture[0]));

            try {
                compositeFuture.get(5, TimeUnit.SECONDS);

                break;
            } catch (Exception e) {
                log.info("futures {}", communicationHandler.REPLY_FUTURES);
            }
        }
        for (ClientChannel client : clients) {
            client.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 50})
    public void checkParallelConnections(int parallelConnections) throws Exception {
        server = new ServerChannel(hostAndPort, new ServerEchoChannelHandler(100), CONNECTOR_POOL_SIZE);

        List<ClientChannel> clients = new ArrayList<>();

        try {


            for (int i = 0; i < parallelConnections; i++) {
                ClientChannel client = new ClientChannel(hostAndPort, new ClientChannelHandler(100));

                clients.add(client);
            }
        } finally {

            for (ClientChannel client : clients) {
                try {
                    client.close();
                } catch (Exception e) {}
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 50})
    public void checkSequenceConnection(int connections) throws Exception {
        server = new ServerChannel(hostAndPort, new ServerEchoChannelHandler(100), CONNECTOR_POOL_SIZE);

        for (int i = 0; i < connections; i++) {
            log.debug("create {} client", i);
            try (ClientChannel client = new ClientChannel(hostAndPort, new ClientChannelHandler(100))) {


                log.debug("{} client was created", i);
            } catch (Exception e) {
                throw e;
            }

        }
    }


}