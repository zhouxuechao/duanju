package com.yourapp.drama.production;
import java.util.*;
public final class FirstShotAnchorValidator {
 public List<ProductionModels.Risk> validate(String description){List<ProductionModels.Risk> risks=new ArrayList<>();String text=description==null?"":description;boolean ambiguous=(text.contains("他")&&text.contains("她"))||text.matches(".*(?:某人|有人|他们|她们).*" );if(ambiguous)risks.add(new ProductionModels.Risk("MULTI_SUBJECT_AMBIGUITY","ERROR","firstShot.subject","首镜存在多个主体但没有明确归属"));boolean body=text.matches(".*(?:一只|一双|那只|这只)?(?:左手|右手|双手|手|脸|脚|眼睛).*"),owned=text.matches(".*[\u4e00-\u9fa5]{2,8}(?:的|伸出|抬起|举起|收回|握紧|张开|用)(?:左手|右手|双手|手|脸|脚|眼睛).*" );if(body&&(ambiguous||!owned))risks.add(new ProductionModels.Risk("DANGLING_BODY_PART_REFERENCE","ERROR","firstShot.description","首镜身体部位没有绑定明确人物"));return List.copyOf(risks);}
}
