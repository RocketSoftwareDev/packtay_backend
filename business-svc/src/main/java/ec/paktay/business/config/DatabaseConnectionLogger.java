package ec.paktay.business.config;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class DatabaseConnectionLogger {
    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionLogger.class);

    @Bean
    ApplicationRunner verifyBusinessDatabase(JdbcClient jdbc) {
        return args -> {
            try {
                DatabaseIdentity identity = jdbc.sql("select current_database() as database_name, current_user as database_user")
                        .query(this::map).single();
                log.info("business_database_connected database={} user={}", identity.databaseName(), identity.databaseUser());
            } catch (DataAccessException ex) {
                log.error("business_database_connection_failed reason={}", ex.getMostSpecificCause().getMessage(), ex);
                throw ex;
            }
        };
    }

    private DatabaseIdentity map(ResultSet rs, int rowNum) throws SQLException {
        return new DatabaseIdentity(rs.getString("database_name"), rs.getString("database_user"));
    }

    private record DatabaseIdentity(String databaseName, String databaseUser) { }
}
