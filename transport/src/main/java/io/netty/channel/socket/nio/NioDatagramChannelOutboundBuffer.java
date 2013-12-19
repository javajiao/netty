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
package io.netty.channel.socket.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.AbstractNioChannelOutboundBuffer;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;

final class NioDatagramChannelOutboundBuffer extends AbstractNioChannelOutboundBuffer {

    private static final Recycler<NioDatagramChannelOutboundBuffer> RECYCLER =
            new Recycler<NioDatagramChannelOutboundBuffer>() {
                @Override
                protected NioDatagramChannelOutboundBuffer newObject(Handle<NioDatagramChannelOutboundBuffer> handle) {
                    return new NioDatagramChannelOutboundBuffer(handle);
                }
            };

    static NioDatagramChannelOutboundBuffer newInstance(NioDatagramChannel channel) {
        NioDatagramChannelOutboundBuffer buffer = RECYCLER.get();
        buffer.channel = channel;
        return buffer;
    }

    private NioDatagramChannelOutboundBuffer(Handle<? extends ChannelOutboundBuffer> handle) {
        super(handle);
    }

    @Override
    protected void addMessage(Object msg, ChannelPromise promise) {
        if (msg instanceof DatagramPacket) {
            DatagramPacket packet = (DatagramPacket) msg;
            ByteBuf content = packet.content();
            if (isCopyNeeded(content)) {
                ByteBuf buf = toDirect(promise.channel(), content);
                msg = new DatagramPacket(buf, packet.recipient(), packet.sender());
            }
        } else if (msg instanceof ByteBuf) {
            ByteBuf content = (ByteBuf) msg;
            if (isCopyNeeded(content)) {
                msg = toDirect(promise.channel(), content);
            }
        }
        super.addMessage(msg, promise);
    }

    private static boolean isCopyNeeded(ByteBuf buf) {
        return !buf.isDirect() || buf.nioBufferCount() > 1;
    }
}
