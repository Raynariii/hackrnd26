package io.blueprints.analysis.rules;

import io.blueprints.analysis.Rule;

import java.util.List;

/**
 * Реестр правил анализатора — единственное место, где перечислены правила по умолчанию
 * (CLAUDE.md: новое правило = класс, реализующий {@link Rule}, строка в реестре и тест).
 *
 * <p>Порядок — порядок показа в панели «Проблемы»: сначала дефекты сложности, потом структура.
 * Список неизменяемый; {@code Analyzer.defaultRules()} делегирует сюда, чтобы правила роли 2
 * регистрировались в одном файле и не конфликтовали при параллельной работе подролей.
 */
public final class Rules {

    private Rules() {
    }

    /** Правила из PLAN.md §4 в порядке показа. */
    public static List<Rule> defaultRules() {
        return List.of(
                new NestedLoopSameCollectionRule(),
                new CollectionLookupInLoopRule(),
                new StringConcatInLoopRule(),
                new LinkedListGetInLoopRule(),
                new DeepNestingRule(4, 15),
                new LongMethodRule(40),
                new DuplicateCodeRule(5),
                new EmptyCatchRule(),
                new MagicNumberRule(),
                new GodClassRule(20, 15, 400));
    }
}
