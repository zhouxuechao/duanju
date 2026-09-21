package com.yourapp.drama.production;
/** Estimates authored dialogue before TTS and reconciles the measured audio afterward. */
public final class DialogueTimingEstimator {
 public enum ReconciliationAction { NONE,TIMELINE_NUDGE,SPEED_ADJUST,SILENCE_TRIM,MANUAL_REVIEW }
 public record Reconciliation(ReconciliationAction action,double deltaSeconds,double speedRatio){}
 public double estimateSeconds(String text,double charsPerSecond,double emotionPauseSeconds){if(text==null||text.isBlank())return 0;int spoken=(int)text.codePoints().filter(c->!Character.isWhitespace(c)&&"，。！？；、,.!?;:：…—".indexOf(c)<0).count();long commas=text.codePoints().filter(c->"，、,；;：:".indexOf(c)>=0).count(),stops=text.codePoints().filter(c->"。！？.!?…".indexOf(c)>=0).count();return spoken/Math.max(1,charsPerSecond)+commas*.18+stops*.35+Math.max(0,emotionPauseSeconds);}
 public Reconciliation reconcile(double estimated,double actual){double delta=actual-estimated,abs=Math.abs(delta);if(abs<=.08)return new Reconciliation(ReconciliationAction.NONE,delta,1);if(abs<=.35)return new Reconciliation(ReconciliationAction.TIMELINE_NUDGE,delta,1);double ratio=estimated<=0?1:actual/estimated;if(abs<=1&&ratio>=.9&&ratio<=1.1)return new Reconciliation(ReconciliationAction.SPEED_ADJUST,delta,ratio);if(delta>0&&abs<=1.5)return new Reconciliation(ReconciliationAction.SILENCE_TRIM,delta,1);return new Reconciliation(ReconciliationAction.MANUAL_REVIEW,delta,ratio);}
}
