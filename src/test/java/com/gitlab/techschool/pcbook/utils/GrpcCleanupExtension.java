package com.gitlab.techschool.pcbook.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class GrpcCleanupExtension implements AfterEachCallback {
    private final List<Resource> resources = new ArrayList<>();
    private long timeoutNanos = TimeUnit.SECONDS.toNanos(10L);
    private Stopwatch stopwatch = Stopwatch.createUnstarted();

    private boolean abruptShutdown;

    public GrpcCleanupExtension setTimeout(long timeout, TimeUnit timeUnit) {
        checkArgument(timeout > 0, "timeout should be positive");
        timeoutNanos = timeUnit.toNanos(timeout);
        return this;
    }

    /**
     * Sets a specified time source for monitoring cleanup timeout.
     *
     * @return this
     */
    @SuppressWarnings("BetaApi") // Test only.
    @VisibleForTesting
    GrpcCleanupExtension setTicker(Ticker ticker) {
        this.stopwatch = Stopwatch.createUnstarted(ticker);
        return this;
    }

    /**
     * Registers the given channel to the rule. Once registered, the channel will be automatically
     * shutdown at the end of the test.
     *
     * <p>This method need be properly synchronized if used in multiple threads. This method must
     * not be used during the test teardown.
     *
     * @return the input channel
     */
    public <T extends ManagedChannel> T register(@Nonnull T channel) {
        checkNotNull(channel, "channel");
        register(new ManagedChannelResource(channel));
        return channel;
    }

    /**
     * Registers the given server to the rule. Once registered, the server will be automatically
     * shutdown at the end of the test.
     *
     * <p>This method need be properly synchronized if used in multiple threads. This method must
     * not be used during the test teardown.
     *
     * @return the input server
     */
    public <T extends Server> T register(@Nonnull T server) {
        checkNotNull(server, "server");
        register(new ServerResource(server));
        return server;
    }

    @VisibleForTesting
    void register(Resource resource) {
        resources.add(resource);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        stopwatch.reset();
        stopwatch.start();

        InterruptedException interrupted = null;
        if (!abruptShutdown) {
            for (Resource resource : Lists.reverse(resources)) {
                resource.cleanUp();
            }

            for (int i = resources.size() - 1; i >= 0; i--) {
                try {
                    boolean released = resources.get(i).awaitReleased(
                            timeoutNanos - stopwatch.elapsed(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS);
                    if (released) {
                        resources.remove(i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted = e;
                    break;
                }
            }
        }

        if (!resources.isEmpty()) {
            for (Resource resource : Lists.reverse(resources)) {
                resource.forceCleanUp();
            }

            try {
                if (interrupted != null) {
                    throw new AssertionError(
                            "Thread interrupted before resources gracefully released", interrupted);
                } else if (!abruptShutdown) {
                    throw new AssertionError(
                            "Resources could not be released in time at the end of test: " + resources);
                }
            } finally {
                resources.clear();
            }
        }
    }

    @VisibleForTesting
    interface Resource {
        void cleanUp();

        /**
         * Error already happened, try the best to clean up. Never throws.
         */
        void forceCleanUp();

        /**
         * Returns true if the resource is released in time.
         */
        boolean awaitReleased(long duration, TimeUnit timeUnit) throws InterruptedException;
    }

    private static final class ManagedChannelResource implements Resource {
        final ManagedChannel channel;

        ManagedChannelResource(ManagedChannel channel) {
            this.channel = channel;
        }

        @Override
        public void cleanUp() {
            channel.shutdown();
        }

        @Override
        public void forceCleanUp() {
            channel.shutdownNow();
        }

        @Override
        public boolean awaitReleased(long duration, TimeUnit timeUnit) throws InterruptedException {
            return channel.awaitTermination(duration, timeUnit);
        }

        @Override
        public String toString() {
            return channel.toString();
        }
    }

    private static final class ServerResource implements Resource {
        final Server server;

        ServerResource(Server server) {
            this.server = server;
        }

        @Override
        public void cleanUp() {
            server.shutdown();
        }

        @Override
        public void forceCleanUp() {
            server.shutdownNow();
        }

        @Override
        public boolean awaitReleased(long duration, TimeUnit timeUnit) throws InterruptedException {
            return server.awaitTermination(duration, timeUnit);
        }

        @Override
        public String toString() {
            return server.toString();
        }
    }
}
