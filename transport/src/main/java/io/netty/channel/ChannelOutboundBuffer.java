/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

public abstract class ChannelOutboundBuffer {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelOutboundBuffer.class);

    protected AbstractChannel channel;
    private boolean inFail;
    private static final AtomicLongFieldUpdater<ChannelOutboundBuffer> TOTAL_PENDING_SIZE_UPDATER =
            AtomicLongFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "totalPendingSize");

    private volatile long totalPendingSize;
    private static final AtomicIntegerFieldUpdater<ChannelOutboundBuffer> WRITABLE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "writable");
    private volatile int writable = 1;

    protected ChannelOutboundBuffer(AbstractChannel channel) {
        this.channel = channel;
    }

    protected ChannelOutboundBuffer() {
    }

    /**
     * Increment the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    protected final void incrementPendingOutboundBytes(int size) {
        // Cache the channel and check for null to make sure we not produce a NPE in case of the Channel gets
        // recycled while process this method.
        Channel channel = this.channel;
        if (size == 0 || channel == null) {
            return;
        }

        long oldValue = totalPendingSize;
        long newWriteBufferSize = oldValue + size;
        while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
            oldValue = totalPendingSize;
            newWriteBufferSize = oldValue + size;
        }

        int highWaterMark = channel.config().getWriteBufferHighWaterMark();

        if (newWriteBufferSize > highWaterMark) {
            if (WRITABLE_UPDATER.compareAndSet(this, 1, 0)) {
                channel.pipeline().fireChannelWritabilityChanged();
            }
        }
    }

    /**
     * Decrement the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    protected final void decrementPendingOutboundBytes(long size) {
        // Cache the channel and check for null to make sure we not produce a NPE in case of the Channel gets
        // recycled while process this method.
        Channel channel = this.channel;
        if (size == 0 || channel == null) {
            return;
        }

        long oldValue = totalPendingSize;
        long newWriteBufferSize = oldValue - size;
        while (!TOTAL_PENDING_SIZE_UPDATER.compareAndSet(this, oldValue, newWriteBufferSize)) {
            oldValue = totalPendingSize;
            newWriteBufferSize = oldValue - size;
        }

        int lowWaterMark = channel.config().getWriteBufferLowWaterMark();

        if (newWriteBufferSize == 0 || newWriteBufferSize < lowWaterMark) {
            if (WRITABLE_UPDATER.compareAndSet(this, 0, 1)) {
                channel.pipeline().fireChannelWritabilityChanged();
            }
        }
    }

    protected static long total(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }

    protected static boolean isVoidPromise(ChannelPromise promise) {
        return promise instanceof VoidChannelPromise;
    }

    final boolean getWritable() {
        return writable != 0;
    }

    public final boolean isEmpty() {
        return size() == 0;
    }

    final void failFlushed(Throwable cause) {
        // Make sure that this method does not reenter.  A listener added to the current promise can be notified by the
        // current thread in the tryFailure() call of the loop below, and the listener can trigger another fail() call
        // indirectly (usually by closing the channel.)
        //
        // See https://github.com/netty/netty/issues/1501
        if (inFail) {
            return;
        }

        try {
            inFail = true;
            for (;;) {
                if (!remove(cause)) {
                    break;
                }
            }
        } finally {
            inFail = false;
        }
    }

    final void close(final ClosedChannelException cause) {
        if (inFail) {
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    close(cause);
                }
            });
            return;
        }

        inFail = true;

        if (channel.isOpen()) {
            throw new IllegalStateException("close() must be invoked after the channel is closed.");
        }

        if (!isEmpty()) {
            throw new IllegalStateException("close() must be invoked after all flushed writes are handled.");
        }

        // Release all unflushed messages.
        try {
            failUnflushed(cause);
        } finally {
            inFail = false;
        }
        recycle();
    }

    protected static void safeRelease(Object message) {
        try {
            ReferenceCountUtil.release(message);
        } catch (Throwable t) {
            logger.warn("Failed to release a message.", t);
        }
    }

    protected static void safeFail(ChannelPromise promise, Throwable cause) {
        if (!isVoidPromise(promise) && !promise.tryFailure(cause)) {
            logger.warn("Promise done already: {} - new exception is:", promise, cause);
        }
    }

    private void recycle() {
        channel = null;
        totalPendingSize = 0;
        writable = 1;

        onRecycle();
    }

    final void addMessage(Object msg, ChannelPromise promise) {
        int size = channel.estimatorHandle().size(msg);
        if (size < 0) {
            size = 0;
        }
        addMessage(msg, size, promise);

        // increment pending bytes after adding message to the unflushed arrays.
        // See https://github.com/netty/netty/issues/1619
        incrementPendingOutboundBytes(size);
    }

    protected void onRecycle() { }
    protected abstract void addMessage(Object msg, int pendingSize, ChannelPromise promise);
    protected abstract void addFlush();
    protected abstract void failUnflushed(Throwable cause);
    public abstract int size();
    public abstract Object current();
    public abstract void progress(long amount);
    public abstract boolean remove();
    public abstract boolean remove(Throwable cause);

}
