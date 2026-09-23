package com.yourapp.drama.workflow;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class ChapterBoundaryDetector {
    private final List<Pattern> patterns=List.of(
            Pattern.compile("^\\s*第[零〇一二三四五六七八九十百千万两0-9]{1,12}[章节回卷部篇].{0,80}$"),
            Pattern.compile("^\\s*第[零〇一二三四五六七八九十百千万两0-9]{1,8}卷(?:\\s+第[零〇一二三四五六七八九十百千万两0-9]{1,12}章)?.{0,80}$"),
            Pattern.compile("(?i)^\\s*chapter\\s+[0-9ivxlcdm]+(?:[.:：\\s-].{0,80})?$")
    );
    public boolean isBoundary(String line){if(line==null||line.isBlank()||line.length()>120)return false;String value=line.strip();return patterns.stream().anyMatch(pattern->pattern.matcher(value).matches());}
}
