/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.io.transport.modbus.internal;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.commons.pool2.KeyedObjectPool;
import org.apache.commons.pool2.SwallowedExceptionListener;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.smarthome.core.common.ThreadPoolManager;
import org.eclipse.smarthome.core.scheduler.ExpressionThreadPoolManager;
import org.eclipse.smarthome.core.scheduler.ExpressionThreadPoolManager.ExpressionThreadPoolExecutor;
import org.openhab.io.transport.modbus.ModbusCallback;
import org.openhab.io.transport.modbus.ModbusConnectionException;
import org.openhab.io.transport.modbus.ModbusManager;
import org.openhab.io.transport.modbus.ModbusManagerListener;
import org.openhab.io.transport.modbus.ModbusReadCallback;
import org.openhab.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.io.transport.modbus.ModbusRequestBlueprint;
import org.openhab.io.transport.modbus.ModbusTransportException;
import org.openhab.io.transport.modbus.ModbusUnexpectedTransactionIdException;
import org.openhab.io.transport.modbus.ModbusWriteCallback;
import org.openhab.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.io.transport.modbus.endpoint.EndpointPoolConfiguration;
import org.openhab.io.transport.modbus.endpoint.ModbusSerialSlaveEndpoint;
import org.openhab.io.transport.modbus.endpoint.ModbusSlaveEndpoint;
import org.openhab.io.transport.modbus.endpoint.ModbusSlaveEndpointVisitor;
import org.openhab.io.transport.modbus.endpoint.ModbusTCPSlaveEndpoint;
import org.openhab.io.transport.modbus.endpoint.ModbusUDPSlaveEndpoint;
import org.openhab.io.transport.modbus.internal.pooling.ModbusSlaveConnectionFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.wimpi.modbus.Modbus;
import net.wimpi.modbus.ModbusException;
import net.wimpi.modbus.ModbusIOException;
import net.wimpi.modbus.ModbusSlaveException;
import net.wimpi.modbus.io.ModbusTransaction;
import net.wimpi.modbus.msg.ModbusRequest;
import net.wimpi.modbus.msg.ModbusResponse;
import net.wimpi.modbus.net.ModbusSlaveConnection;

/**
 * <p>
 * ModbusManagerImpl class.
 * </p>
 *
 * @author Sami Salonen
 */
public class ModbusManagerImpl implements ModbusManager {

    private static class PollTaskUnregistered extends Exception {
        public PollTaskUnregistered(String msg) {
            super(msg);
        }

        private static final long serialVersionUID = 6939730579178506885L;
    }

    @FunctionalInterface
    private interface ModbusOperation<T> {

        /**
         * Execute the operation.
         *
         * All errors should be raised. There should not be any retry mechanism implemented at this level
         *
         * @param task task to execute
         * @param connection connection to use
         * @throws Exception on IO errors, slave exception responses, and when transaction IDs of the request and
         *             response do not match
         */
        public void accept(String operationId, T task, ModbusSlaveConnection connection) throws Exception;

    }

    static private <R> void checkTransactionId(ModbusResponse response, ModbusRequest libRequest,
            TaskWithEndpoint<R, ? extends ModbusCallback> task, String operationId)
            throws ModbusUnexpectedTransactionIdException {
        // Compare request and response transaction ID. NOTE: ModbusTransaction.getTransactionID() is static and
        // not safe to use
        if ((response.getTransactionID() != libRequest.getTransactionID()) && !response.isHeadless()) {
            logger.warn(
                    "Transaction id of the response ({}) does not match request ({}) {}. Endpoint {}. Ignoring response. [operation ID {}]",
                    response.getTransactionID(), libRequest.getTransactionID(), task.getRequest(), task.getEndpoint(),
                    operationId);
            throw new ModbusUnexpectedTransactionIdException();
        }
    }

