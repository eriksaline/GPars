package org.gparallelizer.dataflow;

import org.gparallelizer.MessageStream;
import org.codehaus.groovy.runtime.InvokerHelper;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import groovy.lang.*;

/**
 * @author Alex Tkachman
 */
public abstract class DataFlowExpression<T> extends GroovyObjectSupport {

    /**
     * Holds the actual value. Is null before a concrete value is bound to it.
     */
    @SuppressWarnings({"InstanceVariableMayNotBeInitialized"})
    protected volatile T value;

    /**
     * Holds the current state of the variable
     */
    protected final AtomicInteger state = new AtomicInteger();

    /**
     * Points to the head of the chain of requests waiting for a value to be bound
     */
    private final AtomicReference<WaitingThread<T>> waiting = new AtomicReference<WaitingThread<T>>();

    protected static final int S_NOT_INITIALIZED = 0;
    protected static final int S_INITIALIZING = 1;
    protected static final int S_INITIALIZED = 2;
    private static final int CHANNEL_INDEX_NOT_REQUIRED = -1;

    /**
     * A logical representation of a synchronous or asynchronous request to read the value once it is bound.
     *
     * @param <V> The type of the value to bind
     */
    private static class WaitingThread<V> {
        private final Thread thread;
        private volatile WaitingThread<V> previous;
        private final MessageStream callback;
        private final Integer index;

        /**
         * Creates a representation of the request to read the value once it is bound
         * @param thread The physical thread of the request, which will be suspended
         * @param previous The previous request in the chain of requests
         * @param index A logical identifier to match the original value request with a reply in the operators, which receive values asynchronously
         * @param callback An actor or operator to send a message to once a value is bound
         */
        private WaitingThread(final Thread thread, final WaitingThread<V> previous, final Integer index, final MessageStream callback) {
            this.callback = callback;
            this.index = index;
            this.thread = thread;
            this.previous = previous;
        }
    }

    /**
     * A request chain terminator
     */
    private static final WaitingThread dummyWaitingThread = new WaitingThread<Object>(null, null, null, null);

    /**
     * Creates a new unbound Dataflow Expression
     */
    public DataFlowExpression() {
        state.set(S_NOT_INITIALIZED);
    }

    /**
     * Asynchronously retrieves the value of the variable. Sends the actual value of the variable as a message
     * back the the supplied actor once the value has been bound.
     * The actor can perform other activities or release a thread back to the pool by calling react() waiting for the message
     * with the value of the Dataflow Variable.
     * @param callback An actor to send the bound value to.
     */
    public void getValAsync(final MessageStream callback) {
        getValAsync(CHANNEL_INDEX_NOT_REQUIRED, callback);
    }

    /**
     * Used by Dataflow operators.
     * Asynchronously retrieves the value of the variable. Sends a message back the the supplied actor
     * with a map holding the supplied index under the 'index' key and the actual value of the variable under
     * the 'result' key once the value has been bound.
     * Index is an arbitrary value helping the actor.operator match its request with the reply.
     * The actor/operator can perform other activities or release a thread back to the pool by calling react() waiting for the message
     * with the value of the Dataflow Variable.
     * @param index An arbitrary value to identify operator channels and so match requests and replies
     * @param callback An actor to send the bound value plus the supplied index to.
     */
    void getValAsync(final Integer index, final MessageStream callback) {
        WaitingThread<T> newWaiting = null;
        while (state.get() != S_INITIALIZED) {
            if (newWaiting == null) {
                newWaiting = new WaitingThread<T>(null, null, index, callback);
            }

            final WaitingThread<T> previous = waiting.get();
            // it means that writer already started processing queue, so value is already in place
            if (previous == dummyWaitingThread) {
                break;
            }

            newWaiting.previous = previous;
            if (waiting.compareAndSet(previous, newWaiting)) {
                // ok, we are in the queue, so writer is responsible to process us
                return;
            }
        }

        scheduleCallback(index, callback);
    }

