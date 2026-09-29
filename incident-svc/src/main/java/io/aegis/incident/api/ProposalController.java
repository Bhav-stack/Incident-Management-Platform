package io.aegis.incident.api;

import io.aegis.incident.app.ProposalService;
import io.aegis.incident.domain.Proposal;
import io.aegis.incident.security.ControlPlaneAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ProposalController {

    private final ProposalService proposals;

    public ProposalController(ProposalService proposals) {
        this.proposals = proposals;
    }

    @GetMapping("/incidents/{incidentId}/proposals")
    public List<Proposal> listForIncident(@PathVariable UUID incidentId) {
        return proposals.listForIncident(incidentId);
    }

    @GetMapping("/proposals/{proposalId}")
    public Map<String, Object> get(@PathVariable UUID proposalId) {
        Proposal proposal = proposals.get(proposalId);

        Map<String, Object> response = new java.util.HashMap<>();

        response.put("id", proposal.getId());
        response.put("externalId", proposal.getExternalId());
        response.put("actionType", proposal.getActionType());
        response.put("params", proposal.getParams());
        response.put("riskLevel", proposal.getRiskLevel());
        response.put("confidence", proposal.getConfidence());
        response.put("reasoning", proposal.getReasoning());
        response.put("status", proposal.getStatus());
        response.put("createdAt", proposal.getCreatedAt());
        response.put("decidedAt", proposal.getDecidedAt());
        response.put("decidedBy", proposal.getDecidedBy());
        response.put("approvals", proposal.getApprovals());
        response.put("approvalsRequired", proposal.getApprovalsRequired());

        return response;
    }

    /**
     * Approves as the authenticated caller. The approver is not a request
     * field: it comes from the credential — a token subject or an operator key
     * — so a caller cannot sign off as somebody else. High-risk actions need
     * two distinct operators, so the proposal may come back still PENDING with
     * one recorded approval.
     */
    @PostMapping("/proposals/{proposalId}/approve")
    public Proposal approve(@PathVariable UUID proposalId, HttpServletRequest request) {
        return proposals.approve(proposalId, ControlPlaneAuthFilter.identityOf(request));
    }

    /** A single verified operator can reject: a veto needs one signature. */
    @PostMapping("/proposals/{proposalId}/reject")
    public Proposal reject(@PathVariable UUID proposalId, HttpServletRequest request) {
        return proposals.reject(proposalId, ControlPlaneAuthFilter.identityOf(request));
    }
}