    private class PollExecutor implements ModbusOperation<PollTask> {
        @Override
        public void accept(String operationId, PollTask task, ModbusSlaveConnection connection)
                throws ModbusException, ModbusTransportException {
            ModbusSlaveEndpoint endpoint = task.getEndpoint();
            ModbusReadRequestBlueprint request = task.getRequest();
            WeakReference<ModbusReadCallback> callback = task.getCallback();

            Optional<ModbusSlaveConnection> optionalConnection = Optional.of(connection);
            ModbusTransaction transaction = ModbusLibraryWrapper.createTransactionForEndpoint(endpoint,
                    optionalConnection);
            ModbusRequest libRequest = ModbusLibraryWrapper.createRequest(request);
            transaction.setRequest(libRequest);

            logger.trace("Going execute transaction with request (FC={}): {} [operation ID {}]",
                    request.getFunctionCode(), libRequest.getHexMessage(), operationId);
            // Might throw ModbusIOException (I/O error) or ModbusSlaveException (explicit exception response from
            // slave)
            transaction.execute();
            ModbusResponse response = transaction.getResponse();
            logger.trace("Response for read (FC={}, transaction ID={}): {} [operation ID {}]",
                    response.getFunctionCode(), response.getTransactionID(), response.getHexMessage(), operationId);
            checkTransactionId(response, libRequest, task, operationId);
            callbackThreadPool.execute(() -> {
                Optional.ofNullable(callback.get())
                        .ifPresent(cb -> ModbusLibraryWrapper.invokeCallbackWithResponse(request, cb, response));
            });
        }
    }

    private class WriteExecutor implements ModbusOperation<WriteTask> {
        @Override
        public void accept(String operationId, WriteTask task, ModbusSlaveConnection connection)
                throws ModbusException, ModbusTransportException {
            ModbusSlaveEndpoint endpoint = task.getEndpoint();
            ModbusWriteRequestBlueprint request = task.getRequest();
            WeakReference<ModbusWriteCallback> callback = task.getCallback();

            Optional<ModbusSlaveConnection> optionalConnection = Optional.of(connection);
            ModbusTransaction transaction = ModbusLibraryWrapper.createTransactionForEndpoint(endpoint,
                    optionalConnection);
            ModbusRequest libRequest = ModbusLibraryWrapper.createRequest(request);
            transaction.setRequest(libRequest);

            logger.trace("Going execute transaction with request (FC={}): {} [operation ID {}]",
                    request.getFunctionCode(), libRequest.getHexMessage(), operationId);

            // Might throw ModbusIOException (I/O error) or ModbusSlaveException (explicit exception response from
            // slave)
            transaction.execute();
            ModbusResponse response = transaction.getResponse();
            logger.trace("Response for read (FC={}, transaction ID={}): {} [operation ID {}]",
                    response.getFunctionCode(), response.getTransactionID(), response.getHexMessage(), operationId);

            checkTransactionId(response, libRequest, task, operationId);
            Optional.ofNullable(callback.get())
                    .ifPresent(cb -> invokeCallbackWithResponse(request, cb, new ModbusResponseImpl(response)));
        }
    }

    static final Logger logger = LoggerFactory.getLogger(ModbusManagerImpl.class);

    /**
     * Time to wait between connection passive+borrow, i.e. time to wait between
     * transactions
     * Default 60ms for TCP slaves, Siemens S7 1212 PLC couldn't handle faster
     * requests with default settings.
     */
    public static final long DEFAULT_TCP_INTER_TRANSACTION_DELAY_MILLIS = 60;

    /**
     * Time to wait between connection passive+borrow, i.e. time to wait between
     * transactions
     * Default 35ms for Serial slaves, motivation discussed
     * here https://community.openhab.org/t/connection-pooling-in-modbus-binding/5246/111?u=ssalonen
     */
    public static final long DEFAULT_SERIAL_INTER_TRANSACTION_DELAY_MILLIS = 35;

    public static final long MODBUS_POLLER_THREADS = 10;
    public static final long MODBUS_POLLER_CALLBACK_THREADS = 5;

    private static final String MODBUS_POLLER_THREAD_POOL_NAME = "MODBUS_POLLER_THREAD_POOL";
    private static final String MODBUS_POLLER_CALLBACK_THREAD_POOL_NAME = "MODBUS_POLLER_CALLBACK_THREAD_POOL";

    private static GenericKeyedObjectPoolConfig generalPoolConfig = new GenericKeyedObjectPoolConfig();

    static {
        // When the pool is exhausted, multiple calling threads may be simultaneously blocked waiting for instances to
        // become available. As of pool 1.5, a "fairness" algorithm has been implemented to ensure that threads receive
        // available instances in request arrival order.
        generalPoolConfig.setFairness(true);
        // Limit one connection per endpoint (i.e. same ip:port pair or same serial device).
        // If there are multiple read/write requests to process at the same time, block until previous one finishes
        generalPoolConfig.setBlockWhenExhausted(true);
        generalPoolConfig.setMaxTotalPerKey(1);

        // block infinitely when exhausted
        generalPoolConfig.setMaxWaitMillis(-1);

        // make sure we return connected connections from/to connection pool
        generalPoolConfig.setTestOnBorrow(true);
        generalPoolConfig.setTestOnReturn(true);

        // disable JMX
        generalPoolConfig.setJmxEnabled(false);
    }

