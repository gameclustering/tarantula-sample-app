package com.icodesoftware.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Loads SQL and Redis credentials from Vault before Spring starts.
 * Uses VAULT_HOST + VAULT_TOKEN env vars (injected by tarantula deploy handler).
 * Sets DB_URL, DB_USER, DB_PASSWORD, REDIS_HOST, REDIS_PORT, REDIS_PASSWORD
 * as system properties so Spring's ${...} placeholders resolve them.
 */
public class VaultLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void load() {
        String vaultHost = env("VAULT_HOST");
        String vaultToken = env("VAULT_TOKEN");
        if (vaultHost.isEmpty() || vaultToken.isEmpty()) {
            return;
        }
        String prefix = env("ENV");
        if (prefix.isEmpty()) prefix = "dev";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        try {
            loadSql(client, vaultHost, vaultToken, prefix);
        } catch (Exception e) {
            System.err.println("[VaultLoader] failed to load sql: " + e.getMessage());
        }
        try {
            loadRedis(client, vaultHost, vaultToken, prefix);
        } catch (Exception e) {
            System.err.println("[VaultLoader] failed to load redis: " + e.getMessage());
        }
    }

    private static void loadSql(HttpClient client, String vaultHost, String vaultToken, String prefix) throws Exception {
        JsonNode data = kvGet(client, vaultHost, vaultToken, prefix + "/presence", "postgresql");
        String user  = text(data, "user");
        String pwd   = text(data, "password");
        String host  = text(data, "host");
        String port  = text(data, "port");
        String db    = text(data, "db");
        String cert  = text(data, "cert");

        if (port.isEmpty()) port = "5432";

        // Write CA cert so JDBC can verify the server
        // sslParam starts with & so firstAmpToQuery can turn the first & into ?
        String sslParam = "";
        if (!cert.isEmpty()) {
            Path certFile = Files.createTempFile("pg-ca", ".crt");
            Files.writeString(certFile, cert);
            certFile.toFile().deleteOnExit();
            sslParam = "&ssl=true&sslmode=require&sslrootcert=" + certFile.toAbsolutePath();
        }

        // Ensure the target database exists
        String pgUrl = "jdbc:postgresql://" + host + ":" + port + "/postgres" + firstAmpToQuery(sslParam);
        createDatabaseIfAbsent(pgUrl, user, pwd, "sample");

        String dbUrl = "jdbc:postgresql://" + host + ":" + port + "/sample" + firstAmpToQuery(sslParam);
        System.setProperty("DB_URL",      dbUrl);
        System.setProperty("DB_USER",     user);
        System.setProperty("DB_PASSWORD", pwd);
        System.out.println("[VaultLoader] sql configured host=" + host);
    }

    private static void loadRedis(HttpClient client, String vaultHost, String vaultToken, String prefix) throws Exception {
        JsonNode data = kvGet(client, vaultHost, vaultToken, prefix + "/presence", "redis");
        String host = text(data, "host");
        String port = text(data, "port");
        String pwd  = text(data, "password");

        if (port.isEmpty()) port = "6379";
        if (!host.isEmpty()) {
            // Redis cluster: provide seed node; Lettuce auto-discovers the rest
            System.setProperty("spring.data.redis.cluster.nodes", host + ":" + port);
            System.setProperty("REDIS_PASSWORD", pwd);
            System.setProperty("redis.available", "true");
            System.out.println("[VaultLoader] redis configured host=" + host);
        }
    }

    private static JsonNode kvGet(HttpClient client, String vaultHost, String vaultToken, String mount, String path)
            throws IOException, InterruptedException {
        String url = vaultHost + "/v1/" + mount + "/data/" + path;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Vault-Token", vaultToken)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("vault " + url + " returned " + resp.statusCode() + ": " + resp.body());
        }
        return MAPPER.readTree(resp.body()).path("data").path("data");
    }

    private static void createDatabaseIfAbsent(String pgUrl, String user, String pwd, String dbName) {
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(pgUrl, user, pwd);
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE " + dbName);
            System.out.println("[VaultLoader] created database " + dbName);
        } catch (java.sql.SQLException e) {
            // Ignore "already exists" (42P04), log others
            if (!e.getSQLState().equals("42P04")) {
                System.err.println("[VaultLoader] createDatabase: " + e.getMessage());
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode f = node.path(field);
        return f.isTextual() ? f.asText() : "";
    }

    // "&ssl=true&..." → "?ssl=true&..." so first param uses ? not &
    private static String firstAmpToQuery(String params) {
        if (params.isEmpty()) return "";
        return "?" + params.substring(1);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v != null ? v : "";
    }
}
