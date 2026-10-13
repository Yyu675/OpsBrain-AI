package com.devops.agent.domain.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 与独立检查共用同一套行为断言，Maven verify 自动纳入。 */
@DisplayName("企业告警/恢复/诊断模板真实行为")
class AlertNotificationRendererTest {
    @Test void firingFacts() { AlertNotificationChecks.firingHasFactsAndOneHeading(); }
    @Test void honestMissingFields() { AlertNotificationChecks.lowRiskAndMissingFieldsAreHonest(); }
    @Test void stringEvidence() { AlertNotificationChecks.stringEvidenceAndThresholdStaySourceBased(); }
    @Test void actualAssignee() { AlertNotificationChecks.assigneeIsActualNotImpliedAcknowledgement(); }
    @Test void missingRecoveryValue() { AlertNotificationChecks.recoveryDoesNotReuseFailureValue(); }
    @Test void groupRecovery() { AlertNotificationChecks.recoveryFreshValueAndGroupGuard(); }
    @Test void closedTicket() { AlertNotificationChecks.closedTicketDoesNotAskToCloseAgain(); }
    @Test void estimatedTimes() { AlertNotificationChecks.fallbackTimesAreExplicit(); }
    @Test void invalidAndLongTimes() { AlertNotificationChecks.invalidAndLongDurationsDoNotLie(); }
    @Test void safeLinks() { AlertNotificationChecks.unsafeBaseUrlsCannotBecomeLinks(); }
    @Test void safeBoundedText() { AlertNotificationChecks.externalTextIsEscapedRedactedAndBounded(); }
    @Test void diagnosisSummary() { AlertNotificationChecks.diagnosisSeparatesEvidenceStatusesAndNeverUrgent(); }
}