    /**
     * We use connection pool to ensure that only single transaction is ongoing per each endpoint. This is especially
     * important with serial slaves but practice has shown that even many tcp slaves have limited
     * capability to handle many connections at the same time
     *
     * Relevant discussion at the time of implementation:
     * - https://community.openhab.org/t/modbus-connection-problem/6108/
     * - https://community.openhab.org/t/connection-pooling-in-modbus-binding/5246/
     */
    private static KeyedObjectPool<ModbusSlaveEndpoint, ModbusSlaveConnection> connectionPool;
    static ModbusSlaveConnectionFactoryImpl connectionFactory;

    private volatile Map<@NonNull PollTask, ScheduledFuture<?>> scheduledPollTasks = new ConcurrentHashMap<>();

    private static ExpressionThreadPoolExecutor scheduledThreadPoolExecutor;
    private static ExecutorService callbackThreadPool;

    private Collection<ModbusManagerListener> listeners = new CopyOnWriteArraySet<>();

    static {
        constructConnectionPool();
    }

    private static void constructConnectionPool() {
        connectionFactory = new ModbusSlaveConnectionFactoryImpl();
        connectionFactory.setDefaultPoolConfigurationFactory(endpoint -> {
            return endpoint.accept(new ModbusSlaveEndpointVisitor<EndpointPoolConfiguration>() {

                @Override
                public EndpointPoolConfiguration visit(ModbusTCPSlaveEndpoint modbusIPSlavePoolingKey) {
                    EndpointPoolConfiguration endpointPoolConfig = new EndpointPoolConfiguration();
                    endpointPoolConfig.setPassivateBorrowMinMillis(DEFAULT_TCP_INTER_TRANSACTION_DELAY_MILLIS);
                    endpointPoolConfig.setConnectMaxTries(Modbus.DEFAULT_RETRIES);
                    return endpointPoolConfig;
                }

                @Override
                public EndpointPoolConfiguration visit(ModbusSerialSlaveEndpoint modbusSerialSlavePoolingKey) {
                    EndpointPoolConfiguration endpointPoolConfig = new EndpointPoolConfiguration();
                    // never "disconnect" (close/open serial port) serial connection between borrows
                    endpointPoolConfig.setReconnectAfterMillis(-1);
                    endpointPoolConfig.setPassivateBorrowMinMillis(DEFAULT_SERIAL_INTER_TRANSACTION_DELAY_MILLIS);
                    endpointPoolConfig.setConnectMaxTries(Modbus.DEFAULT_RETRIES);
                    return endpointPoolConfig;
                }

                @Override
                public EndpointPoolConfiguration visit(ModbusUDPSlaveEndpoint modbusUDPSlavePoolingKey) {
                    EndpointPoolConfiguration endpointPoolConfig = new EndpointPoolConfiguration();
                    endpointPoolConfig.setPassivateBorrowMinMillis(DEFAULT_TCP_INTER_TRANSACTION_DELAY_MILLIS);
                    endpointPoolConfig.setConnectMaxTries(Modbus.DEFAULT_RETRIES);
                    return endpointPoolConfig;
                }
            });
        });

        GenericKeyedObjectPool<ModbusSlaveEndpoint, ModbusSlaveConnection> genericKeyedObjectPool = new GenericKeyedObjectPool<>(
                connectionFactory, generalPoolConfig);
        genericKeyedObjectPool.setSwallowedExceptionListener(new SwallowedExceptionListener() {

            @Override
            public void onSwallowException(Exception e) {
                logger.error("Connection pool swallowed unexpected exception: {}", e.getMessage());

            }
        });
        connectionPool = genericKeyedObjectPool;
    }

    private Optional<ModbusSlaveConnection> borrowConnection(ModbusSlaveEndpoint endpoint) {
        Optional<ModbusSlaveConnection> connection = Optional.empty();
        long start = System.currentTimeMillis();
        try {
            connection = Optional.ofNullable(connectionPool.borrowObject(endpoint));
        } catch (Exception e) {
            logger.warn("Error getting a new connection for endpoint {}. Error was: {} {}", endpoint,
                    e.getClass().getName(), e.getMessage());
        }
        logger.trace("borrowing connection (got {}) for endpoint {} took {} ms", connection, endpoint,
                System.currentTimeMillis() - start);
        return connection;
    }

