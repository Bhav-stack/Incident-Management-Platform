package io.aegis.incident.api;

import io.aegis.incident.app.ProposalService;
import io.aegis.incident.domain.Proposal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public record DecisionRequest(@NotBlank String approver) {
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

        return response;
    }

    @PostMapping("/proposals/{proposalId}/approve")
    public Proposal approve(@PathVariable UUID proposalId,
                            @Valid @RequestBody DecisionRequest request) {
        return proposals.approve(proposalId, request.approver());
    }

    @PostMapping("/proposals/{proposalId}/reject")
    public Proposal reject(@PathVariable UUID proposalId,
                           @Valid @RequestBody DecisionRequest request) {
        return proposals.reject(proposalId, request.approver());
    }
}