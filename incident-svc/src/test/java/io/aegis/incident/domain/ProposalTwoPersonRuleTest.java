package io.aegis.incident.domain;

import io.aegis.incident.rules.ProposalDraft;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-person rule is enforced in the entity, so no caller can bypass it by
 * going around the service: whatever route reaches {@link Proposal#approve},
 * the same identity cannot supply both signatures.
 */
class ProposalTwoPersonRuleTest {

    private static Proposal proposalRequiring(int approvals) {
        Incident incident = new Incident("checkout-service", Severity.SEV2, "test");
        Proposal proposal = new Proposal(incident, new ProposalDraft(
                "rollback_deploy", "HIGH", 0.95, Map.of("target", "checkout-service"),
                Map.of("evidence", List.of("deploy regression", "error rate 8.5"))));
        proposal.setApprovalsRequired(approvals);
        return proposal;
    }

    @Test
    void oneSignatureIsEnoughForASingleApproverAction() {
        Proposal proposal = proposalRequiring(1);

        assertTrue(proposal.approve("alice"));
        assertEquals(ProposalStatus.APPROVED, proposal.getStatus());
        assertEquals(List.of("alice"), proposal.approvalOperators());
    }

    @Test
    void firstOfTwoSignaturesIsRecordedButDoesNotReleaseTheAction() {
        Proposal proposal = proposalRequiring(2);

        assertFalse(proposal.approve("alice"));
        assertEquals(ProposalStatus.PENDING, proposal.getStatus());
        assertEquals(List.of("alice"), proposal.approvalOperators());
        assertTrue(proposal.awaitingFurtherApproval());
        assertEquals(2, proposal.getApprovalsRequired());
    }

    @Test
    void theSameOperatorCannotSupplyBothSignatures() {
        Proposal proposal = proposalRequiring(2);
        proposal.approve("alice");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> proposal.approve("alice"));

        assertTrue(e.getMessage().contains("already approved by alice"));
        assertEquals(ProposalStatus.PENDING, proposal.getStatus());
        assertEquals(List.of("alice"), proposal.approvalOperators());
    }

    @Test
    void twoDistinctOperatorsReleaseTheAction() {
        Proposal proposal = proposalRequiring(2);

        proposal.approve("alice");
        assertTrue(proposal.approve("bob"));

        assertEquals(ProposalStatus.APPROVED, proposal.getStatus());
        assertEquals(List.of("alice", "bob"), proposal.approvalOperators());
        assertFalse(proposal.awaitingFurtherApproval());
    }

    @Test
    void anAnonymousSignatureIsRefused() {
        Proposal proposal = proposalRequiring(1);

        assertThrows(IllegalStateException.class, () -> proposal.approve("  "));
        assertEquals(ProposalStatus.PENDING, proposal.getStatus());
    }

    @Test
    void aRejectedProposalCannotAlsoBeApproved() {
        Proposal proposal = proposalRequiring(2);
        proposal.reject("alice");

        assertThrows(IllegalStateException.class, () -> proposal.approve("bob"));
        assertEquals(ProposalStatus.REJECTED, proposal.getStatus());
    }

    @Test
    void aSingleOperatorCanAlwaysReject() {
        // A veto needs one signature; an irreversible change needs two.
        Proposal proposal = proposalRequiring(2);

        proposal.reject("alice");

        assertEquals(ProposalStatus.REJECTED, proposal.getStatus());
        assertEquals("alice", proposal.getDecidedBy());
    }
}