    private void invalidate(ModbusSlaveEndpoint endpoint, Optional<ModbusSlaveConnection> connection) {
        if (!connection.isPresent()) {
            return;
        }
        connection.ifPresent(con -> {
            try {
                connectionPool.invalidateObject(endpoint, con);
            } catch (Exception e) {
                logger.warn("Error invalidating connection in pool for endpoint {}. Error was: {} {}", endpoint,
                        e.getClass().getName(), e.getMessage(), e);
            }
        });
    }

    private void returnConnection(ModbusSlaveEndpoint endpoint, Optional<ModbusSlaveConnection> connection) {
        connection.ifPresent(con -> {
            try {
                connectionPool.returnObject(endpoint, con);
            } catch (Exception e) {
                logger.warn("Error returning connection to pool for endpoint {}. Error was: {} {}", endpoint,
                        e.getClass().getName(), e.getMessage(), e);
            }
        });
        logger.trace("returned connection for endpoint {}", endpoint);
    }

    /**
     * Establishes connection to the endpoint specified by the task
     *
     * In case connection cannot be established, callback is called with {@link ModbusConnectionException}
     *
     * @param operationId id appened to log messages for identifying the operation
     * @param oneOffTask whether this is one-off, or execution of previously scheduled poll
     * @param task task representing the read or write operation
     * @return {@link ModbusSlaveConnection} to the endpoint as specified by the task, or empty {@link Optional} when
     *         connection cannot be established
     * @throws PollTaskUnregistered
     */
    private <R extends ModbusRequestBlueprint, C extends ModbusCallback, T extends TaskWithEndpoint<R, C>> Optional<ModbusSlaveConnection> getConnection(
            String operationId, boolean oneOffTask, @NonNull T task) throws PollTaskUnregistered {
        logger.trace("Executing task {} (oneOff={})! Waiting for connection [operation ID {}]", task, oneOffTask,
                operationId);
        long connectionBorrowStart = System.currentTimeMillis();
        ModbusCallback callback = task.getCallback().get();
        ModbusSlaveEndpoint endpoint = task.getEndpoint();

        ModbusRequestBlueprint request = task.getRequest();
        Optional<ModbusSlaveConnection> connection = borrowConnection(endpoint);
        logger.trace("Executing task {} (oneOff={})! Connection received in {} ms [operation ID {}]", task, oneOffTask,
                System.currentTimeMillis() - connectionBorrowStart, operationId);
        if (!connection.isPresent()) {
            logger.warn("Could not connect to endpoint {} -- aborting request {} [operation ID {}]", endpoint, request,
                    operationId);
            callbackThreadPool.execute(() -> {
                Optional.ofNullable(callback)
                        .ifPresent(cb -> invokeCallbackWithError(request, cb, new ModbusConnectionException(endpoint)));
            });
        }
        return connection;
    }

    private static <R> void invokeCallbackWithError(ModbusRequestBlueprint request, ModbusCallback callback,
            Exception error) {
        try {
            if (request instanceof ModbusReadRequestBlueprint) {
                ((ModbusReadCallback) callback).onError((ModbusReadRequestBlueprint) request, error);
            } else if (request instanceof ModbusWriteRequestBlueprint) {
                ((ModbusWriteCallback) callback).onError((ModbusWriteRequestBlueprint) request, error);
            } else {
                throw new IllegalStateException(String.format("Request %s or callback %s is of wrong type.",
                        request.getClass().getName(), callback.getClass().getName()));
            }
        } catch (

        Exception e) {
            logger.error("Unhandled exception in callback: {} {} with request {}", e.getClass().getName(),
                    e.getMessage(), request, e);
        }
    }

    private static void invokeCallbackWithResponse(ModbusWriteRequestBlueprint request, ModbusWriteCallback callback,
            org.openhab.io.transport.modbus.ModbusResponse response) {
        try {
            callback.onWriteResponse(request, response);
        } catch (Exception e) {
            logger.error("Unhandled exception in callback: {} {}", e.getClass().getName(), e.getMessage(), e);
        }
    }

