package com.datastax.driver.core.transport;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.datastax.driver.core.Host;
import com.datastax.driver.core.utils.SimpleFuture;

import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.transport.*;
import org.apache.cassandra.transport.messages.*;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A connection to a Cassandra Node.
 */
public class Connection extends org.apache.cassandra.transport.Connection
{
    private static final Logger logger = LoggerFactory.getLogger(Connection.class);

    // TODO: that doesn't belong here
    private static final String CQL_VERSION = "3.0.0";

    public final InetSocketAddress address;
    private final String name;

    private final ClientBootstrap bootstrap;
    private final Channel channel;
    private final Factory factory;
    private final Dispatcher dispatcher = new Dispatcher();

    private AtomicInteger inFlight = new AtomicInteger(0);
    private volatile boolean isClosed;
    private volatile String keyspace;

    private volatile boolean isDefunct;
    private volatile ConnectionException exception;


    /**
     * Create a new connection to a Cassandra node.
     *
     * The connection is open and initialized by the constructor.
     *
     * @throws ConnectionException if the connection attempts fails or is
     * refused by the server.
     */
    private Connection(String name, InetSocketAddress address, Factory factory) throws ConnectionException {
        this.address = address;
        this.factory = factory;
        this.name = name;
        this.bootstrap = factory.bootstrap();

        bootstrap.setPipelineFactory(new PipelineFactory(this));

        ChannelFuture future = bootstrap.connect(address);

        inFlight.incrementAndGet();
        try {
            // Wait until the connection attempt succeeds or fails.
            this.channel = future.awaitUninterruptibly().getChannel();
            if (!future.isSuccess())
            {
                logger.debug(String.format("[%s] Error connecting to %s%s", name, address, extractMessage(future.getCause())));
                throw new TransportException(address, "Cannot connect", future.getCause());
            }
        } finally {
            inFlight.decrementAndGet();
        }

        logger.trace(String.format("[%s] Connection opened successfully", name));
        initializeTransport();
        logger.trace(String.format("[%s] Transport initialized and ready", name));
    }

    private static String extractMessage(Throwable t) {
        if (t == null || t.getMessage().isEmpty())
            return "";
        return " (" + t.getMessage() + ")";
    }

