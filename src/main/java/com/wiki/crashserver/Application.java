package com.wiki.crashserver;

import com.wiki.crashserver.util.RequestResult;
import io.netty.handler.codec.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.core.Environment;
import reactor.core.Reactor;
import reactor.core.composable.Stream;
import reactor.core.spec.Reactors;
import reactor.event.Event;
import reactor.net.NetServer;
import reactor.net.config.ServerSocketOptions;
import reactor.net.netty.NettyServerSocketOptions;
import reactor.net.netty.tcp.NettyTcpServer;
import reactor.net.tcp.spec.TcpServerSpec;
import reactor.spring.context.config.EnableReactor;

import java.util.concurrent.CountDownLatch;

import static reactor.event.selector.Selectors.$;

@Configuration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.wiki.crashserver")
@EnableReactor
@EnableScheduling
public class Application {

    @Bean
    @Autowired
    public Reactor reactor(Environment env, ResourceHandler handler) {
        Reactor reactor = Reactors.reactor(env, Environment.THREAD_POOL);
        handler.getRequestMapper()
                .entrySet()
                .forEach(e -> reactor.receive($(e.getKey().getT1() + e.getKey().getT2().name()), e.getValue()));
        return reactor;
    }

    @Bean
    public ServerSocketOptions serverSocketOptions() {
        return new NettyServerSocketOptions()
                .pipelineConfigurer(pipeline -> pipeline.addLast(new HttpServerCodec())
                        .addLast(new HttpObjectAggregator(16 * 1024 * 1024)));
    }

    @Bean
    @Autowired
    public NetServer<FullHttpRequest, FullHttpResponse> restApi(Environment env,
                                                                ServerSocketOptions opts,
                                                                Reactor reactor,
                                                                CountDownLatch closeLatch,
                                                                ResourceHandler resourceHandler) throws InterruptedException {
        NetServer<FullHttpRequest, FullHttpResponse> server =
                new TcpServerSpec<FullHttpRequest, FullHttpResponse>(NettyTcpServer.class)
                        .env(env).dispatcher("sync").options(opts).consume(ch -> {
                    Stream<FullHttpRequest> in = ch.in();
                    in.filter(resourceHandler::canHandleRequest,
                            new Stream<FullHttpRequest>(reactor, -1, in, env).consume(t -> {
                                DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                        HttpResponseStatus.NOT_FOUND);
                                resp.content().writeBytes(HttpResponseStatus.NOT_FOUND.reasonPhrase().getBytes());
                                resp.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/plain");
                                resp.headers().set(HttpHeaders.Names.CONTENT_LENGTH, resp.content().readableBytes());
                                ch.send(resp);
                            })
                    ).consume(req -> {
                        QueryStringDecoder decoder = new QueryStringDecoder(req.getUri());
                        reactor.sendAndReceive(decoder.path() + req.getMethod().name(),
                                Event.wrap(req.content()), (Event<RequestResult> responseModel) -> {
                                    DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                            responseModel.getData().getStatus());
                                    resp.headers().set(HttpHeaders.Names.CONTENT_TYPE, responseModel.getData().getContentType());
                                    resp.content().writeBytes(responseModel.getData().getResponseContent());
                                    resp.headers().set(HttpHeaders.Names.CONTENT_LENGTH, resp.content().readableBytes());
                                    ch.send(resp);
                                });
                    });
                    in.filter((FullHttpRequest req) -> "/shutdown".equals(req.getUri()))
                            .consume(req -> closeLatch.countDown());
                }).get();
        server.start().await();
        return server;
    }

    @Bean
    public CountDownLatch closeLatch() {
        return new CountDownLatch(1);
    }

    @Bean
    @Autowired
    public ApplicationListener<ContextClosedEvent> shutdownListener(ResourceHandler resourceHandler) {
        return contextClosedEvent -> resourceHandler.syncShadow();
    }

    public static void main(String... args) throws Exception {
        ApplicationContext ctx = SpringApplication.run(Application.class, args);
        CountDownLatch closeLatch = ctx.getBean(CountDownLatch.class);
        closeLatch.await();
    }
}