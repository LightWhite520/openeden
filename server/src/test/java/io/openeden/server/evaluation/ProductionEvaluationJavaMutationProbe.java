package io.openeden.server.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ProductionEvaluationJavaMutationProbe {
    private ProductionEvaluationJavaMutationProbe() {}

    static void replaceFirstDecision(ProductionEvaluationReport report) {
        List<PairwiseEvaluation.Decision> decisions = report.getPairwiseEvaluation().getDecisions();
        PairwiseEvaluation.Decision current = decisions.get(0);
        decisions.set(0, new PairwiseEvaluation.Decision(
            current.getDecisionId(),
            current.getScenarioCaseId(),
            current.getCandidateRepetition(),
            current.getLeftArtifactFingerprint(),
            current.getRightArtifactFingerprint(),
            current.getCandidateSlot(),
            current.getWinner(),
            current.getDimensionWinners(),
            !current.getFactualRegressionObserved(),
            current.getRationale()
        ));
    }

    static void replaceDimensionWinner(ProductionEvaluationReport report) {
        Map<PairwiseEvaluation.JudgeDimension, PairwiseEvaluation.Winner> winners =
            report.getPairwiseEvaluation().getDecisions().get(0).getDimensionWinners();
        winners.put(PairwiseEvaluation.JudgeDimension.ATRI_FIDELITY, PairwiseEvaluation.Winner.TIE);
    }

    static void clearManifestFingerprints(ProductionEvaluationReport report) {
        report.getProductionProvenance().getManifestFingerprints().clear();
    }

    static void clearPositivePathDimensions(ProductionEvaluationReport report) {
        Set<CompanionQualityMetrics.BioDimension> dimensions =
            report.getMetrics().getBio().getPositivePathDimensions().getValue();
        dimensions.clear();
    }

    static void clearMetricEvidenceFingerprints(ProductionEvaluationReport report) {
        report.getMetrics().getRelationship().getPairwiseWinRate().getEvidence().getManifestFingerprints().clear();
    }
}
