package db.migration.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.*;
import java.util.*;

public final class V25__relink_legacy_timeline_items extends BaseJavaMigration {
    private final ObjectMapper mapper=new ObjectMapper();

    @Override public void migrate(Context context)throws Exception{
        Connection connection=context.getConnection();
        List<Row> rows=new ArrayList<>();
        try(PreparedStatement statement=connection.prepareStatement("SELECT id,timeline_id,shot_id,document FROM \"timeline_item\" WHERE linked_video_timeline_item_id IS NULL");ResultSet result=statement.executeQuery()){
            while(result.next())rows.add(new Row(result.getObject(1,UUID.class),result.getObject(2,UUID.class),result.getObject(3,UUID.class),result.getString(4)));
        }
        for(Row row:rows){
            ObjectNode document=(ObjectNode)mapper.readTree(row.document());
            if("VIDEO".equalsIgnoreCase(document.path("track").asText()))continue;
            List<UUID> candidates=candidates(connection,row.timelineId(),row.shotId());
            document.remove(List.of("timelineRelinkRequired","timelineRelinkReason"));
            UUID linked=null;
            if(candidates.size()==1){linked=candidates.getFirst();document.put("linkedVideoTimelineItemId",linked.toString());}
            else{document.put("timelineRelinkRequired",true).put("timelineRelinkReason",candidates.isEmpty()?"VIDEO_CLIP_NOT_FOUND":"AMBIGUOUS_REPEATED_SHOT");}
            try(PreparedStatement update=connection.prepareStatement("UPDATE \"timeline_item\" SET linked_video_timeline_item_id=?,document=? WHERE id=?")){
                update.setObject(1,linked);update.setString(2,mapper.writeValueAsString(document));update.setObject(3,row.id());update.executeUpdate();
            }
        }
    }

    private List<UUID> candidates(Connection connection,UUID timelineId,UUID shotId)throws SQLException{
        if(shotId==null)return List.of();List<UUID> result=new ArrayList<>();
        try(PreparedStatement statement=connection.prepareStatement("SELECT id,document FROM \"timeline_item\" WHERE timeline_id=? AND shot_id=? ORDER BY created_at,id")){
            statement.setObject(1,timelineId);statement.setObject(2,shotId);
            try(ResultSet rows=statement.executeQuery()){while(rows.next())try{if("VIDEO".equalsIgnoreCase(mapper.readTree(rows.getString(2)).path("track").asText()))result.add(rows.getObject(1,UUID.class));}catch(Exception error){throw new SQLException("Invalid timeline item document",error);}}
        }
        return result;
    }
    private record Row(UUID id,UUID timelineId,UUID shotId,String document){}
}
