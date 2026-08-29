package com.dajudge.s3operator.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VersityS3ProviderTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC);
    private static final String ENDPOINT = "http://localhost:7070";

    @Test
    void createsSignedAdminRequest() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(200, ""));

        new VersityS3Provider(client, CLOCK)
                .createUser(ENDPOINT, "admin-access", "admin-secret", "user access", "secret", "admin");

        HttpRequest request = capturedRequest(client);
        assertThat(request.method()).isEqualTo("PATCH");
        assertThat(request.uri().toString()).isEqualTo(ENDPOINT + "/create-user");
        assertThat(request.headers().firstValue("Authorization"))
                .hasValueSatisfying(value -> assertThat(value)
                        .startsWith("AWS4-HMAC-SHA256 Credential=admin-access/20260829/us-east-1/s3/aws4_request"));
        assertThat(request.headers().firstValue("X-Amz-Date")).contains("20260829T000000Z");
        assertThat(request.headers().firstValue("content-type")).contains("application/xml");
    }

    @Test
    void convergesExistingUserAndBucketOwner() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(409, "<Code>XAdminUserExists</Code>"))
                .thenReturn(response(200, ""))
                .thenReturn(response(409, "<Code>BucketAlreadyExists</Code>"))
                .thenReturn(response(200, ""));
        VersityS3Provider provider = new VersityS3Provider(client, CLOCK);

        provider.createUser(ENDPOINT, "admin", "secret", "user a", "rotated", "admin");
        provider.createBucket(ENDPOINT, "admin", "secret", "bucket a", "user a");

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(4)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues())
                .extracting(request -> request.uri().toString())
                .containsExactly(
                        ENDPOINT + "/create-user",
                        ENDPOINT + "/update-user?access=user%20a",
                        ENDPOINT + "/bucket%20a/create",
                        ENDPOINT + "/change-bucket-owner?bucket=bucket%20a&owner=user%20a");
    }

    @Test
    void toleratesMissingDeletesAndRejectsUnexpectedErrors() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(404, "<Code>NoSuchBucket</Code>"))
                .thenReturn(response(404, "<Code>XAdminUserNotFound</Code>"))
                .thenReturn(response(500, "boom"));
        VersityS3Provider provider = new VersityS3Provider(client, CLOCK);

        provider.deleteBucket(ENDPOINT, "admin", "secret", "missing");
        provider.deleteUser(ENDPOINT, "admin", "secret", "missing");

        assertThatThrownBy(() -> provider.deleteBucket(ENDPOINT, "admin", "secret", "broken"))
                .isInstanceOf(S3ProviderException.class)
                .hasMessageContaining("HTTP 500 boom");
    }

    @Test
    void wrapsIoFailuresAndPreservesInterrupts() throws Exception {
        HttpClient ioClient = mock(HttpClient.class);
        when(ioClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("offline"));
        assertThatThrownBy(() ->
                        new VersityS3Provider(ioClient, CLOCK).deleteBucket(ENDPOINT, "admin", "secret", "bucket"))
                .isInstanceOf(S3ProviderException.class)
                .hasCauseInstanceOf(IOException.class);

        HttpClient interruptedClient = mock(HttpClient.class);
        when(interruptedClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("stop"));
        try {
            assertThatThrownBy(() -> new VersityS3Provider(interruptedClient, CLOCK)
                            .deleteBucket(ENDPOINT, "admin", "secret", "bucket"))
                    .isInstanceOf(S3ProviderException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static HttpRequest capturedRequest(HttpClient client) throws Exception {
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        return request.getValue();
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
