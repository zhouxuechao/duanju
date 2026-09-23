package db.migration.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Removes V25 relink warnings from tracks which are intentionally independent of a shot video clip. */
public final class V26__fix_legacy_timeline_relink_scope extends BaseJavaMigration {
    private final ObjectMapper mapper=new ObjectMapper();

    @Override public void migrate(Context context)throws Exception{
        Connection connection=context.getConnection();List<Row> rows=new ArrayList<>();
        try(PreparedStatement statement=connection.prepareStatement("SELECT id,shot_id,document FROM \"timeline_item\" WHERE linked_video_timeline_item_id IS NULL");ResultSet result=statement.executeQuery()){
            while(result.next())rows.add(new Row(result.getObject(1,UUID.class),result.getObject(2,UUID.class),result.getString(3)));
        }
        for(Row row:rows){
            ObjectNode document=(ObjectNode)mapper.readTree(row.document());String track=document.path("track").asText().toUpperCase(Locale.ROOT);
            boolean independent="BGM".equals(track)||"AMBIENCE".equals(track)||("SFX".equals(track)&&row.shotId()==null);
            if(!independent)continue;
            document.remove(List.of("linkedVideoTimelineItemId","timelineRelinkRequired","timelineRelinkReason"));
            try(PreparedStatement update=connection.prepareStatement("UPDATE \"timeline_item\" SET document=? WHERE id=?")){
                update.setString(1,mapper.writeValueAsString(document));update.setObject(2,row.id());update.executeUpdate();
            }
        }
    }
    private record Row(UUID id,UUID shotId,String document){}
}
