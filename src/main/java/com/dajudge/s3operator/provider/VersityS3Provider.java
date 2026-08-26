package com.dajudge.s3operator.provider;

import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class VersityS3Provider implements S3Provider {
    private static final String REGION = "us-east-1";
    private static final String SERVICE = "s3";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    private final HttpClient httpClient;
    private final Clock clock;

    public VersityS3Provider() {
        this(HttpClient.newHttpClient(), Clock.systemUTC());
    }

    VersityS3Provider(HttpClient httpClient, Clock clock) {
        this.httpClient = httpClient;
        this.clock = clock;
    }

    @Override
    public String type() {
        return "versity";
    }

    @Override
    public void createUser(String endpoint, String adminAccessKey, String adminSecretKey,
                           String accessKey, String secretKey, String role) {
        String createBody = "<Account><Access>" + xml(accessKey) + "</Access><Secret>" + xml(secretKey)
                + "</Secret><Role>" + xml(role) + "</Role></Account>";
        boolean alreadyExists = send("PATCH", endpoint, "/create-user", "", createBody,
                Map.of("content-type", "application/xml"), adminAccessKey, adminSecretKey,
                "XAdminUserExists");

        if (alreadyExists) {
            String updateBody = "<MutableProps><Secret>" + xml(secretKey) + "</Secret><Role>" + xml(role)
                    + "</Role></MutableProps>";
            send("PATCH", endpoint, "/update-user", "access=" + url(accessKey), updateBody,
                    Map.of("content-type", "application/xml"), adminAccessKey, adminSecretKey,
                    null);
        }
    }

    @Override
    public void deleteUser(String endpoint, String adminAccessKey, String adminSecretKey, String accessKey) {
        send("PATCH", endpoint, "/delete-user", "access=" + url(accessKey), "", Map.of(),
                adminAccessKey, adminSecretKey, "XAdminUserNotFound");
    }

    @Override
    public void createBucket(String endpoint, String adminAccessKey, String adminSecretKey,
                             String bucketName, String ownerAccessKey) {
        send("PATCH", endpoint, "/" + url(bucketName) + "/create", "", "",
                Map.of("x-vgw-owner", ownerAccessKey), adminAccessKey, adminSecretKey,
                "BucketAlreadyOwnedByYou");
    }

    @Override
    public void deleteBucket(String endpoint, String adminAccessKey, String adminSecretKey, String bucketName) {
        send("DELETE", endpoint, "/" + url(bucketName), "", "", Map.of(),
                adminAccessKey, adminSecretKey, "NoSuchBucket");
    }

    private boolean send(String method, String endpoint, String path, String query, String body,
                         Map<String, String> extraHeaders, String accessKey, String secretKey,
                         String idempotentErrorCode) {
        try {
            URI base = URI.create(endpoint);
            URI uri = URI.create(endpoint.replaceAll("/$", "") + path + (query.isEmpty() ? "" : "?" + query));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            String payloadHash = hex(sha256(payload));
            Instant now = clock.instant();
            String amzDate = AMZ_DATE.format(now);
            String date = DATE.format(now);

            TreeMap<String, String> headers = new TreeMap<>();
            headers.put("host", hostHeader(base));
            headers.put("x-amz-content-sha256", payloadHash);
            headers.put("x-amz-date", amzDate);
            extraHeaders.forEach((k, v) -> headers.put(k.toLowerCase(), v.trim()));

            String canonicalHeaders = headers.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue().replaceAll("\\s+", " ") + "\n")
                    .collect(Collectors.joining());
            String signedHeaders = String.join(";", headers.keySet());
            String canonicalRequest = method + "\n" + path + "\n" + query + "\n" + canonicalHeaders + "\n"
                    + signedHeaders + "\n" + payloadHash;
            String scope = date + "/" + REGION + "/" + SERVICE + "/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                    + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
            byte[] signingKey = hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date), REGION), SERVICE), "aws4_request");
            String signature = hex(hmac(signingKey, stringToSign));
            String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                    + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .header("X-Amz-Content-Sha256", payloadHash)
                    .header("X-Amz-Date", amzDate)
                    .header("Authorization", authorization);
            extraHeaders.forEach(request::header);
            request.method(method, payload.length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(payload));

            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 400) {
                return false;
            }
            if (idempotentErrorCode != null && response.body().contains(idempotentErrorCode)) {
                return true;
            }
            throw new IllegalStateException("VersityGW admin request failed: HTTP " + response.statusCode()
                    + " " + response.body());
        } catch (IOException e) {
            throw new IllegalStateException("VersityGW admin request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("VersityGW admin request interrupted", e);
        }
    }

    private static String hostHeader(URI uri) {
        boolean defaultPort = uri.getPort() == -1
                || ("http".equals(uri.getScheme()) && uri.getPort() == 80)
                || ("https".equals(uri.getScheme()) && uri.getPort() == 443);
        return defaultPort ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
