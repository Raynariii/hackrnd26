package io.blueprints.core.ops;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;

import java.util.function.Function;

/**
 * Проверка фрагментов кода из операций графа до вставки в файл: так пользователь получает понятную ошибку
 * («ожидалось условие»), а не сбой разбора всего файла. Ошибка → {@link UnsupportedGraphOpException}.
 */
final class Snippets {

    private final ParserConfiguration configuration = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    /**
     * Один или несколько операторов; вернуть код, готовый к вставке. Точку с запятой в конце можно не ставить:
     * {@code x++} → {@code x++;}.
     */
    String statements(String code) {
        String c = code.strip();
        if (c.isEmpty()) {
            throw new UnsupportedGraphOpException("Пустой код");
        }
        if (block(c) != null) {
            return c;
        }
        if (!c.endsWith(";") && !c.endsWith("}") && block(c + ";") != null) {
            return c + ";";
        }
        throw problem(c, p -> p.parseBlock("{\n" + c + "\n}"));
    }

    /** Код — ровно один оператор указанного вида (полная замена конструкции). */
    boolean isSingle(String code, Class<? extends Statement> kind) {
        BlockStmt b = block(code.strip());
        return b != null && b.getStatements().size() == 1 && kind.isInstance(b.getStatement(0));
    }

    boolean isExpression(String code) {
        String c = code.strip();
        return !c.isEmpty() && parse(p -> p.parseExpression(c)) != null;
    }

    /** Объявление члена класса; вернуть true, если это поле. */
    boolean member(String code) {
        BodyDeclaration<?> d = parse(p -> p.parseBodyDeclaration(code));
        if (d == null) {
            throw problem(code, p -> p.parseBodyDeclaration(code));
        }
        return d.isFieldDeclaration();
    }

    /** {@code case 1, 2} / {@code 1, 2} / {@code default} → заголовок case без двоеточия. */
    String caseHeader(String code) {
        String c = code.strip();
        if (c.endsWith(":")) {
            c = c.substring(0, c.length() - 1).strip();
        } else if (c.endsWith("->")) {
            c = c.substring(0, c.length() - 2).strip();
        }
        String header = c.startsWith("case ") || c.equals("default") ? c : "case " + c;
        Statement s = parse(p -> p.parseStatement("switch (x) { " + header + ": }"));
        if (!(s instanceof SwitchStmt sw) || sw.getEntries().size() != 1) {
            throw new UnsupportedGraphOpException("Ожидались метки case, например: 1, 2 или default");
        }
        return header;
    }

    void catchParameter(String inner) {
        if (!(parse(p -> p.parseStatement("try {} catch (" + inner + ") {}")) instanceof TryStmt)) {
            throw new UnsupportedGraphOpException("Ожидалось: тип и имя исключения, например IOException e");
        }
    }

    /** {@code (Reader r = ...)} → проверить ресурсы try. */
    void tryResources(String parenthesized) {
        if (!(parse(p -> p.parseStatement("try " + parenthesized + " {}")) instanceof TryStmt)) {
            throw new UnsupportedGraphOpException("Ожидались ресурсы try, например (Scanner in = new Scanner(System.in))");
        }
    }

    /** {@code for (...)} → заголовок for или for-each. */
    void loopHeader(String header) {
        Statement s = parse(p -> p.parseStatement(header + " {}"));
        if (!(s instanceof ForStmt) && !(s instanceof ForEachStmt)) {
            throw new UnsupportedGraphOpException("Ожидался заголовок цикла, например: int i = 0; i < n; i++");
        }
    }

    private BlockStmt block(String code) {
        return parse(p -> p.parseBlock("{\n" + code + "\n}"));
    }

    private <T> T parse(Function<JavaParser, ParseResult<T>> call) {
        ParseResult<T> result = call.apply(new JavaParser(configuration));
        return result.isSuccessful() ? result.getResult().orElse(null) : null;
    }

    private <T> UnsupportedGraphOpException problem(String code, Function<JavaParser, ParseResult<T>> call) {
        ParseResult<T> result = call.apply(new JavaParser(configuration));
        String reason = result.getProblems().isEmpty() ? "" : ": " + result.getProblems().get(0).getMessage()
                .lines().findFirst().orElse("");
        return new UnsupportedGraphOpException("Код не разбирается" + reason + " — " + code.strip());
    }
}
