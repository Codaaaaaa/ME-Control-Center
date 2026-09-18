package io.github.codaaaaaa.mecc.web.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;

/** Shared JSON configuration for the public API. Thread-safe. */
public final class JsonCodec {
    private final ObjectMapper mapper;

    public JsonCodec() {
        JsonMapper built = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .build();
        built.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32)
                .maxStringLength(16_384)
                .build());
        this.mapper = built;
    }

    public byte[] toBytes(Object value) throws JsonProcessingException {
        return mapper.writeValueAsBytes(value);
    }

    public <T> T fromBytes(byte[] bytes, Class<T> type) throws IOException {
        return mapper.readValue(bytes, type);
    }
}
