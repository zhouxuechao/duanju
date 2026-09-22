package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProviderPromptCompilerTest {
    @Test void providerCompilersOwnTheirProtocolAndPromptBudgets(){
        ProductionModels.PromptIR ir=mock(ProductionModels.PromptIR.class);
        List<PromptBudgeter.Section> imageSections=List.of(
            new PromptBudgeter.Section("TASK / IMAGE PURPOSE","KEYFRAME",100,true),
            new PromptBudgeter.Section("OUTPUT","9:16",80,true),
            new PromptBudgeter.Section("OPTIONAL", "x".repeat(20_000),1,false));
        List<PromptBudgeter.Section> videoSections=List.of(
            new PromptBudgeter.Section("REFERENCE AUTHORITY","locked",100,true),
            new PromptBudgeter.Section("CURRENT ACTION / ENDPOINT","turns and stops",100,true),
            new PromptBudgeter.Section("HIGH-RISK CONTINUITY LOCKS","identity locked",100,true));
        List<PromptBudgeter.Section> voiceSections=List.of(
            new PromptBudgeter.Section("VOICE IDENTITY","approved voice",100,true),
            new PromptBudgeter.Section("SPEECH CONTENT","line only",100,true),
            new PromptBudgeter.Section("OUTPUT CONSTRAINTS","dry voice",100,true));

        ProviderCompiler seedream=new SeedreamCompiler();
        ProviderCompiler seedance=new SeedanceCompiler();
        ProviderCompiler seedAudio=new SeedAudioCompiler();
        ProviderCompiler structuredText=new ArkStructuredTextCompiler();
        String image=seedream.compile(ir,imageSections),video=seedance.compile(ir,videoSections),voice=seedAudio.compile(ir,voiceSections),text=structuredText.compile(ir,List.of(new PromptBudgeter.Section("SKILL CONTRACT","contract",100,true)));

        assertThat(seedream.provider()).isEqualTo("SEEDREAM");
        assertThat(seedance.provider()).isEqualTo("SEEDANCE");
        assertThat(seedAudio.provider()).isEqualTo("SEED_AUDIO");
        assertThat(structuredText.provider()).isEqualTo("ARK_STRUCTURED_TEXT");
        assertThat(image).contains("[TASK / IMAGE PURPOSE]","[OUTPUT]").hasSizeLessThanOrEqualTo(12_000);
        assertThat(video).contains("[REFERENCE AUTHORITY]","[CURRENT ACTION / ENDPOINT]","[HIGH-RISK CONTINUITY LOCKS]").hasSizeLessThanOrEqualTo(10_000);
        assertThat(voice).contains("[VOICE IDENTITY]","[SPEECH CONTENT]","[OUTPUT CONSTRAINTS]").hasSizeLessThanOrEqualTo(4_000);
        assertThat(text).contains("[SKILL CONTRACT]","contract");
    }
}
