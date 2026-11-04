package com.shopglobal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shopglobal.agent.FakeGateway.Reply;
import com.shopglobal.agent.FakeGateway.Req;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Token handling, retry rules and headers of the calls to the gateway (LLM and MCP). */
class GatewayClientTest {

    static final ObjectMapper M = new ObjectMapper();

    /** Everything wired against one fake gateway. Retry waits are shortened so the tests run fast. */
    static final class Wiring {
        final TokenProvider tokens;
        final KongHttp kong;
        final LlmClient llm;
        final McpClient mcp;
        Wiring(FakeGateway g) {
            tokens = new TokenProvider(M, g.url() + "/token", "kong-ai-admin", "test-secret", "", 30);
            kong = new KongHttp(tokens, 5, 20, 20, 3);
            llm = new LlmClient(M, kong, g.url(), "/v1/chat/completions", "shopglobal-auto", "UAT", "");
            mcp = new McpClient(M, kong, g.url(), "/mcp/marketplace", "CUST-1001");
        }
    }

    static List<ObjectNode> question(String text) {
        ObjectNode sys = M.createObjectNode().put("role", "system").put("content", "LANGUAGE rule");
        ObjectNode usr = M.createObjectNode().put("role", "user").put("content", text);
        return List.of(sys, usr);
    }

    @Test
    void bearerTokenIsSentOnLlmAndOnEveryMcpRequest() throws Exception {
        try (FakeGateway g = new FakeGateway()) {
            AtomicInteger tokenCalls = new AtomicInteger();
            g.handler = r -> {
                if (r.path().equals("/token")) return FakeGateway.token("tok" + tokenCalls.incrementAndGet(), 300);
                if (r.path().equals("/v1/chat/completions")) return FakeGateway.chat("hi", "Miss");
                return FakeGateway.mcp(r, "ok");
            };
            Wiring w = new Wiring(g);
            w.llm.chat(question("hello"), null, "en");
            w.mcp.call("searchProducts", M.createObjectNode(), "CUST-1001");
            List<Req> gw = g.requests.stream().filter(r -> !r.path().equals("/token")).toList();
            assertTrue(gw.size() >= 5, "chat + initialize + initialized + tools/list + tools/call");
            for (Req r : gw) assertEquals("Bearer tok1", r.header("Authorization"), r.path());
            assertEquals(1, tokenCalls.get(), "one token is reused for all calls");
        }
    }

    @Test
    void gatewayRejectsTheFirstTokenThenTheAppRefreshesAndRetriesOnce() throws Exception {
        try (FakeGateway g = new FakeGateway()) {
            AtomicInteger tokenCalls = new AtomicInteger();
            g.handler = r -> {
                if (r.path().equals("/token")) return FakeGateway.token("tok" + tokenCalls.incrementAndGet(), 300);
                return "Bearer tok2".equals(r.header("Authorization"))
                        ? FakeGateway.chat("welcome", "Miss") : Reply.json(401, "{\"message\":\"Unauthorized\"}");
            };
            Wiring w = new Wiring(g);
            LlmClient.LlmResult res = w.llm.chat(question("hello"), null, "en");
            assertEquals("welcome", res.message().path("content").asText());
            assertEquals(2, tokenCalls.get(), "a second token was fetched after the 401");
            assertEquals(2, g.to("/v1/chat/completions").size(), "exactly one retry");
        }
    }

    @Test
    void a401ThatSurvivesTheRefreshEndsAsAnException() throws Exception {
        try (FakeGateway g = new FakeGateway()) {
            g.handler = r -> r.path().equals("/token") ? FakeGateway.token("t", 300) : Reply.json(401, "{}");
            Wiring w = new Wiring(g);
            KongException e = assertThrows(KongException.class, () -> w.llm.chat(question("hello"), null, "en"));
            assertEquals(401, e.status());
            assertEquals(2, g.to("/v1/chat/completions").size(), "one call plus one retry, no more");
        }
    }

    @Test
    void anExpiredTokenIsRefreshedAutomatically() throws Exception {
        try (FakeGateway g = new FakeGateway()) {
            AtomicInteger tokenCalls = new AtomicInteger();
            g.handler = r -> FakeGateway.token("tok" + tokenCalls.incrementAndGet(), 100);   // valid 100 s, renewed 30 s early
            TokenProvider t = new TokenProvider(M, g.url() + "/token", "c", "s", "", 30);
            long[] now = {1_000_000L};
            t.useClock(() -> now[0]);
            assertEquals("tok1", t.token());
            now[0] += 60_000;                       // 60 s later: still valid
            assertEquals("tok1", t.token());
            now[0] += 20_000;                       // 80 s: inside the 30 s safety margin -> renewed
            assertEquals("tok2", t.token());
            assertEquals(2, tokenCalls.get());
        }
    }

