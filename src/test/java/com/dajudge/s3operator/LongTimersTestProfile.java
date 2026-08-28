package com.dajudge.s3operator;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class LongTimersTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "s3.operator.resync-interval", "1h",
                "s3.operator.retry-delay", "1h");
    }
}
