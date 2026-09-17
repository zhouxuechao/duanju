package com.yourapp.drama.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourapp.drama.model.ProviderException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class StructuredJsonTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void rejectsMalformedJsonInsteadOfRewritingProviderOutput(){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            StructuredJson json=new StructuredJson(mapper,factory.getValidator());
            Map<String,Object> definition=mapper.convertValue(mapper.readTree("""
                {"type":"object","additionalProperties":false,
                 "properties":{"averageShotLength":{"type":"number"},"label":{"type":"string"}},
                 "required":["averageShotLength","label"]}
                """),new TypeReference<>(){});
            JsonNode schema=json.schema(definition);
            assertThatThrownBy(()->json.parse("{\"averageShotLength\":3.0\",\"label\":\"take3\"}",schema,JsonNode.class,"req-1"))
                .isInstanceOf(ProviderException.class);
            assertThatThrownBy(()->json.parse("{\"averageShotLength\":3.0\",\"label\":\"take3\"\"}",schema,JsonNode.class,"req-2"))
                .isInstanceOf(ProviderException.class);
        }catch(ProviderException error){throw error;}catch(Exception error){throw new RuntimeException(error);}
    }

    @Test void rejectsMissingObjectClose(){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            StructuredJson json=new StructuredJson(mapper,factory.getValidator());
            Map<String,Object> definition=mapper.convertValue(mapper.readTree("""
                {"type":"object","additionalProperties":false,
                 "properties":{"items":{"type":"array","items":{"type":"object","additionalProperties":false,
                   "properties":{"value":{"type":"number"}},"required":["value"]}}},"required":["items"]}
                """),new TypeReference<>(){});
            assertThatThrownBy(()->json.parse("{\"items\":[{\"value\":3.0\"]}",json.schema(definition),JsonNode.class,"req-3"))
                .isInstanceOf(ProviderException.class);
        }catch(ProviderException error){throw error;}catch(Exception error){throw new RuntimeException(error);}
    }

    @Test void rejectsEveryAdditionalPropertyForbiddenByTheSchema(){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            StructuredJson json=new StructuredJson(mapper,factory.getValidator());
            Map<String,Object> definition=mapper.convertValue(mapper.readTree("""
                {"type":"object","additionalProperties":false,
                 "properties":{"value":{"type":"number"}},"required":["value"]}
                """),new TypeReference<>(){});JsonNode schema=json.schema(definition);
            assertThatThrownBy(()->json.parse("{\"value\":3,\"characterRelativePosition\":\"unused duplicate\"}",schema,JsonNode.class,"req-4"))
                .isInstanceOf(ProviderException.class).hasMessageContaining("characterRelativePosition");
            assertThatThrownBy(()->json.parse("{\"characterRelativePosition\":\"still missing value\"}",schema,JsonNode.class,"req-5"))
                .isInstanceOf(ProviderException.class).hasMessageContaining("value");
            assertThatThrownBy(()->json.parse("{\"value\":3,\"locationId\":\"must not be silently discarded\"}",schema,JsonNode.class,"req-6"))
                .isInstanceOf(ProviderException.class).hasMessageContaining("locationId");
        }catch(ProviderException error){throw error;}catch(Exception error){throw new RuntimeException(error);}
    }

    @Test void rejectsMalformedEntityIdsInsteadOfGuessingTheirTargets(){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            StructuredJson json=new StructuredJson(mapper,factory.getValidator());String canonical="654459ed-5804-49f4-ab37-218b476ab253";
            Map<String,Object> definition=mapper.convertValue(mapper.readTree("""
                {"type":"object","properties":{"ids":{"type":"array","items":{"type":"string","enum":["%s"]}}},"required":["ids"]}
                """.formatted(canonical)),new TypeReference<>(){});JsonNode schema=json.schema(definition);
            assertThatThrownBy(()->json.parse("{\"ids\":[\"654459ed-5804-4926-a8aa-0b4950b0935b\"]}",schema,JsonNode.class,"req-7"))
                .isInstanceOf(ProviderException.class);
            String zhang="1f01de0a-acfb-492e-8dc2-9e2a13ba5579",chen="a34ae280-6e4f-4926-a8aa-0b4950b0935b";
            Map<String,Object> people=mapper.convertValue(mapper.readTree("""
                {"type":"object","properties":{"ids":{"type":"array","items":{"type":"string","enum":["%s","%s"]}}},"required":["ids"]}
                """.formatted(zhang,chen)),new TypeReference<>(){});
            assertThatThrownBy(()->json.parse("{\"ids\":[\"1f01de0a-acfb-4926-a8aa-0b4950b0935b\"]}",json.schema(people),JsonNode.class,"req-7b"))
                .isInstanceOf(ProviderException.class);
            assertThatThrownBy(()->json.parse("{\"ids\":[\"ffffffff-ffff-ffff-ffff-ffffffffffff\"]}",json.schema(people),JsonNode.class,"req-7c"))
                .isInstanceOf(ProviderException.class);
            Map<String,Object> direction=mapper.convertValue(mapper.readTree("""
                {"type":"object","properties":{"side":{"type":"string","enum":["LEFT"]}},"required":["side"]}
                """),new TypeReference<>(){});
            assertThatThrownBy(()->json.parse("{\"side\":\"RIGHT\"}",json.schema(direction),JsonNode.class,"req-8"))
                .isInstanceOf(ProviderException.class);
        }catch(ProviderException error){throw error;}catch(Exception error){throw new RuntimeException(error);}
    }

    @Test void rejectsEnumAliasesInsteadOfReinterpretingProviderOutput(){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            StructuredJson json=new StructuredJson(mapper,factory.getValidator());Map<String,Object> definition=mapper.convertValue(mapper.readTree("""
                {"type":"object","properties":{"relation":{"type":"string","enum":["CONTINUOUS","REACTION","CUTAWAY"]}},"required":["relation"]}
                """),new TypeReference<>(){});JsonNode schema=json.schema(definition);
            assertThatThrownBy(()->json.parse("{\"relation\":\"REVEAL\"}",schema,JsonNode.class,"req-9")).isInstanceOf(ProviderException.class);
            assertThatThrownBy(()->json.parse("{\"relation\":\"PAYOFF\"}",schema,JsonNode.class,"req-10")).isInstanceOf(ProviderException.class);
            assertThatThrownBy(()->json.parse("{\"relation\":\"UNKNOWN\"}",schema,JsonNode.class,"req-11")).isInstanceOf(ProviderException.class);
        }catch(ProviderException error){throw error;}catch(Exception error){throw new RuntimeException(error);}
    }
    @Test void rejectsMisnestedStateChangesInsteadOfMovingFields(){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            StructuredJson json=new StructuredJson(mapper,factory.getValidator());Map<String,Object> definition=mapper.convertValue(mapper.readTree("""
                {"type":"object","properties":{"shots":{"type":"array","items":{"type":"object","additionalProperties":false,
                 "properties":{"referenceViews":{"type":"object"},"stateChanges":{"type":"array"}},"required":["referenceViews","stateChanges"]}}},"required":["shots"]}
                """),new TypeReference<>(){});JsonNode schema=json.schema(definition);
            assertThatThrownBy(()->json.parse("{\"shots\":[{\"referenceViews\":{}},\"stateChanges\":[]}]}",schema,JsonNode.class,"req-12"))
                .isInstanceOf(ProviderException.class);
        }catch(ProviderException error){throw error;}catch(Exception error){throw new RuntimeException(error);}
    }
}
