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
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionEvents;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.channel.Http2Message;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
class Http2StreamOperations extends ChannelOperations<Http2StreamInbound, Http2StreamOutbound>
		implements Http2StreamInbound, Http2StreamOutbound {

	final static AtomicIntegerFieldUpdater<Http2StreamOperations> HTTP_STATE =
			AtomicIntegerFieldUpdater.newUpdater(Http2StreamOperations.class,
					"statusAndHeadersSent");
	volatile int statusAndHeadersSent = 0;

	static final int READY        = 0;
	static final int HEADERS_SENT = 1;
	static final int BODY_SENT    = 2;

	final Http2Headers nettyRequest;
	final Http2Headers nettyResponse;
	final Integer streamId;

	Http2StreamOperations(Connection connection, ConnectionEvents listener, Http2Headers headers, Integer streamId) {
		super(connection, listener);
		this.nettyRequest = headers;
		this.streamId = streamId;
		this.nettyResponse = new DefaultHttp2Headers().status(OK.codeAsText());
	}

	/**
	 * Has headers been sent
	 *
	 * @return true if headers have been sent
	 */
	public final boolean hasSentHeaders() {
		return statusAndHeadersSent != READY;
	}

	@Override
	public int streamId() {
		if (streamId != null) {
			return streamId;
		}
		return -1;
	}

	/**
	 * Outbound Netty Http2Headers
	 *
	 * @return Outbound Netty Http2Headers
	 */
	protected Http2Headers outboundHttpMessage() {
		return nettyResponse;
	}

	@Override
	public final boolean isDisposed() {
		return ((Http2ServerOperations) get(channel())).streamsCache.get(streamId) != this;
	}

	@Override
	protected void onOutboundComplete() {
		final ChannelFuture f;
		if (log.isDebugEnabled()) {
			log.debug("Last HTTP response frame");
		}
		if (markSentHeaderAndBody()) {
			if (log.isDebugEnabled()) {
				log.debug("No sendHeaders() called before complete, sending zero-length header");
			}

			f = channel().writeAndFlush(new Http2Message(streamId, new DefaultHttp2Headers().status(status()), true));
		}
		else if (markSentBody()) {
			f = channel().writeAndFlush(new Http2Message(streamId, EMPTY_BUFFER, true));
		}
		else{
			discard();
			return;
		}
		f.addListener(s -> {
			discard();
			if (!s.isSuccess() && log.isDebugEnabled()) {
				log.error(channel()+" Failed flushing last frame", s.cause());
			}
		});

	}

	protected void onInboundNext(ChannelHandlerContext ctx, Object msg, boolean endOfStream) {
		if (msg instanceof ByteBuf) {
			super.onInboundNext(ctx, msg);
		}
		if (endOfStream) {
			onInboundComplete();
		}
	}

	@Override
	public Mono<Void> then() {
		if (markSentHeaders()) {
			return FutureMono.deferFuture(() -> channel().writeAndFlush(new Http2Message(streamId, outboundHttpMessage(), false)));
		}
		else {
			return Mono.empty();
		}
	}

	@Override
	public NettyOutbound sendObject(Object message) {
		return then(FutureMono.deferFuture(() -> channel().writeAndFlush(new Http2Message(streamId, message, false))));
	}

	@Override
	public CharSequence status() {
		return this.nettyResponse.status();
	}

	@Override
	public Http2StreamOutbound status(CharSequence status) {
		if (!hasSentHeaders()) {
			this.nettyResponse.status(status);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	/**
	 * Mark the headers sent
	 *
	 * @return true if marked for the first time
	 */
	protected final boolean markSentHeaders() {
		return HTTP_STATE.compareAndSet(this, READY, HEADERS_SENT);
	}

	/**
	 * Mark the body sent
	 *
	 * @return true if marked for the first time
	 */
	protected final boolean markSentBody() {
		return HTTP_STATE.compareAndSet(this, HEADERS_SENT, BODY_SENT);
	}

	/**
	 * Mark the headers and body sent
	 *
	 * @return true if marked for the first time
	 */
	protected final boolean markSentHeaderAndBody() {
		return HTTP_STATE.compareAndSet(this, READY, BODY_SENT);
	}

	static final Logger log = Loggers.getLogger(Http2StreamOperations.class);
}
