package com.devops.agent.controller;

import com.devops.agent.common.audit.SecretRevealAuditor;
import com.devops.agent.domain.notify.NotifyChannelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotifyChannelControllerContractTest {

    @Mock
    private NotifyChannelService service;
    @Mock
    private SecretRevealAuditor revealAuditor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void maskedBranchPreservesExistingJsonShape() throws Exception {
        NotifyChannelService.IngestSecretView view = new NotifyChannelService.IngestSecretView(
                true, "db", "••••last", true, "同步更新 Alertmanager");
        when(service.listViews()).thenReturn(new NotifyChannelService.NotifyChannelList(
                java.util.List.of(), view, true));
        NotifyChannelController controller = new NotifyChannelController(service, revealAuditor);

        var response = controller.ingestSecret(false, request("/api/v1/notify-channels/ingest-secret"));
        JsonNode data = objectMapper.valueToTree(response.getData());

        assertEquals(view.configured(), data.get("configured").asBoolean());
        assertEquals(view.source(), data.get("source").asText());
        assertEquals(view.secretMasked(), data.get("secretMasked").asText());
        assertEquals(view.hmacConfigured(), data.get("hmacConfigured").asBoolean());
        assertEquals(view.hint(), data.get("hint").asText());
        assertFalse(data.has("secret"));
        assertFalse(data.has("webhook"));
    }

    @Test
    void revealBranchPreservesPlaintextJsonShapeAndAudit() throws Exception {
        NotifyChannelService.Revealed view = new NotifyChannelService.Revealed(
                NotifyChannelService.KEY_ALERT_INGEST, "https://alerts.example/hook", "plain-secret");
        when(service.revealIngest()).thenReturn(view);
        MockHttpServletRequest request = request("/api/v1/notify-channels/ingest-secret");
        NotifyChannelController controller = new NotifyChannelController(service, revealAuditor);

        var response = controller.ingestSecret(true, request);
        JsonNode data = objectMapper.valueToTree(response.getData());

        assertEquals(view.channelKey(), data.get("channelKey").asText());
        assertEquals(view.webhook(), data.get("webhook").asText());
        assertEquals(view.secret(), data.get("secret").asText());
        assertFalse(data.has("secretMasked"));
        assertFalse(data.has("configured"));
        verify(revealAuditor).record(SecretRevealAuditor.ACTION_NOTIFY_REVEAL,
                NotifyChannelService.KEY_ALERT_INGEST, request.getRequestURI(), request);
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}
