package db.migration.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rewrite persisted pre-staged-director documents once; runtime contracts remain strict. */
public class V13__canonical_director_definitions extends BaseJavaMigration {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private static final Set<String> CURRENT_DIRECTIONS=Set.of("FRAME_LEFT","FRAME_RIGHT","INTO_DEPTH","OUT_OF_DEPTH","STATIC");
    private static final Map<String,String> DIRECTION_MIGRATION=Map.of(
        "LEFT","FRAME_LEFT","RIGHT","FRAME_RIGHT",
        "左前方","FRAME_LEFT","右前方","FRAME_RIGHT",
        "正向画面深处","INTO_DEPTH","正向镜头方向","OUT_OF_DEPTH"
    );
    private static final Map<String,String> FIELD_MIGRATION=Map.of(
        "activeShotPlanJobId","activeDirectorPlanJobId",
        "activeShotPlanSignature","activeDirectorPlanSignature",
        "shotPlanStatus","directorPlanStatus"
    );

    @Override public void migrate(Context context) throws Exception {
        Connection connection=context.getConnection();Map<String,String> jobTypes=new HashMap<>();
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT id, type FROM \"generation_job\"")){
            while(rows.next())jobTypes.put(rows.getString(1),rows.getString(2));
        }
        Set<String> currentRoots=new HashSet<>();
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT document FROM \"scene\"")){
            while(rows.next()){
                String active=activePlanId(MAPPER.readTree(rows.getString(1)));
                if("DIRECTOR_PLAN".equals(jobTypes.get(active)))currentRoots.add(active);
            }
        }
        List<String> tables=new ArrayList<>();
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery(
            "SELECT table_name FROM information_schema.columns WHERE table_schema=CURRENT_SCHEMA AND LOWER(column_name)='document'")){
            while(rows.next())tables.add(rows.getString(1));
        }
        for(String table:tables)migrateTable(connection,table,currentRoots);
    }

    static String canonicalizeDocument(String source){
        return canonicalizeDocument(source,true);
    }

    static String canonicalizeHistoricalDocument(String source){
        return canonicalizeDocument(source,false);
    }

    static String canonicalizeSceneDocument(String source,Set<String> currentRoots){
        try{
            ObjectNode root=(ObjectNode)MAPPER.readTree(source);String active=activePlanId(root);boolean retired=!active.isBlank()&&!currentRoots.contains(active);
            canonicalize(root,"$",false);
            if(retired){root.remove(List.of("activeDirectorPlanJobId","activeDirectorPlanSignature","directorPlanStatus"));root.put("directorPlanStatus","REPLAN_REQUIRED");}
            return MAPPER.writeValueAsString(root);
        }catch(IllegalStateException error){throw error;}catch(Exception error){throw new IllegalStateException("Cannot migrate persisted JSON document",error);}
    }

    private static String canonicalizeDocument(String source,boolean directions){
        try{
            JsonNode root=MAPPER.readTree(source);canonicalize(root,"$",directions);return MAPPER.writeValueAsString(root);
        }catch(IllegalStateException error){throw error;}catch(Exception error){throw new IllegalStateException("Cannot migrate persisted JSON document",error);}
    }

    private static void migrateTable(Connection connection,String table,Set<String> currentRoots) throws Exception {
        String quoted='"'+table.replace("\"","\"\"")+'"';Map<Object,String> changed=new LinkedHashMap<>();
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT id, document FROM "+quoted)){
            while(rows.next()){
                String id=rows.getString(1),source=rows.getString(2),canonical;
                if("scene".equalsIgnoreCase(table))canonical=canonicalizeSceneDocument(source,currentRoots);
                else if("generation_job".equalsIgnoreCase(table)){
                    JsonNode document=MAPPER.readTree(source);String parent=document.path("inputSnapshot").path("rootPlanJobId").asText();
                    canonical=canonicalizeDocument(source,currentRoots.contains(id)||currentRoots.contains(parent));
                }else canonical=canonicalizeHistoricalDocument(source);
                if(!canonical.equals(source))changed.put(rows.getObject(1),canonical);
            }
        }
        if(changed.isEmpty())return;
        try(PreparedStatement update=connection.prepareStatement("UPDATE "+quoted+" SET document=? WHERE id=?")){
            for(var entry:changed.entrySet()){update.setString(1,entry.getValue());update.setObject(2,entry.getKey());update.addBatch();}
            update.executeBatch();
        }
    }

    private static String activePlanId(JsonNode scene){
        String current=scene.path("activeDirectorPlanJobId").asText();return current.isBlank()?scene.path("activeShotPlanJobId").asText():current;
    }

    private static void canonicalize(JsonNode node,String path,boolean directions){
        if(node.isArray()){for(int i=0;i<node.size();i++)canonicalize(node.path(i),path+'['+i+']',directions);return;}
        if(!node.isObject())return;ObjectNode object=(ObjectNode)node;
        for(var rename:FIELD_MIGRATION.entrySet())if(object.has(rename.getKey())){
            JsonNode old=object.remove(rename.getKey()),current=object.get(rename.getValue());
            if(current!=null&&!current.equals(old))throw new IllegalStateException(path+" contains conflicting "+rename.getKey()+" and "+rename.getValue());
            if(current==null)object.set(rename.getValue(),old);
        }
        if(directions&&object.has("screenDirection")){
            String old=object.path("screenDirection").asText();
            if(!CURRENT_DIRECTIONS.contains(old)){
                String canonical=DIRECTION_MIGRATION.get(old);
                if(canonical==null)throw new IllegalStateException(path+".screenDirection has unknown legacy value: "+old);
                object.put("screenDirection",canonical);
            }
        }
        List<Map.Entry<String,JsonNode>> children=new ArrayList<>();Iterator<Map.Entry<String,JsonNode>> fields=object.fields();fields.forEachRemaining(children::add);
        for(var child:children)canonicalize(child.getValue(),path+'.'+child.getKey(),directions);
    }
}