    /**
     * Reads the value of the variable. Blocks, if the value has not been assigned yet.
     *
     * @return The actual value
     * @throws InterruptedException If the current thread gets interrupted while waiting for the variable to be bound
     */
    public T getVal() throws InterruptedException {
        WaitingThread<T> newWaiting = null;
        while (state.get() != S_INITIALIZED) {
            if (newWaiting == null) {
                newWaiting = new WaitingThread<T>(Thread.currentThread(), null, null, null);
            }

            final WaitingThread<T> previous = waiting.get();
            // it means that writer already started processing queue, so value is already in place
            if (previous == dummyWaitingThread) {
                break;
            }

            newWaiting.previous = previous;
            if (waiting.compareAndSet(previous, newWaiting)) {
                // ok, we are in the queue, so writer is responsible to process us
                while (state.get() != S_INITIALIZED) {
                    LockSupport.park();
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                }
                break;
            }
        }

        return value;
    }

    /**
     * Performs the actual bind operation, unblocks all blocked threads and informs all asynchronously waiting actors.
     * @param value The value to assign
     */
    protected void doBind(final T value) {
        this.value = value;
        state.set(S_INITIALIZED);

        @SuppressWarnings({"unchecked"})
        final WaitingThread<T> waitingQueue = waiting.getAndSet(dummyWaitingThread);
        // no more new waiting threads since that point

        // no more new waiting threads since that point
        for (WaitingThread<T> waiting = waitingQueue; waiting != null; waiting = waiting.previous) {
            if (waiting.thread != null) {
                LockSupport.unpark(waiting.thread);  //can be potentially called on a not parked thread
            } else {
                scheduleCallback(waiting.index, waiting.callback);
            }
        }
    }

    /**
     * Sends the result back to the actor, which is waiting asynchronously for the value to be bound.
     * The message will either be a map holding the index under the 'index' key and the actual bound value under the 'result' key,
     * or it will be the result itself if the callback doesn't care about the index.
     * @param index The index associated with the original request (identifies operator's channels)
     * @param callback The actor to send the message to
     */
    @SuppressWarnings({"TypeMayBeWeakened"})
    private void scheduleCallback(final Integer index, final MessageStream callback) {
        if (index == CHANNEL_INDEX_NOT_REQUIRED) {
            callback.send(value);
        } else {
            final Map<String, Object> message = new HashMap<String, Object>();
            message.put("index", index);
            message.put("result", value);
            callback.send(message);
        }
    }

    /**
     * Schedule closure to be executed by pooled actor after data became available
     * It is important to notice that even if data already available the execution of closure
     * will not happen immediately but will be scheduled
     *
     * @param closure closure to execute when data available
     */
    public void rightShift(final Closure closure) {
        whenBound(closure);
    }

    /**
     * Schedule closure to be executed by pooled actor after data became available
     * It is important to notice that even if data already available the execution of closure
     * will not happen immediately but will be scheduled.
     *
     * @param closure closure to execute when data available
     */
    public void whenBound(final Closure closure) {
        getValAsync(new DataCallback(closure));
    }

    /**
     * Schedule closure to be executed by pooled actor after data became available
     * It is important to notice that even if data already available the execution of closure
     * will not happen immediately but will be scheduled.
     * Used by the DataFlowStream class, since it cannot pass closures directly.
     *
     * @param closure An object with a method matching the 'perform(Object value)' signature
     */
    void whenBound(final Object closure) {
        getValAsync(new DataCallback(closure));
    }

    protected abstract void collectVariables(Set<DataFlowVariable> collection);

    public Object invokeMethod(String name, Object args) {
        if (!getMetaClass().respondsTo(this, name).isEmpty())
            return InvokerHelper.invokeMethod(this, name, args);

        return new DataFlowInvocationExpression(this, name, (Object[]) args);
    }

    public Object getProperty(String propertyName) {
        return getMetaClass().getProperty(this, propertyName);
    }

    public void setProperty(String propertyName, Object newValue) {
        getMetaClass().setProperty(this, propertyName, newValue);
    }

    public void setMetaClass(MetaClass metaClass) {
        throw new UnsupportedOperationException();
    }
}