    @Test
    void aTokenEndpointFailureIsAnErrorNotAnEmptyBearer() throws Exception {
        try (FakeGateway g = new FakeGateway()) {
            g.handler = r -> r.path().equals("/token") ? Reply.json(401, "{\"error\":\"unauthorized_client\"}") : FakeGateway.chat("x", null);
            Wiring w = new Wiring(g);
            assertThrows(KongException.class, () -> w.llm.chat(question("hello"), null, "en"));
            assertTrue(g.to("/v1/chat/completions").isEmpty(), "nothing is sent to the gateway without a token");
        }
    }

    @Test
    void a429IsRetriedOnceAfterRetryAfterAndTheSecondOneFails() throws Exception {
        try (FakeGateway g = new FakeGateway()) {
            AtomicInteger n = new AtomicInteger();
            g.handler = r -> {
                if (r.path().equals("/token")) return FakeGateway.token("t", 300);
                return n.incrementAndGet() == 1 ? Reply.with(Reply.json(429, "{}"), "Retry-After", "0") : FakeGateway.chat("ok", null);
            };
            Wiring w = new Wiring(g);
            assertEquals("ok", w.llm.chat(question("hello"), null, "en").message().path("content").asText());
            assertEquals(2, g.to("/v1/chat/completions").size());

            g.requests.clear();
            g.handler = r -> r.path().equals("/token") ? FakeGateway.token("t", 300) : Reply.with(Reply.json(429, "{}"), "Retry-After", "0");
            KongException e = assertThrows(KongException.class, () -> w.llm.chat(question("hello"), null, "en"));
            assertEquals(429, e.status());
            assertEquals(2, g.to("/v1/chat/completions").size(), "at most one retry");
        }
    }

    @Test
    void a5xxIsRetriedOnceThenFails_butAToolCallIsNeverRepeated() throws Exception {
        try (FakeGateway g = new FakeGateway()) {
            g.handler = r -> {
                if (r.path().equals("/token")) return FakeGateway.token("t", 300);
                if (r.path().equals("/v1/chat/completions")) return Reply.json(503, "{}");
                if (r.rpcMethod().equals("tools/call")) return Reply.json(500, "{}");
                return FakeGateway.mcp(r, "ok");
            };
            Wiring w = new Wiring(g);
            KongException e = assertThrows(KongException.class, () -> w.llm.chat(question("hello"), null, "en"));
            assertEquals(503, e.status());
            assertEquals(2, g.to("/v1/chat/completions").size(), "one retry");

            String out = w.mcp.call("cancelOrder", M.createObjectNode(), "CUST-1001");
            assertTrue(out.startsWith("ERROR"));
            long calls = g.to("/mcp/marketplace").stream().filter(r -> r.rpcMethod().equals("tools/call")).count();
            assertEquals(1, calls, "a data-changing call is sent once, never re-sent");
        }
    }

    @Test
    void noCacheControlHeaderIsSent_modelIsAuto_andTheCacheStatusIsRead() throws Exception {
        try (FakeGateway g = new FakeGateway()) {
            AtomicInteger n = new AtomicInteger();
            g.handler = r -> r.path().equals("/token") ? FakeGateway.token("t", 300)
                    : FakeGateway.chat("30 days", n.incrementAndGet() == 1 ? "Miss" : "Hit");
            Wiring w = new Wiring(g);
            LlmClient.LlmResult first = w.llm.chat(question("What is your return policy?"), null, "en");
            LlmClient.LlmResult second = w.llm.chat(question("What is your return policy?"), null, "en");
            assertEquals("Miss", first.cache());
            assertEquals("Hit", second.cache());
            for (Req r : g.to("/v1/chat/completions")) {
                assertNull(r.header("Cache-Control"), "Cache-Control: no-cache would make the gateway skip its cache");
                assertNull(r.header("Pragma"));
                assertEquals("shopglobal-auto", r.json().path("model").asText());
                assertTrue(r.json().path("stream").isMissingNode(), "non-streaming");
                assertNull(r.header("X-Model-Tier"), "the app does not choose a model tier");
            }
        }
    }

    @Test
    void customerIdIsSentOnInitializeToolsListAndEveryToolCall_andUnsafeValuesAreReplaced() throws Exception {
        try (FakeGateway g = new FakeGateway()) {
            g.handler = r -> r.path().equals("/token") ? FakeGateway.token("t", 300) : FakeGateway.mcp(r, "ok");
            Wiring w = new Wiring(g);
            w.mcp.call("searchProducts", M.createObjectNode(), "CUST-2002");
            w.mcp.call("listOrders", M.createObjectNode(), "CUST-1001\r\nX-Evil: 1");
            List<Req> mcp = g.to("/mcp/marketplace");
            for (Req r : mcp) assertNotNull(r.header("X-Customer-Id"), r.rpcMethod());
            assertEquals("CUST-2002", mcp.get(mcp.size() - 2).header("X-Customer-Id"));
            assertEquals("CUST-1001", mcp.get(mcp.size() - 1).header("X-Customer-Id"), "an unsafe id falls back to the default");
            assertEquals("S1", mcp.get(mcp.size() - 1).header("Mcp-Session-Id"));
        }
    }
}
