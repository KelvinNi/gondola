/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the New BSD License.
 * See the accompanying LICENSE file for terms.
 */

package com.yahoo.gondola.impl;

import com.yahoo.gondola.Channel;
import com.yahoo.gondola.Config;
import com.yahoo.gondola.Gondola;

import com.yahoo.gondola.GondolaException;
import com.yahoo.gondola.core.ExceptionLogger;
import com.yahoo.gondola.core.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class maintains a socket between this member another another. The client should call getOutputStream() before
 * every write to the other member. Likewise for getInputStream(). These methods return a valid stream for use and will
 * block until one is available. <p> Design note: it might be nicer to have a single socket between Gondola instances.
 * However, in order to do this, the messages would need to contain the destination member id. And if we do this, we
 * can't share the same Message object in all the send queues. <p> There is a handshake protocol when one node connects
 * to another. See SocketNetwork for details. <p> Synchronization notes: - when socketValid is false and memberId &gt;
 * peerId, a SocketCreator thread will be continuously trying to connect to the peer. - when socketValid is false,
 * socket, in, and out will all be null.
 */
public class SocketChannel implements Channel {
    final static Logger logger = LoggerFactory.getLogger(SocketChannel.class);

    Gondola gondola;
    int memberId;
    int peerId;
    boolean retry = true;

    // Used to protect the socket and the streams. Ensures that only one reconnect occurs at a time.
    final ReentrantLock lock = new ReentrantLock();
    final Condition socketValidCond = lock.newCondition();

    // Used to wake up a thread that's retrying to reconnect to a remote member, before its timeout period.
    final Condition retryCond = lock.newCondition();

    // When false, there is a thread that continuously attempts to create a new connection
    volatile boolean socketValid = false;
    InetSocketAddress inetSocketAddress;

    // Protected by the lock
    volatile Socket socket;
    volatile OutputStream out;
    volatile InputStream in;

    // Config variables
    boolean networkTracing;
    int createSocketRetryPeriod;
    int heartbeatPeriod;
    int connTimeout;

    // List of threads running in this class. There should be at most one retry thread running at any time.
    List<Thread> threads = new CopyOnWriteArrayList<>();

    // This boolean is used to stop new reconnect threads from being created as the channel is being shut down
    boolean stopped;

    /**
     *
     * @param gondola
     * @param memberId
     * @param toMemberId
     */
    public SocketChannel(Gondola gondola, int memberId, int toMemberId) {
        this.gondola = gondola;
        this.memberId = memberId;
        this.peerId = toMemberId;

        gondola.getConfig().registerForUpdates(config -> {
            networkTracing = config.getBoolean("gondola.tracing.network");
            createSocketRetryPeriod = config.getInt("network.socket.create_socket_retry_period");
            heartbeatPeriod = config.getInt("raft.heartbeat_period");
            connTimeout = config.getInt("network.socket.connect_timeout");
        });

        if (networkTracing) {
            logger.info("[{}-{}] Creating connection to {} {}", gondola.getHostId(), memberId, toMemberId,
                        this.toString());
        }
        inetSocketAddress = gondola.getConfig().getAddressForMember(peerId);
    }


    /**
     * See Stoppable.start().
     */
    @Override
    public void start() throws GondolaException {
        reconnect();
    }

    /**
     * See Stoppable.stop().
     */
    @Override
    public boolean stop() {
        // Stop new retry threads from starting
        stopped = true;

        // Stop any existing retry threads and then close the current socket, if any
        for (Thread t : threads) {
            ((SocketCreator) t).close();
        }
        boolean status = Utils.stopThreads(threads);
        close(socket, in, out);
        ((SocketNetwork) gondola.getNetwork()).removeChannel(this);
        return status;
    }

    void disableRetry() {
        retry = false;
    }

    /**
     * See Channel.getMemberId().
     */
    @Override
    public int getRemoteMemberId() {
        return peerId;
    }

    /**
     * See Channel.getRemoteAddress().
     */
    @Override
    public String getRemoteAddress() {
        return String.format("%s:%d",
                             inetSocketAddress.getAddress().getCanonicalHostName(),
                             inetSocketAddress.getPort());
    }

    /**
     * See Channel.isOperational().
     */
    @Override
    public boolean isOperational() {
        return socketValid;
    }

