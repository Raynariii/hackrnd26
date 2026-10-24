package io.blueprints.core.graph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.util.Optional;

/**
 * Какие узлы AST — члены обзорного графа и у каких есть блок-схема. Одно правило для overview, рёбер call,
 * methodFlow и операций графа.
 *
 * <p>Член — метод, конструктор, компактный конструктор record, инициализатор ({@code static {}} / {@code {}}) или
 * поле, объявленные в классе, в теле константы enum или в анонимном классе.
 */
public final class Members {

    private Members() {
    }

    public static boolean isMember(Node n) {
        if (!(n instanceof MethodDeclaration || n instanceof ConstructorDeclaration
                || n instanceof CompactConstructorDeclaration || n instanceof InitializerDeclaration
                || n instanceof FieldDeclaration)) {
            return false;
        }
        Node parent = n.getParentNode().orElse(null);
        return parent instanceof TypeDeclaration<?> || parent instanceof EnumConstantDeclaration
                || parent instanceof ObjectCreationExpr;
    }

    /** Член с телом-блок-схемой: метод (не абстрактный), конструктор, компактный конструктор, инициализатор. */
    public static boolean hasFlow(Node n) {
        return isMember(n) && body(n).isPresent();
    }

    public static Optional<BlockStmt> body(Node n) {
        if (n instanceof MethodDeclaration m) {
            return m.getBody();
        }
        if (n instanceof ConstructorDeclaration c) {
            return Optional.of(c.getBody());
        }
        if (n instanceof CompactConstructorDeclaration c) {
            return Optional.of(c.getBody());
        }
        if (n instanceof InitializerDeclaration i) {
            return Optional.of(i.getBody());
        }
        return Optional.empty();
    }

    /** Подпись члена на обзоре: имя метода, имя record у компактного конструктора, «static {}», «x, y» у поля. */
    static String label(BodyDeclaration<?> member) {
        if (member instanceof MethodDeclaration m) {
            return m.getNameAsString();
        }
        if (member instanceof ConstructorDeclaration c) {
            return c.getNameAsString();
        }
        if (member instanceof CompactConstructorDeclaration c) {
            return c.getNameAsString();
        }
        if (member instanceof InitializerDeclaration i) {
            return i.isStatic() ? "static {}" : "{}";
        }
        return String.join(", ", ((FieldDeclaration) member).getVariables().stream()
                .map(v -> v.getNameAsString()).toList());
    }

    static NodeKind kind(BodyDeclaration<?> member) {
        if (member instanceof FieldDeclaration) {
            return NodeKind.FIELD;
        }
        if (member instanceof ConstructorDeclaration || member instanceof CompactConstructorDeclaration) {
            return NodeKind.CONSTRUCTOR;
        }
        return NodeKind.METHOD; // метод и инициализатор
    }

    /** Заголовок для поля code / блока «Начало»: объявление без тела. */
    static String header(BodyDeclaration<?> member) {
        if (member instanceof MethodDeclaration m) {
            return m.getDeclarationAsString(true, true, true);
        }
        if (member instanceof ConstructorDeclaration c) {
            return c.getDeclarationAsString(true, true, true);
        }
        if (member instanceof CompactConstructorDeclaration c) {
            String mods = c.getModifiers().stream().map(x -> x.getKeyword().asString() + " ")
                    .reduce("", String::concat);
            return mods + c.getNameAsString();
        }
        if (member instanceof InitializerDeclaration i) {
            return i.isStatic() ? "static" : "{}";
        }
        return member.toString();
    }
}
