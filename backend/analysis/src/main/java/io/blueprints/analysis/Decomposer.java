package io.blueprints.analysis;

import io.blueprints.analysis.decompose.MonolithSplitter;
import io.blueprints.core.model.ProjectModel;

import java.util.List;

/**
 * Декомпозиция монолита: предложение, как разложить god-class на {@code Parser / Analyzer / Optimizer / Main}.
 *
 * <p>Фасад для api: сам алгоритм классификации (имена + вызовы соседей, без LLM и без запуска кода)
 * живёт в {@code decompose/MonolithSplitter}. Предложение объяснимое: для каждого класса видно,
 * какие методы туда попали и почему.
 *
 * <p>v1, как зафиксировано в STATUS.md: предлагаем перенос, ничего не перемещая. Применение —
 * отдельный шаг после quality gate (роль 4), потому что перенос полей между файлами меняет
 * видимость и требует компиляции.
 */
public final class Decomposer {

    /** Один класс в предложении: имя, ответственность и что в него переносим. */
    public record ProposedClass(String className, String responsibility, List<String> members) {
    }

    /** Предложение по одному монолиту. */
    public record Proposal(List<ProposedClass> proposal, List<String> notes) {
        public boolean isEmpty() {
            return proposal.isEmpty();
        }
    }

    private final MonolithSplitter splitter = new MonolithSplitter();

    /** Предложение по всем god-классам проекта. */
    public Proposal propose(ProjectModel model) {
        MonolithSplitter.Split split = splitter.split(model);
        List<ProposedClass> classes = split.groups().stream()
                .map(group -> new ProposedClass(group.className(), group.responsibility(), group.members()))
                .toList();
        return new Proposal(classes, split.notes());
    }
}
