package com.yourapp.drama.api;

import com.yourapp.drama.model.ProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorsTest {
    @Test void providerFailuresExposeSafeCodeAndRequestId(){
        var response=new ApiErrors().provider(new ProviderException("MODEL_NOT_OPEN","模型未开通","0217-request",403,false,false));
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        @SuppressWarnings("unchecked") Map<String,Object> body=(Map<String,Object>)response.getBody();
        assertThat(body).containsEntry("code","MODEL_NOT_OPEN").containsEntry("providerRequestId","0217-request");
    }
    @Test void aDisconnectedMediaClientDoesNotTriggerASecondJsonResponse(){
        assertThat(new ApiErrors().unknown(new AsyncRequestNotUsableException("response already failed"))).isNull();
    }
}
