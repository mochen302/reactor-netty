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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2Headers;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionEvents;

import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
final class Http2ServerOperations extends Http2StreamOperations {

	@SuppressWarnings("unchecked")
	static Http2ServerOperations bindHttp2(Connection connection, ConnectionEvents listener) {
		return new Http2ServerOperations(connection, listener);
	}

	final DirectProcessor<Http2StreamOutbound> streams = DirectProcessor.create();
	final ConcurrentHashMap<Integer, Http2StreamOperations> streamsCache = new ConcurrentHashMap<>();
	final Http2FrameListener http2FrameListener;

	Http2ServerOperations(Connection c, ConnectionEvents listener) {
		super(c, listener, null, -1);
		streamsCache.put(-1, this);
		http2FrameListener = new Http2FrameAdapter() {

			@Override
			public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
				Http2StreamOperations http2StreamOperations = streamsCache.get(streamId);
				http2StreamOperations.onInboundNext(ctx, data, endOfStream);
				return data.readableBytes() + padding;
			}

			@Override
			public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding, boolean endOfStream) {
				Http2StreamOperations http2StreamOperations = streamsCache.get(streamId);
				if (http2StreamOperations == null) {
					http2StreamOperations = new Http2StreamOperations(connection(), listener(), headers, streamId);
					streamsCache.putIfAbsent(streamId, http2StreamOperations);
				}
				streams.onNext(http2StreamOperations);
				http2StreamOperations.onInboundNext(ctx, headers, endOfStream);
			}

			@Override
			public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency,
					short weight, boolean exclusive, int padding, boolean endOfStream) {
				onHeadersRead(ctx, streamId, headers, padding, endOfStream);
			}
		};
	}

	Flux<Http2StreamOutbound> streams() {
		return streams;
	}

	Http2FrameListener http2FrameListener() {
		return http2FrameListener;
	}
}
