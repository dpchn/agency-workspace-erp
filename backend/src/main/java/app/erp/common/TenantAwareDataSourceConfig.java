package app.erp.common;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Binds the current tenant to every pooled connection (session variable app.tenant_id)
 * so PostgreSQL row-level security applies to JPA and JdbcTemplate alike, and resets it
 * when the connection returns to the pool.
 */
@Configuration
public class TenantAwareDataSourceConfig {

    @Bean
    static BeanPostProcessor tenantDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource ds && !(bean instanceof TenantAwareDataSource) && "dataSource".equals(name)) {
                    return new TenantAwareDataSource(ds);
                }
                return bean;
            }
        };
    }

    static class TenantAwareDataSource extends DelegatingDataSource {
        TenantAwareDataSource(DataSource target) { super(target); }

        @Override
        public Connection getConnection() throws SQLException {
            return bind(super.getConnection());
        }

        @Override
        public Connection getConnection(String user, String pass) throws SQLException {
            return bind(super.getConnection(user, pass));
        }

        private Connection bind(Connection c) throws SQLException {
            UUID tenant = TenantContext.tenantId();
            setTenant(c, tenant == null ? "" : tenant.toString());
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("close".equals(method.getName())) {
                            try { if (!c.isClosed()) setTenant(c, ""); } catch (SQLException ignored) { }
                        }
                        if ("unwrap".equals(method.getName()) || "isWrapperFor".equals(method.getName())) {
                            return method.invoke(c, args);
                        }
                        try {
                            return method.invoke(c, args);
                        } catch (java.lang.reflect.InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    });
        }

        private static void setTenant(Connection c, String value) throws SQLException {
            boolean auto = c.getAutoCommit();
            try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                ps.setString(1, value);
                ps.execute();
            }
            if (!auto) c.commit();
        }
    }
}
