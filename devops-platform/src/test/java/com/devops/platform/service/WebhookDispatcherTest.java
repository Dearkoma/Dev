package com.devops.platform.service;

import com.devops.platform.entity.WebhookSubscription;
import com.devops.platform.repository.WebhookSubscriptionRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WebhookDispatcher 单元测试
 * <p>
 * 用真实的本地 HTTP 桩服务承接 POST，验证「项目过滤」与「测试只发一条」两个行为。
 * 测试中直接 new 出 dispatcher（无 Spring 代理），@Async 不生效 → 派发是同步的，可断言结果。
 */
class WebhookDispatcherTest {

    private HttpServer server;
    private String baseUrl;

    /** 桩服务实际收到的请求路径，按到达顺序 */
    private final List<String> receivedPaths = new CopyOnWriteArrayList<>();
    /** 每个路径最后一次收到的 X-DevOps-Signature 头 */
    private final List<String> receivedSignatures = new CopyOnWriteArrayList<>();

    private WebhookSubscriptionRepository repository;
    private WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            receivedPaths.add(exchange.getRequestURI().getPath());
            String sig = exchange.getRequestHeaders().getFirst("X-DevOps-Signature");
            if (sig != null) receivedSignatures.add(sig);
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

        repository = mock(WebhookSubscriptionRepository.class);
        dispatcher = new WebhookDispatcher(repository);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private WebhookSubscription sub(long id, Long projectId, String path) {
        WebhookSubscription s = new WebhookSubscription();
        s.setId(id);
        s.setName("sub-" + id);
        s.setType(WebhookSubscription.TYPE_CUSTOM);
        s.setEventType(WebhookSubscription.EVENT_BUILD_SUCCESS);
        s.setUrl(baseUrl + path);
        s.setProjectId(projectId);
        s.setActive(true);
        s.setFailCount(0);
        return s;
    }

    private void givenSubscriptions(WebhookSubscription... subs) {
        when(repository.findByEventTypeAndActiveTrue(WebhookSubscription.EVENT_BUILD_SUCCESS))
                .thenReturn(new ArrayList<>(List.of(subs)));
    }

    /**
     * 项目 7 的事件 → 只应命中「绑定项目 7」+「全局」两条订阅，项目 9 的订阅不投递
     */
    @Test
    void dispatchWithProjectId_hitsOnlySameProjectAndGlobal() {
        givenSubscriptions(
                sub(1L, 7L, "/p7"),
                sub(2L, null, "/global"),
                sub(3L, 9L, "/p9")
        );

        dispatcher.dispatch(WebhookSubscription.EVENT_BUILD_SUCCESS,
                Map.of("title", "构建成功 #1"), 7L);

        assertEquals(2, receivedPaths.size(), "应只投递 2 条: 本项目 + 全局");
        assertTrue(receivedPaths.contains("/hook/p7"));
        assertTrue(receivedPaths.contains("/hook/global"));
        assertFalse(receivedPaths.contains("/hook/p9"), "不应投递到其他项目的订阅");
    }

    /**
     * 事件无项目上下文 (projectId=null) → 只命中全局订阅，绑定项目的订阅都不投递
     */
    @Test
    void dispatchWithNullProjectId_hitsOnlyGlobal() {
        givenSubscriptions(
                sub(1L, 7L, "/p7"),
                sub(2L, null, "/global"),
                sub(3L, 9L, "/p9")
        );

        dispatcher.dispatch(WebhookSubscription.EVENT_BUILD_SUCCESS,
                Map.of("title", "无项目上下文"), null);

        assertEquals(1, receivedPaths.size(), "无项目上下文时只应命中全局订阅");
        assertEquals("/hook/global", receivedPaths.get(0));
    }

    /**
     * 测试按钮: 只发指定订阅，不触发同事件的其他订阅
     */
    @Test
    void dispatchTo_sendsOnlyTargetSubscription() {
        WebhookSubscription target = sub(3L, 9L, "/p9");
        givenSubscriptions(
                sub(1L, 7L, "/p7"),
                sub(2L, null, "/global"),
                target
        );

        dispatcher.dispatchTo(target, WebhookSubscription.EVENT_BUILD_SUCCESS,
                Map.of("title", "测试消息"));

        assertEquals(1, receivedPaths.size(), "测试只应发出 1 条");
        assertEquals("/hook/p9", receivedPaths.get(0));
    }

    /** 无任何匹配订阅时不应发出请求 */
    @Test
    void dispatch_whenNoSubscriptionMatches_sendsNothing() {
        givenSubscriptions(sub(3L, 9L, "/p9"));

        dispatcher.dispatch(WebhookSubscription.EVENT_BUILD_SUCCESS,
                Map.of("title", "构建成功 #2"), 7L);

        assertTrue(receivedPaths.isEmpty());
    }

    /** 全局订阅应能收到任意项目的事件 */
    @Test
    void globalSubscription_receivesEventsOfEveryProject() {
        givenSubscriptions(sub(2L, null, "/global"));

        dispatcher.dispatch(WebhookSubscription.EVENT_BUILD_SUCCESS, Map.of("title", "a"), 7L);
        dispatcher.dispatch(WebhookSubscription.EVENT_BUILD_SUCCESS, Map.of("title", "b"), 9L);

        assertEquals(2, receivedPaths.size(), "全局订阅应两次都收到");
        assertEquals(List.of("/hook/global", "/hook/global"), receivedPaths);
    }

    /** 配置了 secret 时应带 HMAC-SHA256 签名头 */
    @Test
    void dispatch_signsBodyWhenSecretConfigured() {
        WebhookSubscription signed = sub(1L, null, "/signed");
        signed.setSecret("topsecret");
        givenSubscriptions(signed);

        dispatcher.dispatch(WebhookSubscription.EVENT_BUILD_SUCCESS, Map.of("title", "x"), 7L);

        assertEquals(1, receivedPaths.size());
        assertEquals(1, receivedSignatures.size(), "应带 X-DevOps-Signature 头");
        assertTrue(receivedSignatures.get(0).startsWith("sha256="));
        // sha256= + 64 位十六进制
        assertEquals("sha256=".length() + 64, receivedSignatures.get(0).length());
    }

    /** 未配置 secret 时不应带签名头 */
    @Test
    void dispatch_noSignatureWhenSecretAbsent() {
        givenSubscriptions(sub(1L, null, "/plain"));

        dispatcher.dispatch(WebhookSubscription.EVENT_BUILD_SUCCESS, Map.of("title", "x"), 7L);

        assertEquals(1, receivedPaths.size());
        assertTrue(receivedSignatures.isEmpty(), "未配置密钥不应带签名头");
    }
}
