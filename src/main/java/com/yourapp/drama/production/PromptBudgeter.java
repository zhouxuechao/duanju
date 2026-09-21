package com.yourapp.drama.production;

import java.util.*;

/** Keeps required production contracts while dropping low-value repetition first. */
public final class PromptBudgeter {
    public record Section(String name,String content,int priority,boolean required){}
    public String compile(List<Section> input,int maxChars){
        LinkedHashMap<String,Section> unique=new LinkedHashMap<>();
        for(Section section:input){String content=normalize(section.content());if(content.isBlank())continue;unique.put(section.name(),new Section(section.name(),content,section.priority(),section.required()));}
        List<Section> selected=new ArrayList<>(unique.values());
        while(length(selected)>maxChars){Section removable=selected.stream().filter(s->!s.required()).min(Comparator.comparingInt(Section::priority)).orElse(null);if(removable==null)throw new IllegalArgumentException("PROMPT_BUDGET_REQUIRED_SECTION_OVERFLOW：身份、动作、终点或关键连续性约束超过安全长度");selected.remove(removable);}
        return render(selected);
    }
    private int length(List<Section> values){return render(values).length();}
    private String render(List<Section> values){StringBuilder out=new StringBuilder();for(Section value:values){if(!out.isEmpty())out.append('\n');out.append('[').append(value.name()).append("]\n").append(value.content());}return out.toString();}
    private String normalize(String value){if(value==null)return "";StringBuilder out=new StringBuilder();Set<String> lines=new LinkedHashSet<>();for(String line:value.replace("\r","").split("\n")){String clean=line.trim().replaceAll("[ \\t]+"," ");if(!clean.isBlank()&&lines.add(clean))out.append(clean).append('\n');}return out.toString().trim();}
}
