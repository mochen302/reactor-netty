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
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodecFactory;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.util.AsciiString;
import reactor.ipc.netty.ConnectionEvents;
import reactor.ipc.netty.NettyPipeline;

import java.util.List;

import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http2.Http2CodecUtil.connectionPrefaceBuf;

/**
 * @author Violeta Georgieva
 * @since 0.8
 */
final class CleartextHttp2ServerUpgradeHandler extends ChannelHandlerAdapter {
	static final ByteBuf CONNECTION_PREFACE = unreleasableBuffer(connectionPrefaceBuf());
	final ConnectionEvents listener;
	final HttpServerCodec httpServerCodec;
	final HttpServerUpgradeHandler httpServerUpgradeHandler;
	final Http2ServerHandler http2ServerHandler;

	public CleartextHttp2ServerUpgradeHandler(ConnectionEvents listener) {
		this.listener = listener;
		httpServerCodec = new HttpServerCodec();
		http2ServerHandler = new Http2ServerHandlerBuilder().build();
		httpServerUpgradeHandler =
				new HttpServerUpgradeHandler(httpServerCodec, new UpgradeCodecFactoryImpl());
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		ctx.pipeline()
		   .addBefore(ctx.name(), null, new PriorKnowledgeHandler())
		   .addBefore(ctx.name(), NettyPipeline.HttpCodec, httpServerCodec)
		   .replace(this, null, httpServerUpgradeHandler);

		ctx.read();
	}

	private final class PriorKnowledgeHandler extends ByteToMessageDecoder {
		@Override
		protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
			int prefaceLength = CONNECTION_PREFACE.readableBytes();
			int bytesRead = Math.min(in.readableBytes(), prefaceLength);

			if (!ByteBufUtil.equals(CONNECTION_PREFACE, CONNECTION_PREFACE.readerIndex(),
					in, in.readerIndex(), bytesRead)) {
				ctx.pipeline().remove(this);
			} else if (bytesRead == prefaceLength) {
				ctx.pipeline()
				   .remove(httpServerCodec)
				   .remove(httpServerUpgradeHandler);

				ctx.pipeline().addAfter(ctx.name(), NettyPipeline.Http2ServerHandler, http2ServerHandler);
				ctx.pipeline().remove(this);

				ctx.fireUserEventTriggered(PriorKnowledgeUpgradeEvent.INSTANCE);
			}
		}
	}

	static final class PriorKnowledgeUpgradeEvent {
		private static final PriorKnowledgeUpgradeEvent INSTANCE = new PriorKnowledgeUpgradeEvent();

		private PriorKnowledgeUpgradeEvent() {
		}
	}

	final class UpgradeCodecFactoryImpl implements UpgradeCodecFactory {

		@Override
		public UpgradeCodec newUpgradeCodec(CharSequence protocol) {
			if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
				return new Http2ServerUpgradeCodec(http2ServerHandler);
			} else {
				return null;
			}
		}
	}
}
