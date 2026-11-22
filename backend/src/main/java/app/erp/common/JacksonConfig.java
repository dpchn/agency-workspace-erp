package app.erp.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.sql.SQLException;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Lets raw JDBC result rows (report endpoints) serialize Postgres arrays and jsonb cleanly. */
@Configuration
public class JacksonConfig {
    @Bean
    SimpleModule jdbcTypesModule() {
        SimpleModule m = new SimpleModule("jdbc-types");
        m.addSerializer(java.sql.Array.class, new JsonSerializer<>() {
            @Override
            public void serialize(java.sql.Array value, JsonGenerator gen, SerializerProvider sp) throws IOException {
                try {
                    sp.defaultSerializeValue(value.getArray(), gen);
                } catch (SQLException e) {
                    throw new IOException(e);
                }
            }
        });
        m.addSerializer(PGobject.class, new JsonSerializer<>() {
            @Override
            public void serialize(PGobject value, JsonGenerator gen, SerializerProvider sp) throws IOException {
                if (value.getValue() == null) gen.writeNull();
                else if ("json".equals(value.getType()) || "jsonb".equals(value.getType())) gen.writeRawValue(value.getValue());
                else gen.writeString(value.getValue());
            }
        });
        return m;
    }
}
