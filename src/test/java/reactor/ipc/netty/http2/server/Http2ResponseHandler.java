/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.http2.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.PlatformDependent;
import org.assertj.core.api.Assertions;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.AbstractMap.SimpleEntry;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
final class Http2ResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

	static final Logger LOGGER = Loggers.getLogger(Http2ServerBaseTests.class);
	final Map<Integer, Entry<ChannelFuture, ChannelPromise>> streamIdPromiseMap;
	final String expectation;

	Http2ResponseHandler(String expectation) {
		this.streamIdPromiseMap = PlatformDependent.newConcurrentHashMap();
		this.expectation = expectation;
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) throws Exception {
		Integer streamId = msg.headers().getInt(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());
		if (streamId == null) {
			LOGGER.error("{} Http2ResponseHandler unexpected message received: {}", ctx.channel(), msg);
			return;
		}

		Entry<ChannelFuture, ChannelPromise> entry = streamIdPromiseMap.get(streamId);
		if (entry == null) {
			LOGGER.error("{} Message received for unknown stream id {}", ctx.channel(), streamId);
		} else {
			ByteBuf content = msg.content();
			if (content.isReadable()) {
				int contentLength = content.readableBytes();
				byte[] arr = new byte[contentLength];
				content.readBytes(arr);
				Assertions.assertThat(new String(arr, 0, contentLength, CharsetUtil.UTF_8)).isEqualTo(expectation);
			}

			entry.getValue().setSuccess();
		}
	}

	final Entry<ChannelFuture, ChannelPromise> put(int streamId, ChannelFuture writeFuture, ChannelPromise promise) {
		return streamIdPromiseMap.put(streamId, new SimpleEntry<>(writeFuture, promise));
	}

	final void awaitHttp2Responses(long timeout, TimeUnit unit) {
		Iterator<Entry<Integer, Entry<ChannelFuture, ChannelPromise>>> it = streamIdPromiseMap.entrySet().iterator();
		while (it.hasNext()) {
			Entry<Integer, Entry<ChannelFuture, ChannelPromise>> entry = it.next();
			ChannelFuture writeFuture = entry.getValue().getKey();
			if (!writeFuture.awaitUninterruptibly(timeout, unit)) {
				throw new IllegalStateException("Timed out while waiting to write for stream id " + entry.getKey());
			}
			if (!writeFuture.isSuccess()) {
				throw new RuntimeException(writeFuture.cause());
			}
			ChannelPromise promise = entry.getValue().getValue();
			if (!promise.awaitUninterruptibly(timeout, unit)) {
				throw new IllegalStateException("Timed out while waiting for response on stream id " + entry.getKey());
			}
			if (!promise.isSuccess()) {
				throw new RuntimeException(promise.cause());
			}
			LOGGER.info("Stream id: {} received", entry.getKey());
			it.remove();
		}
	}
}
