package com.yourapp.drama.workflow;

import com.yourapp.drama.provider.volcengine.VolcengineProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NovelIntelligenceProviderGateTest {
    @Test void requiresLiveSwitchApiKeyAndOnlyTheSelectedTextRoleModel(){
        VolcengineProperties properties=new VolcengineProperties();properties.setApiKey("key");properties.setNovelAnalysisModel("analysis-model");
        assertThatThrownBy(()->new NovelIntelligenceProviderGate(properties,"llm",false).validate("novel_analysis")).isInstanceOf(WorkflowException.class);
        properties.setApiKey("");assertThatThrownBy(()->new NovelIntelligenceProviderGate(properties,"llm",true).validate("novel_analysis")).isInstanceOf(WorkflowException.class);
        properties.setApiKey("key");assertThatCode(()->new NovelIntelligenceProviderGate(properties,"llm",true).validate("novel_analysis")).doesNotThrowAnyException();
        assertThatThrownBy(()->new NovelIntelligenceProviderGate(properties,"llm",true).validate("novel_screenwriter")).isInstanceOf(WorkflowException.class);
        properties.setNovelScreenwriterModel("writer-model");assertThatCode(()->new NovelIntelligenceProviderGate(properties,"llm",true).validate("novel_screenwriter")).doesNotThrowAnyException();
        properties.setImageModel("");properties.setVideoModel("");assertThatCode(properties::validateTransport).doesNotThrowAnyException();
    }
}