    /**
     * See Channel.awaitOperational().
     */
    @Override
    public void awaitOperational() throws InterruptedException {
        lock.lock();
        try {
            while (!socketValid || this.in == null) {
                awaitOperationalUnlocked();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * See Channel.getInputStream().
     *
     * @return a non-null input stream
     */
    @Override
    public InputStream getInputStream(InputStream in, boolean errorOccurred) throws InterruptedException, EOFException {
        lock.lock();
        try {
            if (stopped) {
                throw new EOFException("channel for member " + memberId + " has been stopped");
            }
            if (!socketValid || this.in == null || errorOccurred && in == this.in) {
                awaitOperationalUnlocked();
            }
            return this.in;
        } finally {
            lock.unlock();
        }
    }

    /**
     * See Channel.getOutputStream().
     *
     * @return a non-null output stream
     */
    @Override
    public OutputStream getOutputStream(OutputStream out, boolean errorOccurred)
        throws InterruptedException, EOFException {
        lock.lock();
        try {
            if (stopped) {
                throw new EOFException("channel for member " + memberId + " has been stopped");
            }
            if (!socketValid || this.out == null || errorOccurred && out == this.out) {
                awaitOperationalUnlocked();
            }
            return this.out;
        } finally {
            lock.unlock();
        }
    }

    /*********************** non-public methods ********************/

    /**
     * Blocks until the socket is operational. Must be called while lock is locked.
     */
    void awaitOperationalUnlocked() throws InterruptedException {
        // If the socket was valid, create a thread to reconnect
        if (socketValid) {
            logger.info("[{}-{}] Socket to {} is now non-operational. Closing.",
                        gondola.getHostId(), memberId, peerId);
            socketValid = false;

            // Close the current socket
            close(socket, in, out);
            socket = null;
            in = null;
            out = null;

            reconnect();
        }

        // Wait for the socket to be available
        while (!socketValid) {
            if (networkTracing) {
                logger.info("[{}-{}] Waiting for valid socket to {}", gondola.getHostId(), memberId, peerId);
            }
            socketValidCond.await();
        }
        if (networkTracing) {
            logger.info("[{}-{}] {}: Valid socket now available to {}",
                        gondola.getHostId(), memberId, Thread.currentThread().getName(), peerId);
        }
    }

    /**
     * Called when a new socket to the remote member is ready for use.
     */
    void setSocket(Socket socket, InputStream in, OutputStream out) throws IOException {
        lock.lock();
        try {
            logger.info("[{}-{}] {}valid socket to {} is being replaced ",
                        gondola.getHostId(), memberId, socketValid ? "A " : "An in", peerId);
            close(this.socket, this.in, this.out);

            // Update new streams
            this.socket = socket;
            this.in = in;
            this.out = out;

            // Inform waiters
            socketValid = true;
            socketValidCond.signalAll();
            logger.info("[{}-{}] Socket to {} is now operational", gondola.getHostId(), memberId, peerId);
        } finally {
            lock.unlock();
        }
    }

    /*
     * Used to interrupt the reconnect thread's delay to attempt a reconnect immediately.
     */
    void retry() {
        lock.lock();
        try {
            retryCond.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void close(Socket socket, InputStream in, OutputStream out) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (Exception e) {
            logger.info("Failed to close input stream to member " + peerId, e);
        }
        try {
            if (out != null) {
                out.close();
            }
        } catch (Exception e) {
            logger.info("Failed to close output stream to member " + peerId, e);
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception e) {
            logger.info("Failed to close socket to member " + peerId, e);
        }
    }

    /**
     * By calling this method, the connection's setSocket() method
     * will eventually get called when a socket connection is established to peerId.
     */
    void reconnect() {
        if (retry) {
            logger.info("[{}-{}] Reconnecting socket {} to {}", gondola.getHostId(), memberId, socket, peerId);

            // If the remote member is not part of this member's shard, this member is assumed to be a slave
            Config config = gondola.getConfig();
            boolean isSlave = config.getMember(memberId).getShardId() != config.getMember(peerId).getShardId();

            // Initiate the connection only if this member id is
            // larger than the remote member or if this member is a
            // slave.  Otherwise, it's assumed that the remote member will initiate the connection.
            boolean initiateCall = memberId > peerId || isSlave;
            new SocketCreator(initiateCall).start();
        }
    }

    /**
     * This thread continuously attempts to establish a valid socket to the remote member. Once connected, the thread
     * dies.
     */
    class SocketCreator extends Thread {
        Socket socket = null;
        InputStream in = null;
        OutputStream out = null;
        boolean makeCall;
        ExceptionLogger excLogger;

        /**
         * @param makeCall if true, this thread initiates a call to the remote member. Otherwise,
         *                 a request is sent to the remote member to call back.
         */
        SocketCreator(boolean makeCall) {
            setName(String.format("SocketCreator-%d-%d", memberId, peerId));
            this.makeCall = makeCall;
            excLogger = new ExceptionLogger(gondola)
                .setMessage(eMsg -> {
                        return String.format("[%s-%d] Failed to create socket to %d (%s): %s",
                                             gondola.getHostId(), memberId, peerId, inetSocketAddress,
                                             eMsg);
                    })
                .setNoStackTracePattern("Connection reset|End-of-file")
                .setNoStackTraceClasses(ConnectException.class, SocketTimeoutException.class)
                .setAdditionalMessage(eMsg -> {
                        return String.format("[%s-%d] Will retry creating the socket to %d (%s) every %dms",
                                    gondola.getHostId(), memberId, peerId, inetSocketAddress, createSocketRetryPeriod);
                    });
        }

        public void run() {
            // Handle the case where the thread was just started while the channel was being shut down
            threads.add(this);
            if (stopped) {
                return;
            }

            here:
            while (!stopped) {
                try {
                    socket = new Socket();
                    socket.connect(inetSocketAddress, connTimeout);
                    socket.setTcpNoDelay(true);

                    // Send hello message
                    in = socket.getInputStream();
                    out = socket.getOutputStream();
                    SocketNetwork.Hello hello = new SocketNetwork.Hello(gondola.getHostId(), in, out);
                    if (makeCall) {
                        // Wait for call from peer
                        hello.makeCall(memberId, peerId);

                        // Socket is now valid
                        setSocket(socket, in, out);
                    } else {
                        // Ask the peer to call back and initiate a connection
                        hello.requestCallBack(memberId, peerId);
                        close();
                    }

                    // No exceptions means success
                    logger.info("[{}-{}] Socket created to {} ({})",
                                gondola.getHostId(), memberId, peerId, inetSocketAddress);
                    threads.remove(this);
                    return;
                } catch (Throwable e) {
                    close();
                    excLogger.warn(e);

                    // Wait before retrying
                    lock.lock();
                    try {
                        gondola.getClock().awaitCondition(lock, retryCond, createSocketRetryPeriod);
                    } catch (InterruptedException e1) {
                        break here;
                    } finally {
                        lock.unlock();
                    }
                }
            }

            // Clean up
            close();
            threads.remove(this);
        }

        public void close() {
            SocketChannel.this.close(socket, in, out);
        }
    }

    public InetSocketAddress getInetSocketAddress() {
        return inetSocketAddress;
    }
}
