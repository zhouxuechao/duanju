package com.yourapp.drama.production;
public final class AssetDependencyAnalyzer {
 public enum Level { A0,A1,A2,A3 }
 public Level classify(int recurringSubjects,int recurringLocations,int shotCount,boolean crossShotContinuity,boolean longForm){if(longForm||recurringSubjects>=5||recurringLocations>=5||shotCount>=50)return Level.A3;if(crossShotContinuity||recurringSubjects>1||recurringLocations>1)return Level.A2;if(recurringSubjects==1)return Level.A1;return Level.A0;}
}