    private void verifyTaskIsRegistered(PollTask task) throws PollTaskUnregistered {
        if (!this.scheduledPollTasks.containsKey(task)) {
            String msg = String.format("Poll task %s is unregistered", task);
            logger.debug(msg);
            throw new PollTaskUnregistered(msg);
        }
    }

    /**
     * Execute operation using a retry mechanism.
     *
     * This is a helper function for executing read and write operations and handling the exceptions in a common way.
     *
     * With some connection types, the connection is reseted (disconnected), and new connection is received from the
     * pool. This means that potentially other operations queuing for the connection can be executed in-between.
     *
     * With some other connection types, the operation is retried without reseting the connection type.
     *
     * @param task
     * @param oneOffTask
     * @param operation
     */
    private <R extends ModbusRequestBlueprint, C extends ModbusCallback, T extends TaskWithEndpoint<R, C>> void executeOperation(
            @NonNull T task, boolean oneOffTask, ModbusOperation<T> operation) {
        R request = task.getRequest();
        ModbusSlaveEndpoint endpoint = task.getEndpoint();
        WeakReference<C> callback = task.getCallback();
        int maxTries = task.getMaxTries();
        AtomicReference<Exception> lastError = new AtomicReference<>();

        if (maxTries <= 0) {
            throw new IllegalArgumentException("maxTries should be positive");
        }

        String operationId = UUID.randomUUID().toString();
        Optional<ModbusSlaveConnection> connection = Optional.empty();
        try {
            connection = getConnection(operationId, oneOffTask, task);
            if (!connection.isPresent()) {
                // Could not acquire connection, time to abort
                // Error logged already, error callback called as well
                return;
            }

            int tryIndex = 0;
            long lastTryMillis = System.currentTimeMillis();
            while (tryIndex < maxTries) {
                logger.trace("Try {} out of {} [operation ID {}]", tryIndex + 1, maxTries, operationId);
                if (!connection.isPresent()) {
                    // Retry with failed with failed re-connect
                    // logged already, callback called as well
                    return;
                }
                // Check poll task is still registered (this is all asynchronous)
                if (!oneOffTask && task instanceof PollTask) {
                    verifyTaskIsRegistered((PollTask) task);
                }
                if (tryIndex > 0) {
                    // When retrying with the same connection, let's ensure that enough time is between the retries
                    logger.trace("Ensuring that enough time passes before retrying again. Sleeping [operation ID {}]",
                            operationId);
                    ModbusSlaveConnectionFactoryImpl.waitAtleast(lastTryMillis,
                            connectionFactory.getEndpointPoolConfiguration(endpoint).getPassivateBorrowMinMillis());
                    logger.trace("Sleep ended [operation ID {}]", operationId);
                }
                if (callbackThreadPool == null) {
                    logger.debug("Manager has been shut down, aborting proecssing request {} [operation ID {}]",
                            request, operationId);
                    return;
                }

                try {
                    tryIndex++;
                    operation.accept(operationId, task, connection.get());
                    lastError.set(null);
                } catch (ModbusIOException e) {
                    lastError.set(new ModbusSlaveIOException(e));
                    // IO exception occurred, we re-establish new connection hoping it would fix the issue (e.g.
                    // broken pipe on write)
                    if (tryIndex < maxTries) {
                        // If tryIndex >= maxTries, the loop will end (note that tryIndex has been incremented
                        // already)
                        logger.warn(
                                "Try {} out of {} failed when executing request ({}). Will try again soon. Error was I/O error, so reseting the connection. Error details: {} {} [operation ID {}]",
                                tryIndex, maxTries, request, e.getClass().getName(), e.getMessage(), operationId);
                    } else {
                        logger.error(
                                "Last try {} failed when executing request ({}). Aborting. Error was I/O error, so reseting the connection. Error details: {} {} [operation ID {}]",
                                tryIndex, request, e.getClass().getName(), e.getMessage(), operationId);
                    }
                    invalidate(endpoint, connection);
                    // set the invalidated connection to "null" such that it is not returned to pool. Then get a new
                    // connection
                    connection = Optional.empty();
                    connection = getConnection(operationId, oneOffTask, task);
                    continue;
                } catch (ModbusSlaveException e) {
                    lastError.set(new ModbusSlaveErrorResponseException(e));
                    // Slave returned explicit error response, no reason to re-establish new connection
                    if (tryIndex < maxTries) {
                        // If tryIndex >= maxTries, the loop will end (note that tryIndex has been incremented
                        // already)
                        logger.warn(
                                "Try {} out of {} failed when executing request ({}). Will try again soon. Error was: {} {} [operation ID {}]",
                                tryIndex, maxTries, request, e.getClass().getName(), e.getMessage(), operationId);
                    } else {
                        logger.error(
                                "Last try {} failed when executing request ({}). Aborting. Error was: {} {} [operation ID {}]",
                                tryIndex, request, e.getClass().getName(), e.getMessage(), operationId);
                    }
                    continue;
                } catch (ModbusUnexpectedTransactionIdException e) {
                    lastError.set(e);
                    // transaction error details already logged
                    if (tryIndex < maxTries) {
                        // If tryIndex >= maxTries, the loop will end (note that tryIndex has been incremented
                        // already)
                        logger.warn(
                                "Try {} out of {} failed when executing request ({}). Will try again soon. The response transaction ID did not match the request. Reseting the connection. Error details: {} {} [operation ID {}]",
                                tryIndex, maxTries, request, e.getClass().getName(), e.getMessage(), operationId);
                    } else {
                        logger.error(
                                "Last try {} failed when executing request ({}). Aborting. The response transaction ID did not match the request. Reseting the connection. Error details: {} {} [operation ID {}]",
                                tryIndex, request, e.getClass().getName(), e.getMessage(), operationId);
                    }
                    invalidate(endpoint, connection);
                    // set the invalidated connection to "null" such that it is not returned to pool. Then get a new
                    // connection
                    connection = Optional.empty();
                    connection = getConnection(operationId, oneOffTask, task);
                    continue;
                } catch (Exception e) {
                    lastError.set(e);
                    // Some other (unexpected) exception occurred
                    logger.error(
                            "Last try {} failed when executing request ({}). Aborting. Error was unexpected error, so reseting the connection. Error details: {} {} [operation ID {}]",
                            tryIndex, request, e.getClass().getName(), e.getMessage(), operationId);
                    invalidate(endpoint, connection);
                    // set the invalidated connection to "null" such that it is not returned to pool. Then get a new
                    // connection
                    connection = Optional.empty();
                    connection = getConnection(operationId, oneOffTask, task);
                    continue;
                }
            }
            if (lastError.get() != null) {
                // All retries failed with some error
                callbackThreadPool.execute(() -> {
                    Optional.ofNullable(callback.get())
                            .ifPresent(cb -> invokeCallbackWithError(request, cb, lastError.get()));
                });
            }
        } catch (PollTaskUnregistered e) {
            logger.warn("Poll task was unregistered -- not executing/proceeding with the poll: {} [operation ID {}]",
                    e.getMessage(), operationId);
            return;
        } finally {
            returnConnection(endpoint, connection);
            logger.trace("Connection was returned to the pool, ending operation [operation ID {}]", operationId);
        }
    }

