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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.tcp.SslProvider;
import reactor.ipc.netty.tcp.TcpServer;
import reactor.util.Logger;
import reactor.util.Loggers;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An Http2Server allows to build in a safe immutable way an HTTP2 server that is
 * materialized and connecting when {@link #bind(TcpServer)} is ultimately called.
 * <p> Internally, materialization happens in three phases, first {@link
 * #tcpConfiguration()} is called to retrieve a ready to use {@link TcpServer}, then
 * {@link Http2Server#tcpConfiguration()} ()} retrieve a usable {@link TcpServer} for the final
 * {@link #bind(TcpServer)} is called. <p> Examples:
 * <pre>
 * {@code
 * Http2Server.create()
 *            .tcpConfiguration(TcpServer::secure)
 *            .handler(stream -> stream.sendString(Flux.just("hello"))
 *            .bind()
 *            .block();
 * }
 *
 * @author Violeta Georgieva
 * @since 0.8
 */
public abstract class Http2Server {

	/**
	 * Prepare a pooled {@link Http2Server}
	 *
	 * @return a {@link Http2Server}
	 */
	public static Http2Server create() {
		return Http2ServerBind.INSTANCE;
	}

	/**
	 * Prepare a pooled {@link Http2Server}
	 *
	 * @return a {@link Http2Server}
	 */
	public static Http2Server from(TcpServer tcpServer) {
		return new Http2ServerBind(tcpServer);
	}

	/**
	 * Bind the {@link Http2Server} and return a {@link Mono} of {@link DisposableServer}. If
	 * {@link Mono} is cancelled, the underlying binding will be aborted. Once the {@link
	 * DisposableServer} has been emitted and is not necessary anymore, disposing main server
	 * loop must be done by the user via {@link DisposableServer#dispose()}.
	 *
	 * If updateConfiguration phase fails, a {@link Mono#error(Throwable)} will be returned;
	 *
	 * @return a {@link Mono} of {@link DisposableServer}
	 */
	public final Mono<? extends DisposableServer> bind() {
		return bind(tcpConfiguration());
	}

	/**
	 * Start a Server in a blocking fashion, and wait for it to finish initializing. The
	 * returned {@link DisposableServer} offers simple server API, including to {@link
	 * DisposableServer#disposeNow()} shut it down in a blocking fashion.
	 *
	 * @return a {@link DisposableServer}
	 */
	public final DisposableServer bindNow() {
		return bindNow(Duration.ofSeconds(45));
	}


	/**
	 * Start a Server in a blocking fashion, and wait for it to finish initializing. The
	 * returned {@link DisposableServer} offers simple server API, including to {@link
	 * DisposableServer#disposeNow()} shut it down in a blocking fashion.
	 *
	 * @param timeout max startup timeout
	 *
	 * @return a {@link DisposableServer}
	 */
	public final DisposableServer bindNow(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		return Objects.requireNonNull(bind().block(timeout), "aborted");
	}

	/**
	 * Start a Server in a fully blocking fashion, not only waiting for it to initialize
	 * but also blocking during the full lifecycle of the client/server. Since most
	 * servers will be long-lived, this is more adapted to running a server out of a main
	 * method, only allowing shutdown of the servers through sigkill.
	 * <p>
	 * Note that a {@link Runtime#addShutdownHook(Thread) JVM shutdown hook} is added by
	 * this method in order to properly disconnect the client/server upon receiving a
	 * sigkill signal.
	 *
	 * @param timeout a timeout for server shutdown
	 * @param onStart an optional callback on server start
	 */
	public final void bindUntilJavaShutdown(Duration timeout,
											@Nullable Consumer<DisposableServer> onStart) {

		Objects.requireNonNull(timeout, "timeout");
		DisposableServer facade = bindNow();

		Objects.requireNonNull(facade, "facade");

		if (onStart != null) {
			onStart.accept(facade);
		}
		Runtime.getRuntime()
		       .addShutdownHook(new Thread(() -> facade.disposeNow(timeout)));

		facade.onDispose()
		      .block();
	}

	/**
	 * The port to which this server should bind.
	 *
	 * @param port The port to bind to.
	 *
	 * @return a new {@link Http2Server}
	 */
	public final Http2Server port(int port) {
		return tcpConfiguration(tcpServer -> tcpServer.port(port));
	}

	/**
	 * Apply a wire logger configuration using {@link Http2Server} category
	 *
	 * @return a new {@link Http2Server}
	 */
	public final Http2Server wiretap() {
		return tcpConfiguration(tcpServer ->
		       tcpServer.bootstrap(b -> BootstrapHandlers.updateLogSupport(b, LOGGING_HANDLER)));
	}

	/**
	 * Enable default sslContext support. The default {@link SslContext} will be assigned
	 * to with a default value of {@literal 10} seconds handshake timeout unless the
	 * environment property {@literal reactor.ipc.netty.sslHandshakeTimeout} is set.
	 *
	 * @return a new {@link Http2Server}
	 */
	public final Http2Server secure() {
		return tcpConfiguration(tcp -> tcp.secure(SSL_DEFAULT_SPEC_HTTP2));
	}

	/**
	 * Attach an IO handler to react on connected server
	 *
	 * @param handler an IO handler that can dispose underlying connection when {@link
	 * Publisher} terminates. Only the first registered handler will subscribe to the
	 * returned {@link Publisher} while other will immediately cancel given a same
	 * {@link Connection}
	 *
	 * @return a new {@link Http2Server}
	 */
	public final Http2Server handler(BiFunction<? super Http2StreamInbound, ? super Http2StreamOutbound, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return tcpConfiguration(tcp -> tcp.doOnConnection(c -> {
			((Http2ServerOperations) c).streams().subscribe(s -> {
				if (log.isDebugEnabled()) {
					log.debug("{} HTTP/2 handler is being applied: {}", c.channel(), handler);
				}

				Mono.fromDirect(handler.apply((Http2StreamInbound) s, (Http2StreamOutbound) s))
					.subscribe(((Connection) s).disposeSubscriber());
			});
		}));
	}

	/**
	 * Apply {@link ServerBootstrap} configuration given mapper taking currently
	 * configured one and returning a new one to be ultimately used for socket binding.
	 * <p> Configuration will apply during {@link #tcpConfiguration()} phase.
	 *
	 * @param tcpMapper A tcpServer mapping function to update tcp configuration and
	 * return an enriched tcp server to use.
	 *
	 * @return a new {@link Http2Server}
	 */
	public final Http2Server tcpConfiguration(Function<? super TcpServer, ? extends TcpServer> tcpMapper) {
		return new Http2ServerTcpConfig(this, tcpMapper);
	}

	/**
	 * Bind the {@link Http2Server} and return a {@link Mono} of {@link DisposableServer}
	 *
	 * @param b the {@link TcpServer} to bind
	 *
	 * @return a {@link Mono} of {@link DisposableServer}
	 */
	protected abstract Mono<? extends DisposableServer> bind(TcpServer b);

	/**
	 * Materialize a TcpServer from the parent {@link Http2Server} chain to use with
	 * {@link #bind(TcpServer)} or separately
	 *
	 * @return a configured {@link TcpServer}
	 */
	protected TcpServer tcpConfiguration() {
		return DEFAULT_TCP_SERVER;
	}


	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(Http2Server.class);
	static final Logger         log             = Loggers.getLogger(Http2Server.class);

	static final ChannelOperations.OnSetup HTTP2_OPS =
			(ch, c, msg) -> Http2ServerOperations.bindHttp2(ch, c);

	static final Function<ServerBootstrap, ServerBootstrap> HTTP2_OPS_CONF = b -> {
		BootstrapHandlers.channelOperationFactory(b, HTTP2_OPS);
		return b;
	};

	static final int DEFAULT_PORT =
			System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;

	static final TcpServer DEFAULT_TCP_SERVER = TcpServer.create()
	                                                     .bootstrap(HTTP2_OPS_CONF)
	                                                     .port(DEFAULT_PORT);

	static final SslContext DEFAULT_SSL_CONTEXT_HTTP2;
	static {
		SslContext sslContext;
		try {
			SelfSignedCertificate cert = new SelfSignedCertificate();
			io.netty.handler.ssl.SslProvider provider =
					OpenSsl.isAlpnSupported() ? io.netty.handler.ssl.SslProvider.OPENSSL :
					                            io.netty.handler.ssl.SslProvider.JDK;
			sslContext =
					SslContextBuilder.forServer(cert.certificate(), cert.privateKey())
					                 .sslProvider(provider)
					                 .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
					                 .applicationProtocolConfig(new ApplicationProtocolConfig(
					                     Protocol.ALPN,
					                     SelectorFailureBehavior.NO_ADVERTISE,
					                     SelectedListenerFailureBehavior.ACCEPT,
					                     ApplicationProtocolNames.HTTP_2))
					                 .build();
		}
		catch (Exception e) {
			sslContext = null;
		}
		DEFAULT_SSL_CONTEXT_HTTP2 = sslContext;
	}
	static final Consumer<SslProvider.SslContextSpec> SSL_DEFAULT_SPEC_HTTP2 =
			sslProviderBuilder -> sslProviderBuilder.sslContext(DEFAULT_SSL_CONTEXT_HTTP2);
}
