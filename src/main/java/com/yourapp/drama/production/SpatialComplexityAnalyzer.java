package com.yourapp.drama.production;
import java.util.List;
public final class SpatialComplexityAnalyzer {
 public enum Level { S0,S1,S2,S3,S4 }
 public Level classify(int actorCount,int movementCount,boolean complexRoute,boolean combat){if(combat||actorCount>=5&&movementCount>=3&&complexRoute)return Level.S4;if(actorCount>=4&&movementCount>=2||complexRoute)return Level.S3;if(actorCount>=3)return Level.S2;if(actorCount==2)return Level.S1;return Level.S0;}
 public List<String> requiredRuleIds(Level level){return switch(level){case S0->List.of("director-simple-position");case S1->List.of("director-axis","director-two-person-blocking");case S2->List.of("director-presence-ledger","director-full-blocking");case S3->List.of("director-presence-ledger","director-position-migration");case S4->List.of("director-presence-ledger","director-position-migration","director-topview","director-previs");};}
}