    @Override
    public ScheduledFuture<?> submitOneTimePoll(PollTask task) {
        long scheduleTime = System.currentTimeMillis();
        logger.debug("Scheduling one-off poll task {}", task);
        ScheduledFuture<?> future = scheduledThreadPoolExecutor.schedule(() -> {
            long millisInThreadPoolWaiting = System.currentTimeMillis() - scheduleTime;
            logger.debug("Will now execute one-off poll task {}, waited in thread pool for {}", task,
                    millisInThreadPoolWaiting);
            executeOperation(task, true, new PollExecutor());
        }, 0L, TimeUnit.MILLISECONDS);
        return future;
    }

    @Override
    public void registerRegularPoll(@NonNull PollTask task, long pollPeriodMillis, long initialDelayMillis) {
        scheduledPollTasks.compute(task, (prevTask, prevFuture) -> {
            ScheduledFuture<?> future = scheduledThreadPoolExecutor.scheduleAtFixedRate(() -> {
                logger.debug("Executing scheduled ({}ms) poll task {}", pollPeriodMillis, task);
                executeOperation(task, false, new PollExecutor());
            }, initialDelayMillis, pollPeriodMillis, TimeUnit.MILLISECONDS);

            // Unregister previous regular poll
            if (prevFuture != null) {
                unregisterRegularPoll(prevTask);
            }
            return future;
        });

    }

