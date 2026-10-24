package io.blueprints.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Мелкие операции над AST, общие для правил: циклы, границы, нормализация текста, id узлов графа. */
public final class Ast {

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "record", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
            "var", "void", "volatile", "while", "true", "false", "null", "yield", "sealed", "permits");

    private Ast() {
    }

    public static boolean isLoop(Node node) {
        return node instanceof ForStmt || node instanceof ForEachStmt
                || node instanceof WhileStmt || node instanceof DoStmt;
    }

    /** Тело цикла (блок или единственный оператор). */
    public static Statement loopBody(Node loop) {
        if (loop instanceof ForStmt forStmt) {
            return forStmt.getBody();
        }
        if (loop instanceof ForEachStmt each) {
            return each.getBody();
        }
        if (loop instanceof WhileStmt whileStmt) {
            return whileStmt.getBody();
        }
        if (loop instanceof DoStmt doStmt) {
            return doStmt.getBody();
        }
        return null;
    }

    /** Циклы-предки, ближайший первым. */
    public static List<Node> enclosingLoops(Node node) {
        List<Node> loops = new ArrayList<>();
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (isLoop(current)) {
                loops.add(current);
            }
            current = current.getParentNode().orElse(null);
        }
        return loops;
    }

    /** Сколько циклов охватывает узел. */
    public static int loopDepth(Node node) {
        return enclosingLoops(node).size();
    }

    /**
     * По чему итерируется цикл — в нормализованном виде, чтобы сравнить два цикла:
     * {@code for (Order o : orders)} → {@code orders}; {@code for (i=0;i<orders.size();i++)} → {@code orders.size()}.
     */
    public static String iterationSource(Node loop) {
        if (loop instanceof ForEachStmt each) {
            return compact(each.getIterable().toString());
        }
        if (loop instanceof ForStmt forStmt) {
            return boundOperand(forStmt);
        }
        if (loop instanceof WhileStmt whileStmt) {
            return compact(whileStmt.getCondition().toString());
        }
        if (loop instanceof DoStmt doStmt) {
            return compact(doStmt.getCondition().toString());
        }
        return null;
    }

    /**
     * Правая часть условия {@code for (int i = 0; i < orders.size(); i++)} → {@code orders.size()}.
     * Для цикла без счётчика возвращает условие целиком.
     */
    public static String boundOperand(ForStmt forStmt) {
        String counter = counterVariable(forStmt);
        Expression comparison = forStmt.getCompare().orElse(null);
        if (comparison == null) {
            return null;
        }
        if (comparison instanceof BinaryExpr binary && counter != null) {
            Expression left = binary.getLeft();
            if (left instanceof NameExpr name && name.getNameAsString().equals(counter)) {
                return compact(binary.getRight().toString());
            }
            Expression right = binary.getRight();
            if (right instanceof NameExpr name && name.getNameAsString().equals(counter)) {
                return compact(binary.getLeft().toString());
            }
        }
        return compact(comparison.toString());
    }

    /** Имя переменной-счётчика классического {@code for}, либо null. */
    public static String counterVariable(ForStmt forStmt) {
        for (Expression init : forStmt.getInitialization()) {
            if (init instanceof VariableDeclarationExpr declaration && !declaration.getVariables().isEmpty()) {
                VariableDeclarator first = declaration.getVariables().get(0);
                return first.getNameAsString();
            }
        }
        return null;
    }

    /** Текст без пробелов — для сравнения выражений. */
    public static String compact(String text) {
        return text == null ? null : text.replaceAll("\\s+", "");
    }

    public static String code(Node node) {
        return compact(node.toString());
    }

    /** Операторы блока; пустой список для не-блока. */
    public static List<Statement> statements(Node body) {
        return body instanceof BlockStmt block ? block.getStatements() : List.of();
    }

    /**
     * Нормализованный текст узла: имена → {@code V}, числа → {@code N}, строковые литералы
     * остаются как есть. Используется для поиска дублирования: код, отличающийся только
     * именами переменных, — дубль, а разные сообщения в {@code println} — нет.
     */
    public static String normalize(Node node) {
        // Комментарии сравниваемому коду не принадлежат: их текст у копий обычно разный
        // и сильно занижает похожесть (ловили на корпусе — копии методов «не находились»).
        Node copy = node.clone();
        copy.getAllContainedComments().forEach(Comment::remove);
        copy.removeComment();
        String text = copy.toString();
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (ch == '"' || ch == '\'') {
                int end = index + 1;
                while (end < text.length()) {
                    char inner = text.charAt(end);
                    if (inner == '\\') {
                        end += 2;
                        continue;
                    }
                    end++;
                    if (inner == ch) {
                        break;
                    }
                }
                int stop = Math.min(end, text.length());
                out.append(text, index, stop);
                index = stop;
                continue;
            }
            if (Character.isDigit(ch)) {
                int end = index + 1;
                while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                out.append('N');
                index = end;
                continue;
            }
            if (Character.isJavaIdentifierStart(ch)) {
                int end = index + 1;
                while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
                    end++;
                }
                String word = text.substring(index, end);
                out.append(KEYWORDS.contains(word) ? word : "V");
                index = end;
                continue;
            }
            out.append(ch);
            index++;
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    /** id узла графа по соглашению contract/graph.schema.json: {@code "g" + astId}. */
    public static String nodeId(ProjectModel model, Node node) {
        String astId = model.idOf(node);
        return astId == null ? null : "g" + astId;
    }

    public static int startLine(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(0);
    }

    public static int endLine(Node node) {
        return node.getRange().map(range -> range.end.line).orElse(startLine(node));
    }

    public static String fileOf(ProjectModel model, Node node) {
        return model.fileOf(node).orElse("");
    }
}
