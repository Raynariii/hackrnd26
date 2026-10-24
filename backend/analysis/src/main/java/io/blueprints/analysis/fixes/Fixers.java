package io.blueprints.analysis.fixes;

import io.blueprints.analysis.Finding;
import io.blueprints.core.model.ProjectModel;

import java.util.List;
import java.util.Optional;

/**
 * Реестр авто-исправлений (подроль 2b): единственное место, где перечислены исправления.
 *
 * <p>Своё исправление = класс, реализующий {@link Fixer}, строка в {@link #all()} и тест.
 * {@code Analyzer} спрашивает реестр, применимо ли исправление ({@link #supports}), поэтому
 * {@code Fix.autoApplicable} в диагностике совпадает с тем, что сделает {@code FixEngine#apply}.
 */
public final class Fixers {

    private static final List<Fixer> ALL = List.of(
            new CollectionLookupFix(),
            new StringConcatFix(),
            new MagicNumberFix(),
            new LinkedListIndexFix(),
            new NestedLoopIndexFix());

    private Fixers() {
    }

    public static List<Fixer> all() {
        return ALL;
    }

    public static Optional<Fixer> byKind(String kind) {
        return ALL.stream().filter(fixer -> fixer.kind().equals(kind)).findFirst();
    }

    /** Можно ли применить исправление находки в этой версии кода (для {@code Fix.autoApplicable}). */
    public static boolean supports(ProjectModel model, Finding finding) {
        if (finding.fix() == null) {
            return false;
        }
        return byKind(finding.fix().kind())
                .map(fixer -> fixer.supports(model, finding))
                .orElse(false);
    }
}
