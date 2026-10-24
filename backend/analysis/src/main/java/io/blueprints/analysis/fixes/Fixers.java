package io.blueprints.analysis.fixes;

import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixSafety;
import io.blueprints.core.model.ProjectModel;

import java.util.List;
import java.util.Optional;

/**
 * Реестр авто-исправлений — единственное место, где решается, что можно применить автоматически.
 *
 * <p>{@code Analyzer} выставляет {@code Fix.autoApplicable} по {@link #supports}, а {@code FixEngine}
 * применяет правку через тот же {@link #forRule}. Новая подроль 2b добавляет фиксер сюда и создаёт
 * свой класс в этом пакете, не касаясь правил (`PLAN.md` §6.1, п. 2–3).
 */
public final class Fixers {

    private static final List<Fixer> ALL = List.of(
            new IndexHoistFixer(),
            new StringBuilderFixer(),
            new ConstantFixer(),
            new ForEachFixer());

    private Fixers() {
    }

    public static List<Fixer> all() {
        return ALL;
    }

    /**
     * Причина, по которой исправление нельзя применить автоматически, или {@code null}, если можно.
     * Для правила без фиксера — {@link FixSafety#NOT_IMPLEMENTED}.
     */
    public static String explain(Finding finding, ProjectModel model) {
        Optional<Fixer> fixer = forRule(finding.rule());
        // Не через Optional.map: null («можно применять») не должен схлопываться в «не реализовано».
        return fixer.isPresent() ? fixer.get().explain(finding, model) : FixSafety.NOT_IMPLEMENTED;
    }

    /** Совпадает ли {@code Fix.autoApplicable} с реальной применимостью. */
    public static boolean supports(Finding finding, ProjectModel model) {
        return forRule(finding.rule()).isPresent() && explain(finding, model) == null;
    }

    public static Optional<Fixer> forRule(String rule) {
        return ALL.stream().filter(fixer -> fixer.rule().equals(rule)).findFirst();
    }
}
