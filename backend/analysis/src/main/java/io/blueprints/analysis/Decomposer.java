package io.blueprints.analysis;

import io.blueprints.analysis.decompose.MonolithApplier;
import io.blueprints.analysis.decompose.MonolithSplitter;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;

import java.util.List;

/**
 * Декомпозиция монолита: предложение и применение разбиения god-class на
 * {@code Parser / Analyzer / Optimizer / Main}.
 *
 * <p>Фасад для api: классификация (имена + вызовы соседей, без LLM и без запуска кода) —
 * в {@code decompose/MonolithSplitter}, перенос кода — в {@code decompose/MonolithApplier}.
 * Предложение объяснимое: для каждого класса видно, какие методы туда попали и почему.
 *
 * <p>Применение (v1, G7) переносит только <b>static-методы</b> в новые классы: {@code main},
 * конструкторы, поля и instance-методы остаются в исходном классе. Это механическая правка,
 * которая не меняет поведение; компиляцию и дифф-тесты проверяет quality gate роли 4.
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

    /** Результат применения: новые исходники, пояснения и описание изменения. */
    public record Applied(List<SourceFile> files, List<String> notes, String description) {
    }

    private final MonolithSplitter splitter = new MonolithSplitter();
    private final MonolithApplier applier = new MonolithApplier();

    /** Предложение по всем god-классам проекта. */
    public Proposal propose(ProjectModel model) {
        MonolithSplitter.Split split = splitter.split(model);
        List<ProposedClass> classes = split.groups().stream()
                .map(group -> new ProposedClass(group.className(), group.responsibility(), group.members()))
                .toList();
        return new Proposal(classes, split.notes());
    }

    /**
     * Применяет декомпозицию к копии исходников: новые файлы классов + изменённый исходный файл.
     * Ничего не запускает и не проверяет поведение — это делает quality gate (сборка + дифф-тесты).
     *
     * @throws FixNotApplicableException если декомпозировать нечего или результат не разбирается
     */
    public Applied apply(List<SourceFile> files) {
        MonolithApplier.Applied applied = applier.apply(files);
        return new Applied(applied.files(), applied.notes(), applied.description());
    }
}
