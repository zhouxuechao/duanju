package db.migration.common;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class H2UpgradePathTest {
    @Test void upgradesV1ToV30(){assertUpgrade("v1-to-v30","1");}
    @Test void upgradesV16ToV30(){assertUpgrade("v16-to-v30","16");}
    @Test void upgradesV28ToV30(){assertUpgrade("v28-to-v30","28");}
    private void assertUpgrade(String database,String startingVersion){
        String url="jdbc:h2:mem:"+database+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration/common").target(startingVersion).load().migrate();
        Flyway current=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration/common").load();current.migrate();
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("30");
    }
}