    private void initializeTransport() throws ConnectionException {

        // TODO: we will need to get fancy about handling protocol version at
        // some point, but keep it simple for now.
        // TODO: we need to allow setting the compression to use
        StartupMessage startup = new StartupMessage(CQL_VERSION, Collections.<StartupMessage.Option, Object>emptyMap());
        try {
            Message.Response response = write(startup).get();
            switch (response.type) {
                case READY:
                    break;
                case ERROR:
                    throw defunct(new TransportException(address, String.format("Error initializing connection: %s", ((ErrorMessage)response).errorMsg)));
                case AUTHENTICATE:
                    throw new TransportException(address, "Authentication required but not yet supported");
                default:
                    throw defunct(new TransportException(address, String.format("Unexpected %s response message from server to a STARTUP message", response.type)));
            }
        } catch (ExecutionException e) {
            throw defunct(new ConnectionException(address, "Unexpected error during transport initialization", e.getCause()));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isDefunct() {
        return isDefunct;
    }

    public ConnectionException lastException() {
        return exception;
    }

    private ConnectionException defunct(ConnectionException e) {
        exception = e;
        isDefunct = true;
        return e;
    }

    public String keyspace() {
        return keyspace;
    }

    public void setKeyspace(String keyspace) throws ConnectionException {
        if (keyspace == null)
            return;

        if (this.keyspace != null && this.keyspace.equals(keyspace))
            return;

        try {
            logger.trace(String.format("[%s] Setting keyspace %s", name, keyspace));
            write(new QueryMessage("USE " + keyspace)).get();
            this.keyspace = keyspace;
        } catch (ConnectionException e) {
            throw defunct(e);
        } catch (ExecutionException e) {
            throw defunct(new ConnectionException(address, "Error while setting keyspace", e));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Write a request on this connection.
     *
     * @param request the request to send
     * @return a future on the server response
     *
     * @throws ConnectionException if the connection is closed
     * @throws TransportException if an I/O error while sending the request
     */
    public Future write(Message.Request request) throws ConnectionException {
        if (isDefunct)
            throw new ConnectionException(address, "Write attempt on defunct connection");

        if (isClosed)
            throw new ConnectionException(address, "Connection has been closed");

        request.attach(this);

        // We only support synchronous mode so far
        if (!inFlight.compareAndSet(0, 1))
            throw new RuntimeException("Busy connection (this should not happen, please open a bug report if you see this)");

        try {

            Future future = new Future(this);

            // TODO: This assumes the connection is used synchronously, fix that at some point
            dispatcher.futureRef.set(future);

            logger.trace(String.format("[%s] writting request %s", name, request));
            ChannelFuture writeFuture = channel.write(request);
            writeFuture.awaitUninterruptibly();
            if (!writeFuture.isSuccess())
            {
                logger.debug(String.format("[%s] Error writting request %s", name, request));

                ConnectionException ce;
                if (writeFuture.getCause() instanceof java.nio.channels.ClosedChannelException) {
                    ce = new TransportException(address, "Error writting: Closed channel");
                } else {
                    ce = new TransportException(address, "Error writting", writeFuture.getCause());
                }
                dispatcher.futureRef.set(null);
                throw defunct(ce);
            }

            logger.trace(String.format("[%s] request sent successfully", name));
            return future;

        } finally {
            inFlight.decrementAndGet();
        }
    }

    public void close() {
        if (isClosed)
            return;

        // TODO: put that to trace
        logger.debug(String.format("[%s] closing connection", name));

        // Make sure all new writes are rejected
        isClosed = true;

        if (!isDefunct) {
            try {
                // Busy waiting, we just wait for request to be fully written, shouldn't take long
                while (inFlight.get() > 0)
                    Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        channel.close().awaitUninterruptibly();
        // Note: we must not call releaseExternalResources, because this shutdown the executors, which are shared
    }

    // Cruft needed because we reuse server side classes, but we don't care about it
    public void validateNewMessage(Message.Type type) {};
    public void applyStateTransition(Message.Type requestType, Message.Type responseType) {};
    public ClientState clientState() { return null; };

    // TODO: We shouldn't need one factory per-host. We should just have one
    // global factory that allow to set the connections parameters and use that everywhere
    public static class Factory {

        // TODO We could share those amongst factories
        private final ExecutorService bossExecutor = Executors.newCachedThreadPool();
        private final ExecutorService workerExecutor = Executors.newCachedThreadPool();

        private final ConcurrentMap<Host, AtomicInteger> idGenerators = new ConcurrentHashMap<Host, AtomicInteger>();

        /**
         * Opens a new connection to the node this factory points to.
         *
         * @return the newly created (and initialized) connection.
         *
         * @throws ConnectionException if connection attempt fails.
         */
        public Connection open(Host host) throws ConnectionException {
            InetSocketAddress address = host.getAddress();
            String name = address.toString() + "-" + getIdGenerator(host).getAndIncrement();
            return new Connection(name, address, this);
        }

        private AtomicInteger getIdGenerator(Host host) {
            AtomicInteger g = idGenerators.get(host);
            if (g == null) {
                g = new AtomicInteger(0);
                AtomicInteger old = idGenerators.putIfAbsent(host, g);
                if (old != null)
                    g = old;
            }
            return g;
        }

        private ClientBootstrap bootstrap() {
            ClientBootstrap b = new ClientBootstrap(new NioClientSocketChannelFactory(bossExecutor, workerExecutor));

            // TODO: handle this better (use SocketChannelConfig)
            b.setOption("connectTimeoutMillis", 10000);
            b.setOption("tcpNoDelay", true);
            b.setOption("keepAlive", true);

            return b;
        }
    }

    private class Dispatcher extends SimpleChannelUpstreamHandler {

        private final AtomicReference<Future> futureRef = new AtomicReference();

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
            logger.trace(String.format("[%s] received ", e.getMessage()));

            // As soon as we set the value to the currently set future, a new write could
            // be started, so reset the futureRef *before* setting the future for this query.
            Future future = futureRef.getAndSet(null);

            // TODO: we should do something better than just throwing an exception
            if (future == null)
                throw new RuntimeException(String.format("Received %s but no future set", e.getMessage()));

            if (!(e.getMessage() instanceof Message.Response)) {
                logger.debug(String.format("[%s] Received unexpected message: %s", name, e.getMessage()));
                ConnectionException ce = new TransportException(address, "Unexpected message received: " + e.getMessage());
                defunct(ce);
                future.setException(ce);
            } else {
                future.set((Message.Response)e.getMessage());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
            logger.trace(String.format("[%s] connection error", name), e.getCause());

            // Ignore exception while writting, this will be handled by write() directly
            if (inFlight.get() > 0)
                return;

            ConnectionException ce = new TransportException(address, "Unexpected exception triggered", e.getCause());
            defunct(ce);

            Future future = futureRef.getAndSet(null);
            if (future != null)
                future.setException(ce);
        }
    }

    public static class Future extends SimpleFuture<Message.Response> {
        private final Connection connection;

        public Future(Connection connection) {
            this.connection = connection;
        }
    }

    private static class PipelineFactory implements ChannelPipelineFactory {
        // Stateless handlers
        private static final Message.ProtocolDecoder messageDecoder = new Message.ProtocolDecoder();
        private static final Message.ProtocolEncoder messageEncoder = new Message.ProtocolEncoder();
        private static final Frame.Decompressor frameDecompressor = new Frame.Decompressor();
        private static final Frame.Compressor frameCompressor = new Frame.Compressor();
        private static final Frame.Encoder frameEncoder = new Frame.Encoder();

        // One more fallout of using server side classes; not a big deal
        private static final org.apache.cassandra.transport.Connection.Tracker tracker;
        static {
            tracker = new org.apache.cassandra.transport.Connection.Tracker() {
                public void addConnection(Channel ch, org.apache.cassandra.transport.Connection connection) {}
                public void closeAll() {}
            };
        }

        private final Connection connection;
        private final org.apache.cassandra.transport.Connection.Factory cfactory;

        public PipelineFactory(final Connection connection) {
            this.connection = connection;
            this.cfactory = new org.apache.cassandra.transport.Connection.Factory() {
                public Connection newConnection() {
                    return connection;
                }
            };
        }

        public ChannelPipeline getPipeline() throws Exception {
            ChannelPipeline pipeline = Channels.pipeline();

            //pipeline.addLast("debug", new LoggingHandler());

            pipeline.addLast("frameDecoder", new Frame.Decoder(tracker, cfactory));
            pipeline.addLast("frameEncoder", frameEncoder);

            pipeline.addLast("frameDecompressor", frameDecompressor);
            pipeline.addLast("frameCompressor", frameCompressor);

            pipeline.addLast("messageDecoder", messageDecoder);
            pipeline.addLast("messageEncoder", messageEncoder);

            pipeline.addLast("dispatcher", connection.dispatcher);

            return pipeline;
        }
    }
}
