package dev.minecraftharness.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Loopback-only MCP endpoint that exposes the Minecraft tools to the claude CLI.
 * Binds 127.0.0.1 on an OS-assigned port unless a fixed one is configured; the URL carries
 * a random token path so other local processes cannot drive the world.
 */
public final class McpServer {

    private static final boolean DEBUG = Boolean.getBoolean("mh.mcp.debug");

    private final McpProtocol protocol;
    private final String token;
    private HttpServer server;
    private ExecutorService pool;

    public McpServer(McpProtocol protocol, String token) {
        this.protocol = protocol;
        this.token = token;
    }

    /** @param port 0 for an ephemeral port */
    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 8);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MinecraftHarness-MCP");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/mcp/" + token, this::handle);
        server.start();
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port() + "/mcp/" + token;
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
        server = null;
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Allow", "POST");
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (DEBUG) System.err.println("[mcp] <- " + body);
            McpProtocol.Reply reply = protocol.handle(body);
            if (DEBUG) System.err.println("[mcp] -> " + reply.status() + " " + (reply.body() == null ? "" : ToolHost.truncate(reply.body(), 300)));
            if (reply.body() == null) {
                ex.sendResponseHeaders(reply.status(), -1);
                return;
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    /**
     * Builds the tool-call handler: runs the tool, one at a time, against the current turn.
     * Results come back as MCP content (text, plus an image for mc_take_photo).
     */
    public static java.util.function.BiFunction<String, JsonObject, JsonObject> toolCaller(
        ToolHost host, Supplier<ToolHost.Turn> currentTurn
    ) {
        Object lock = new Object();
        return (name, args) -> {
            ToolHost.Turn turn = currentTurn.get();
            if (turn == null) {
                return McpProtocol.textResult("No Minecraft request is active.", true);
            }
            synchronized (lock) {
                JsonObject result = host.dispatch(name, args, turn);
                host.afterTool(name, args, result, turn);
                String image = ToolHost.takeImage(result);
                JsonObject out = McpProtocol.textResult(ToolHost.GSON.toJson(result), !ToolHost.isOk(result));
                if (image != null) {
                    out.getAsJsonArray("content").add(McpProtocol.imageBlock(image));
                }
                return out;
            }
        };
    }

    static JsonArray emptyTools() {
        return new JsonArray();
    }
}
