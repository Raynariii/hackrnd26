package io.blueprints.analysis;

import java.util.List;

public record AnalysisReport(List<Finding> findings, List<MethodMetrics> metrics) {
}