    @Override
    public boolean unregisterRegularPoll(PollTask task) {
        // cancel poller
        ScheduledFuture<?> future = scheduledPollTasks.remove(task);
        if (future == null) {
            // No such poll task
            logger.warn("Caller tried to unregister nonexisting poll task {}", task);
            return false;
        }
        logger.info("Unregistering regular poll task {} (interrupting if necessary)", task);

        // Make sure connections to this endpoint are closed when they are returned to pool (which
        // is usually pretty soon as transactions should be relatively short-lived)
        ModbusManagerImpl.connectionFactory.disconnectOnReturn(task.getEndpoint(), System.currentTimeMillis());

        future.cancel(true);
        scheduledThreadPoolExecutor.purge();

        logger.info("Poll task {} canceled", task);

        try {
            // Close all idle connections as well (they will be reconnected if necessary on borrow)
            if (connectionPool != null) {
                connectionPool.clear(task.getEndpoint());
            }
        } catch (Exception e) {
            logger.error("Could not clear poll task {} endpoint {}. Stack trace follows", task, task.getEndpoint(), e);
            return false;
        }

        return true;
    }

    @Override
    public ScheduledFuture<?> submitOneTimeWrite(WriteTask task) {
        long scheduleTime = System.currentTimeMillis();
        logger.debug("Scheduling one-off write task {}", task);
        ScheduledFuture<?> future = scheduledThreadPoolExecutor.schedule(() -> {
            long millisInThreadPoolWaiting = System.currentTimeMillis() - scheduleTime;
            logger.debug("Will now execute one-off write task {}, waited in thread pool for {}", task,
                    millisInThreadPoolWaiting);
            executeOperation(task, true, new WriteExecutor());
        }, 0L, TimeUnit.MILLISECONDS);
        return future;
    }

    public void setDefaultPoolConfigurationFactory(
            Function<ModbusSlaveEndpoint, EndpointPoolConfiguration> defaultPoolConfigurationFactory) {
        connectionFactory.setDefaultPoolConfigurationFactory(defaultPoolConfigurationFactory);
    }

    @Override
    public void setEndpointPoolConfiguration(ModbusSlaveEndpoint endpoint, EndpointPoolConfiguration configuration) {
        connectionFactory.setEndpointPoolConfiguration(endpoint, configuration);
        for (ModbusManagerListener listener : listeners) {
            listener.onEndpointPoolConfigurationSet(endpoint, configuration);
        }
    }

    @Override
    public EndpointPoolConfiguration getEndpointPoolConfiguration(ModbusSlaveEndpoint endpoint) {
        return connectionFactory.getEndpointPoolConfiguration(endpoint);
    }

    protected void activate(Map<String, Object> configProperties) {
        synchronized (this) {
            logger.info("Modbus manager activated");
            if (connectionPool == null) {
                constructConnectionPool();
            }
            if (scheduledThreadPoolExecutor == null) {
                scheduledThreadPoolExecutor = ExpressionThreadPoolManager
                        .getExpressionScheduledPool(MODBUS_POLLER_THREAD_POOL_NAME);
                scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);
            }
            if (callbackThreadPool == null) {
                callbackThreadPool = ThreadPoolManager.getPool(MODBUS_POLLER_CALLBACK_THREAD_POOL_NAME);
            }
            if (scheduledThreadPoolExecutor.isShutdown() || callbackThreadPool.isShutdown()) {
                logger.error("Thread pool(s) shut down! Aborting activation of ModbusMangerImpl");
                throw new IllegalStateException("Thread pool(s) shut down! Aborting activation of ModbusMangerImpl");
            }
        }
    }

    protected void deactivate() {
        synchronized (this) {
            if (connectionPool != null) {
                Set<@NonNull PollTask> polls = getRegisteredRegularPolls();
                for (PollTask task : polls) {
                    unregisterRegularPoll(task);
                }

                connectionPool.close();
                connectionPool = null;
            }
            logger.debug("Purging scheduledThreadPoolExecutor");
            scheduledThreadPoolExecutor.purge();
            // Note that it is not allowed to shutdown the executor, since they will be reused when
            // ExpressionThreadPoolManager.getExpressionScheduledPool is called

            scheduledThreadPoolExecutor = null;
            callbackThreadPool = null;
            connectionFactory = null;
            logger.info("Modbus manager deactivated");
        }
    }

    @Override
    public void addListener(ModbusManagerListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(ModbusManagerListener listener) {
        listeners.remove(listener);
    }

    @Override
    public Set<@NonNull PollTask> getRegisteredRegularPolls() {
        return this.scheduledPollTasks.keySet();
    }

}
