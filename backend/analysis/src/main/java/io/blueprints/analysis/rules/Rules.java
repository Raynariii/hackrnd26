package io.blueprints.analysis.rules;

import io.blueprints.analysis.Rule;

import java.util.List;

/**
 * Реестр правил анализа — единственное место, где перечислены правила модуля.
 *
 * <p>Добавили правило — новый класс в этом пакете и строка в {@link #all()}. Подроли роли 2
 * регистрируют своё в своих реестрах (`PLAN.md` §6.1): правила — здесь, исправления — в
 * {@code io.blueprints.analysis.fixes.Fixers}.
 */
public final class Rules {

    private Rules() {
    }

    /** Порядок показа: сначала дефекты сложности, потом структура. */
    public static List<Rule> all() {
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
