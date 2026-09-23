package db.migration.common;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class H2UpgradePathTest {
    @Test void upgradesV1ToV35(){assertUpgrade("v1-to-v35","1");}
    @Test void upgradesV16ToV35(){assertUpgrade("v16-to-v35","16");}
    @Test void upgradesV28ToV35(){assertUpgrade("v28-to-v35","28");}
    private void assertUpgrade(String database,String startingVersion){
        String url="jdbc:h2:mem:"+database+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration/common").target(startingVersion).load().migrate();
        Flyway current=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration/common").load();current.migrate();
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("35");
    }
}
